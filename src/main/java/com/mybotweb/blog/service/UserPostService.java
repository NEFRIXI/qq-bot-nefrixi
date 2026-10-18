package com.mybotweb.blog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mybotweb.blog.auth.UserPrincipal;
import com.mybotweb.blog.config.BlogProperties;
import com.mybotweb.blog.dto.ArticleSaveRequest;
import com.mybotweb.blog.dto.PostCreateRequest;
import com.mybotweb.blog.entity.Article;
import com.mybotweb.blog.entity.ArticleStatus;
import com.mybotweb.blog.mapper.ArticleMapper;
import com.mybotweb.blog.vo.ArticleListItemVo;
import com.mybotweb.common.exception.BusinessException;
import com.mybotweb.common.result.ResultCode;
import com.mybotweb.security.RedisRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 普通用户的发帖 / 我的帖子 / 删自己的帖子。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么需要一个独立的 Service，而不是让 Controller 直接调
 * {@link ArticleService}</b>
 *
 * <p>{@code ArticleService} 是管理员的视角 —— 它不管"这篇是谁的"，
 * 因为管理员能改任何一篇。而普通用户只能动自己的，
 * 这个"归属校验"是本类唯一真正新增的逻辑，
 * 把它放在 Service 里，Controller 就只剩一层薄薄的转发。
 *
 * <p>发文本身仍然复用 {@code ArticleService.create(request, authorUserId)}，
 * 不重写一遍 slug 生成、摘要截取、contentHash 计算那些细节 ——
 * 那些逻辑只能有一份，复制出去两边就会慢慢不一致。
 *
 * @author mybotweb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPostService {

    /** 限流窗口：一小时 */
    private static final long ONE_HOUR_MS = 3_600_000L;

    /** "我的帖子"一次最多返回多少条 */
    private static final int MAX_MINE = 100;

    private final ArticleMapper articleMapper;

    private final ArticleService articleService;

    private final RedisRateLimiter rateLimiter;

    private final BlogProperties blogProperties;

    /**
     * 发一篇帖子。
     *
     * @param user    当前登录用户
     * @param request 标题和正文
     * @return 新文章 id
     */
    @Transactional
    public Long create(UserPrincipal user, PostCreateRequest request) {
        rateLimiter.checkOrThrow(
                "rl:post:user:" + user.getId(),
                blogProperties.getRateLimit().getPostPerHour(),
                ONE_HOUR_MS,
                "发帖太频繁了，稍后再试");

        // 把用户请求翻译成管理员的保存请求。
        // slug 留空 → ArticleService 自动按时间生成，避免用户撞唯一索引；
        // status 写死 PUBLISHED → 用户发帖就是发布，没有草稿态
        ArticleSaveRequest save = new ArticleSaveRequest();
        save.setTitle(request.getTitle());
        save.setContent(request.getContent());
        save.setSummary(request.getSummary());
        save.setSlug(null);
        save.setStatus(ArticleStatus.PUBLISHED);

        Long id = articleService.create(save, user.getId());
        log.info("{} 发帖：articleId={}", user.toLogTag(), id);
        return id;
    }

    /**
     * 列出当前用户发过的帖子。
     *
     * @param user 当前登录用户
     * @return 列表（不含正文，理由见 {@link ArticleListItemVo} 的类注释）
     */
    public List<ArticleListItemVo> listMine(UserPrincipal user) {
        List<Article> articles = articleMapper.selectList(
                new LambdaQueryWrapper<Article>()
                        .eq(Article::getAuthorUserId, user.getId())
                        .orderByDesc(Article::getCreatedAt)
                        .last("LIMIT " + MAX_MINE));

        return articles.stream().map(this::toListItem).toList();
    }

    /**
     * 删除自己的帖子。
     *
     * <p><b>归属校验一句话都不能省</b>：不校验的话，任何人把 URL 里的
     * id 改一下就能删别人的文章。这是本类最重要的一个 if。
     *
     * @param user 当前登录用户
     * @param id   文章 id
     */
    @Transactional
    public void delete(UserPrincipal user, Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在");
        }
        // 【必须是这条自己发的】用 equals 而不是 ==：都是 Long 包装类型，
        // == 比较的是对象引用，超过 127 之后每次都 false
        if (article.getAuthorUserId() == null
                || !article.getAuthorUserId().equals(user.getId())) {
            log.warn("{} 试图删除不属于自己的文章 id={}", user.toLogTag(), id);
            throw new BusinessException(ResultCode.FORBIDDEN, "只能删除自己发的帖子");
        }

        articleService.delete(id);
        log.info("{} 删除了自己的帖子 articleId={}", user.toLogTag(), id);
    }

    /**
     * 实体转列表项。
     *
     * @param a 文章
     * @return 列表项（不含正文）
     */
    private ArticleListItemVo toListItem(Article a) {
        ArticleListItemVo vo = new ArticleListItemVo();
        vo.setId(a.getId());
        vo.setSlug(a.getSlug());
        vo.setTitle(a.getTitle());
        vo.setSummary(a.getSummary());
        vo.setStatus(a.getStatus());
        vo.setPublishedAt(a.getPublishedAt());
        vo.setUpdatedAt(a.getUpdatedAt());
        // 这两个计数在 Redis 里，列表这里不逐个去查 ——
        // "我的帖子"页用不到，省掉 N 次 Redis 往返
        vo.setLikeCount(0);
        vo.setViewCount(0);
        return vo;
    }
}
