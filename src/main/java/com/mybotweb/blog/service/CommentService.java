package com.mybotweb.blog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mybotweb.blog.auth.UserPrincipal;
import com.mybotweb.blog.config.BlogProperties;
import com.mybotweb.blog.entity.AppUser;
import com.mybotweb.blog.entity.AppUserStatus;
import com.mybotweb.blog.entity.Article;
import com.mybotweb.blog.entity.ArticleStatus;
import com.mybotweb.blog.entity.Comment;
import com.mybotweb.blog.mapper.AppUserMapper;
import com.mybotweb.blog.mapper.ArticleMapper;
import com.mybotweb.blog.mapper.CommentMapper;
import com.mybotweb.blog.vo.CommentVo;
import com.mybotweb.common.exception.BusinessException;
import com.mybotweb.common.result.ResultCode;
import com.mybotweb.security.RedisRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评论的读写。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>这个类最重要的一个方法：{@link #create} 里把层级压平的那几行</b>
 *
 * <p>需求是"能回复，但回复不能再被回复"（只递归一层）。
 * 前端看起来用户是在回复某条回复，但<b>数据库里不许出现第三层</b>。
 *
 * <p>做法是：写入之前检查父评论的层级 ——
 * <ul>
 *   <li>父评论是顶层（{@code parentId == null}）→ 正常挂在它下面</li>
 *   <li>父评论本身是一条回复（{@code parentId != null}）→
 *       <b>把 parentId 改写成父评论的 parentId</b>（也就是抬到顶层），
 *       并记下 {@code replyToUserId} 表示"其实是在回那个人"</li>
 * </ul>
 *
 * <p>所以 {@code A ← B ← C} 写进库是：
 * <pre>
 *   A  parentId = null
 *   B  parentId = A
 *   C  parentId = A   ← 不是 B！replyToUserId = B 的作者
 * </pre>
 * 展示时 C 和 B 并列在 A 的楼中楼里，但 C 带一个"回复 @B"。
 * 这就是 B 站那个形态。
 *
 * @author mybotweb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    /** 一次最多返回多少条顶层评论 */
    private static final int MAX_TOP_COMMENTS = 500;

    /** 限流窗口：一分钟 */
    private static final long ONE_MINUTE_MS = 60_000L;

    private final CommentMapper commentMapper;

    private final ArticleMapper articleMapper;

    private final AppUserMapper appUserMapper;

    private final RedisRateLimiter rateLimiter;

    private final BlogProperties blogProperties;

    /**
     * 取某篇文章的评论树（顶层 + 各自的楼中楼）。
     *
     * <p>只返回 status = 0（正常）的评论，被管理员隐藏的不出现在前台。
     *
     * @param articleId 文章 id
     * @return 顶层评论列表，每条带 {@code replies}
     */
    public List<CommentVo> listByArticle(Long articleId) {
        if (articleId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "缺少文章 id");
        }

        // 一次把所有正常评论捞出来。用 parent_id + created_at 的联合索引，
        // 查询形态和索引顺序是对应的（见 V3 脚本里的注释）
        List<Comment> all = commentMapper.selectList(
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getArticleId, articleId)
                        .eq(Comment::getStatus, 0)
                        .orderByAsc(Comment::getCreatedAt)
                        .last("LIMIT " + MAX_TOP_COMMENTS));

        if (all.isEmpty()) {
            return List.of();
        }

        // 批量取作者昵称。避免 N+1 —— 一页 20 条评论不该发 20 次查询
        Map<Long, String> nameById = loadDisplayNames(all);

        // 先按 parentId 分组：null 的是一组（顶层），其余各自成组
        Map<Long, List<Comment>> childrenByParent = new LinkedHashMap<>();
        List<Comment> tops = new ArrayList<>();
        for (Comment c : all) {
            if (c.getParentId() == null) {
                tops.add(c);
            } else {
                childrenByParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
            }
        }

        List<CommentVo> result = new ArrayList<>(tops.size());
        for (Comment top : tops) {
            CommentVo vo = toVo(top, nameById);
            List<Comment> children = childrenByParent.getOrDefault(top.getId(), List.of());
            for (Comment child : children) {
                vo.getReplies().add(toVo(child, nameById));
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 发一条评论，或者回复一条评论。
     *
     * @param user      当前登录用户
     * @param articleId 文章 id
     * @param content   正文
     * @param parentId  要回复的评论 id；{@code null} 表示发顶层评论
     * @return 新建的评论（已填好作者名）
     */
    @Transactional
    public CommentVo create(UserPrincipal user, Long articleId, String content, Long parentId) {
        // 先拦人再拦内容：禁言的账号不管写什么内容都发不出去
        requireCanPublish(user);

        String text = (content == null) ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "评论内容不能为空");
        }
        if (text.length() > 1000) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "评论太长了，最多 1000 字");
        }

        // ---- 文章必须存在且已发布 ----
        Article article = articleMapper.selectById(articleId);
        if (article == null || article.getStatus() == null
                || article.getStatus() != ArticleStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在或尚未发布");
        }

        // ---- 按用户限流 ----
        // 这里按 userId 而不是 IP：评论必须登录，一个用户可能换网络，
        // 但"这个人话是不是太多"跟 IP 没关系
        rateLimiter.checkOrThrow(
                "rl:comment:user:" + user.getId(),
                blogProperties.getRateLimit().getCommentPerMinute(),
                ONE_MINUTE_MS,
                "评论太频繁了，歇一会儿再发");

        // ---- 决定层级（本类最关键的几行）----
        Long finalParentId = null;
        Long replyToUserId = null;

        if (parentId != null) {
            Comment parent = commentMapper.selectById(parentId);
            if (parent == null || !articleId.equals(parent.getArticleId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "要回复的评论不存在");
            }
            if (parent.getStatus() != null && parent.getStatus() != 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "这条评论已被隐藏，不能回复");
            }

            // 被回复的人：永远取"父评论的作者"
            replyToUserId = parent.getUserId();

            if (parent.getParentId() == null) {
                // 父评论是顶层 → 正常挂在它下面
                finalParentId = parent.getId();
            } else {
                // 【父评论本身是一条回复】用户想回复"楼中楼里的人"。
                // 把新评论抬到顶层评论下面，和那条回复并列，
                // 同时用 replyToUserId 记住"其实是在回谁"。
                // 这样数据库里永远只有两层。
                finalParentId = parent.getParentId();
            }
        }

        Comment comment = new Comment();
        comment.setArticleId(articleId);
        comment.setUserId(user.getId());
        comment.setParentId(finalParentId);
        comment.setReplyToUserId(replyToUserId);
        comment.setContent(text);
        comment.setStatus(0);
        comment.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(comment);

        log.info("{} 在文章 {} 下{}，commentId={}",
                user.toLogTag(), articleId,
                finalParentId == null ? "发表评论" : "回复评论",
                comment.getId());

        // 回填作者名，前端拿到就能直接渲染，不用再查
        CommentVo vo = toVo(comment, Map.of(user.getId(), user.displayName()));
        return vo;
    }

    /**
     * 拦住不能发内容的人（禁言 / 封禁）。
     *
     * <p>和 {@code UserPostService#requireCanPublish} 是同一套判断，
     * 但两处各自留一份：一个是发帖、一个是评论，将来完全可能只允许
     * 其中一种（比如"禁言期可以评论但不能发帖"）。共用一个工具方法
     * 反而会在那天变成障碍。
     *
     * <p>每次都查库，不从 {@code UserPrincipal} 读状态 ——
     * 那个对象是登录那一刻的快照，禁言之后不会自动更新。
     *
     * @param user 当前登录用户
     * @throws BusinessException 用户不存在，或已被禁言 / 封禁
     */
    private void requireCanPublish(UserPrincipal user) {
        AppUser author = appUserMapper.selectById(user.getId());
        if (author == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (!AppUserStatus.canPublish(author.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "账号" + AppUserStatus.describe(author.getStatus()) + "，不能发表评论");
        }
    }

    /**
     * 批量取"用户 id → 显示名"。
     *
     * @param comments 一批评论
     * @return id 到显示名的映射
     */
    private Map<Long, String> loadDisplayNames(List<Comment> comments) {
        // 作者 id 和被回复人 id 都要查，合在一起去重
        var ids = comments.stream()
                .flatMap(c -> java.util.stream.Stream.of(c.getUserId(), c.getReplyToUserId()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (ids.isEmpty()) {
            return new HashMap<>();
        }

        Map<Long, String> map = new HashMap<>();
        for (AppUser u : appUserMapper.selectBatchIds(ids)) {
            String name = (u.getNickname() == null || u.getNickname().isBlank())
                    ? u.getUsername() : u.getNickname();
            map.put(u.getId(), name);
        }
        return map;
    }

    /**
     * 实体转 VO。
     *
     * @param c          评论
     * @param nameById   作者 id → 显示名
     * @return 展示对象
     */
    private CommentVo toVo(Comment c, Map<Long, String> nameById) {
        CommentVo vo = new CommentVo();
        vo.setId(c.getId());
        vo.setArticleId(c.getArticleId());
        vo.setContent(c.getContent());
        vo.setUserId(c.getUserId());
        // 查不到名字时给一个明确的占位，而不是 null ——
        // 前端渲染 null 会变成"undefined"或者直接崩
        vo.setAuthorName(nameById.getOrDefault(c.getUserId(), "已注销用户"));
        vo.setCreatedAt(c.getCreatedAt());
        vo.setReplyToUserId(c.getReplyToUserId());
        if (c.getReplyToUserId() != null) {
            vo.setReplyToName(nameById.getOrDefault(c.getReplyToUserId(), "已注销用户"));
        }
        return vo;
    }
}
