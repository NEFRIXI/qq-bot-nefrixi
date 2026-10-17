package com.mybotweb.blog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mybotweb.blog.config.BlogProperties;
import com.mybotweb.blog.dto.ArticleSaveRequest;
import com.mybotweb.blog.entity.Article;
import com.mybotweb.blog.entity.ArticleStatus;
import com.mybotweb.blog.mapper.ArticleMapper;
import com.mybotweb.blog.markdown.MarkdownRenderer;
import com.mybotweb.blog.vo.ArticleDetailVo;
import com.mybotweb.blog.vo.ArticleListItemVo;
import com.mybotweb.blog.vo.PageVo;
import com.mybotweb.common.exception.BusinessException;
import com.mybotweb.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 文章业务：查询、新建、编辑、删除。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么这些方法上没有 {@code @Transactional}</b>
 *
 * <p>每个方法对数据库都只有<b>一次写操作</b>（一条 insert、一条 update、
 * 一条 delete）。单条 SQL 本身就是原子的，加事务只是多一层代理，
 * 不解决任何问题 —— 而事务代理会带来"方法内部调用不走代理"这类
 * 只有踩过才记得住的坑。
 *
 * <p>以后如果出现"要同时改两张表"的操作（比如加标签表），
 * <b>那时</b>再给对应的方法加 {@code @Transactional}，别提前加。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>草稿和已发布的边界在哪</b>
 *
 * <p>全部在<b>查询条件</b>里，不在权限代码里 ——
 * 前台的两个方法都写死了 {@code status = 已发布}，
 * 所以草稿根本查不出来；后台的方法不加这个条件，所以草稿也能看到。
 * 这比"先查出来再判断要不要给它看"可靠：后者一旦漏判就是内容泄露。
 *
 * @author mybotweb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleService {

    /** 自动生成摘要时截取多少字 */
    private static final int SUMMARY_LENGTH = 150;

    /** 自动生成 slug 时用的时间格式 */
    private static final DateTimeFormatter SLUG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ArticleMapper articleMapper;

    private final ArticleCounterService counterService;

    private final MarkdownRenderer markdownRenderer;

    private final BlogProperties blogProperties;

    // ========================================================
    // 前台（访客看的，不需要登录）
    // ========================================================

    /**
     * 前台文章列表 —— 只有已发布的。
     *
     * @param page 第几页，从 1 开始；为 null 或小于 1 时按 1 处理
     * @param size 每页几条；为 null 时用配置里的默认值，超过上限时按上限截断
     * @return 分页结果
     */
    public PageVo<ArticleListItemVo> listPublished(Integer page, Integer size) {
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                // ★ 这一行是"草稿不对外"的全部实现
                .eq(Article::getStatus, ArticleStatus.PUBLISHED)
                // 按发布时间倒序 —— 不是按创建时间，理由见 Article.publishedAt 的注释
                .orderByDesc(Article::getPublishedAt)
                // 加个 id 兜底：两篇文章发布时间完全相同时（比如批量导入），
                // 没有第二排序键的话每次查询的顺序可能不一样，
                // 表现为翻页时某篇文章重复出现或消失
                .orderByDesc(Article::getId);

        Page<Article> result = articleMapper.selectPage(buildPage(page, size), wrapper);
        return toPageVo(result);
    }

    /**
     * 前台文章详情 —— 按 slug 查，只有已发布的。
     *
     * <p>这个方法<b>会顺带把浏览量 +1</b>。放在这里而不是让 Controller
     * 单独调一次，是为了保证"看到详情"和"计数"永远一起发生 ——
     * 分两处写迟早会漏掉一处。
     *
     * @param slug URL 短名
     * @return 文章详情，含渲染后的 HTML
     * @throws BusinessException 文章不存在，或者存在但还没发布（404）
     */
    public ArticleDetailVo getPublishedBySlug(String slug) {
        Article article = articleMapper.selectOne(new LambdaQueryWrapper<Article>()
                .eq(Article::getSlug, slug)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED));

        if (article == null) {
            // 【草稿和不存在返回同样的提示】如果草稿返回"这篇还没发布"，
            // 那就等于告诉外面"这个地址上有东西，只是还没公开"，
            // 相当于泄露了未发布内容的存在
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在");
        }

        // 浏览量 +1。不做去重 —— 按用户要求，每次访问都算一次
        long viewCount = counterService.incrementView(article.getId());

        ArticleDetailVo vo = toDetailVo(article, false);
        // 用刚加完的值，省一次 Redis 查询，也避免"显示出来的数比实际少 1"
        vo.setViewCount(viewCount);
        return vo;
    }

    /**
     * 确认一篇文章存在且已发布 —— 点赞前调用。
     *
     * <p><b>为什么点赞要查这一次库</b>：点赞接口只收到一个 id，
     * 不查的话，请求一个不存在的 id（比如 {@code /999999/like}）
     * 也会在 Redis 里创建出一个 {@code article:like_count:999999}。
     * 这种 key 永远读不到、永远不会被清理，是纯粹的垃圾。
     *
     * <p>为什么不顺便返回文章：调用方只需要"确认过了"这个事实，
     * 返回整个对象会让人以为要用它做别的事。
     *
     * @param id 文章 id
     * @throws BusinessException 文章不存在，或者还是草稿（404）
     */
    public void requirePublished(Long id) {
        Article article = articleMapper.selectById(id);
        // 草稿和不存在给同样的答复：告诉外面"这里有一篇草稿"本身就是泄露
        if (article == null || article.getStatus() == null
                || article.getStatus() != ArticleStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在");
        }
    }

    // ========================================================
    // 后台（需要登录）
    // ========================================================

    /**
     * 后台文章列表 —— 草稿和已发布都返回。
     *
     * @param status 只看某个状态；传 {@code null} 表示全部
     * @param page   第几页
     * @param size   每页几条
     * @return 分页结果
     */
    public PageVo<ArticleListItemVo> listForAdmin(Integer status, Integer page, Integer size) {
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Article::getStatus, status);
        }
        // 后台按"最后修改时间"倒序：你刚改完的文章应该排在最上面，
        // 而不是按发布时间沉到好几页之后
        wrapper.orderByDesc(Article::getUpdatedAt).orderByDesc(Article::getId);

        Page<Article> result = articleMapper.selectPage(buildPage(page, size), wrapper);
        return toPageVo(result);
    }

    /**
     * 后台取一篇文章用于编辑 —— 草稿也能取到。
     *
     * <p>返回里会带上 {@code contentMarkdown}（正文原文），
     * 编辑框里要填的就是它。
     *
     * @param id 文章 id
     * @return 文章详情，含 Markdown 原文
     * @throws BusinessException 文章不存在（404）
     */
    public ArticleDetailVo getForEdit(Long id) {
        return toDetailVo(requireArticle(id), true);
    }

    /**
     * 新建文章（管理员用）。
     *
     * @param request 文章内容
     * @return 新文章的 id
     * @throws BusinessException 状态值非法、slug 已被占用
     */
    public Long create(ArticleSaveRequest request) {
        return create(request, null);
    }

    /**
     * 新建文章，并记下作者。
     *
     * <p><b>为什么要多这一个参数，而不是让调用方 insert 完再 update 一次</b>
     * 先 insert 再 update 是两次写库，中间那一刻这条文章的作者是空的 ——
     * 如果此时有人正好在读列表，"作者"字段就会显示成管理员。
     * 一次写进去就没有这个中间态。
     *
     * @param request      文章内容
     * @param authorUserId 作者的用户 id；{@code null} 表示管理员发的
     * @return 新文章的 id
     * @throws BusinessException 状态值非法、slug 已被占用
     */
    public Long create(ArticleSaveRequest request, Long authorUserId) {
        validateStatus(request.getStatus());

        String slug = resolveSlug(request.getSlug(), null);

        LocalDateTime now = LocalDateTime.now();

        Article article = new Article();
        article.setSlug(slug);
        article.setTitle(request.getTitle().trim());
        article.setContent(request.getContent());
        article.setSummary(resolveSummary(request.getSummary(), request.getContent()));
        article.setStatus(request.getStatus());
        article.setContentHash(sha256(request.getContent()));
        article.setCreatedAt(now);
        article.setUpdatedAt(now);
        // 普通用户发的文章记下作者；管理员发的留 null
        article.setAuthorUserId(authorUserId);
        // 直接以"已发布"保存时，发布时间就是现在
        if (request.getStatus() == ArticleStatus.PUBLISHED) {
            article.setPublishedAt(now);
        }

        articleMapper.insert(article);

        log.info("新建文章：id={}，slug={}，状态={}",
                article.getId(), slug, ArticleStatus.describe(request.getStatus()));

        // insert 之后 MyBatis-Plus 把数据库生成的自增 id 回填到了这个对象上，
        // 所以这里能直接拿到
        return article.getId();
    }

    /**
     * 编辑文章。
     *
     * @param id      文章 id
     * @param request 新的内容
     * @throws BusinessException 文章不存在、状态值非法、slug 被别人占用
     */
    public void update(Long id, ArticleSaveRequest request) {
        Article article = requireArticle(id);
        validateStatus(request.getStatus());

        String slug = resolveSlug(request.getSlug(), id);

        article.setSlug(slug);
        article.setTitle(request.getTitle().trim());
        article.setContent(request.getContent());
        article.setSummary(resolveSummary(request.getSummary(), request.getContent()));
        article.setContentHash(sha256(request.getContent()));

        // ------------------------------------------------------------
        // 发布时间的三种情况，这里是最容易想错的地方：
        //
        //   草稿 → 草稿     publishedAt 保持 null
        //   草稿 → 已发布   填上当前时间（第一次发布）
        //   已发布 → 已发布 不动它（否则每次改个错别字，文章都会
        //                   跳到列表最前面，看起来像在刷屏）
        //
        // 【改回草稿时也不清空】保留原来的发布时间。
        // 这样重新发布后，它在列表里的位置和原来一致 ——
        // 清空的话重新发布会让它跳到最前面，等于无意中又刷了一次。
        // ------------------------------------------------------------
        if (request.getStatus() == ArticleStatus.PUBLISHED && article.getPublishedAt() == null) {
            article.setPublishedAt(LocalDateTime.now());
        }

        article.setStatus(request.getStatus());
        article.setUpdatedAt(LocalDateTime.now());

        articleMapper.updateById(article);

        log.info("修改文章：id={}，slug={}，状态={}",
                id, slug, ArticleStatus.describe(request.getStatus()));
    }

    /**
     * 删除文章。
     *
     * <p><b>真删，不是逻辑删除</b> —— 项目里没配 MyBatis-Plus 的逻辑删除，
     * 这条记录会被真正从表里拿掉，删了就没了。
     * 顺带把 Redis 里那篇的点赞数和浏览量一起清掉：
     * 文章的 id 是自增的、不会被复用，留着那两个 key 就是永远用不上的垃圾。
     *
     * @param id 文章 id
     * @throws BusinessException 文章不存在（404）
     */
    public void delete(Long id) {
        Article article = requireArticle(id);

        articleMapper.deleteById(id);
        counterService.removeCounters(id);

        log.info("删除文章：id={}，slug={}，标题={}", id, article.getSlug(), article.getTitle());
    }

    // ========================================================
    // 给"访问统计"用的查询
    // ========================================================

    /**
     * 数一数有多少篇文章。
     *
     * @param status 只数某个状态；传 {@code null} 数全部
     * @return 篇数
     */
    public long countByStatus(Integer status) {
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Article::getStatus, status);
        }
        Long count = articleMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    /**
     * 取所有文章（只查统计需要的字段），用于算热门排行。
     *
     * <p><b>为什么只 select 少数字段</b>：算"哪篇点赞最多"只需要 id 和标题，
     * 而这张表里有 {@code MEDIUMTEXT} 的正文。把正文全查出来再扔掉，
     * 在文章多的时候会白白占用大量内存和网络。
     * {@code select(...)} 会生成只查这几列的 SQL。
     *
     * @return 文章列表，只含 id、slug、title、status、publishedAt
     */
    public List<Article> listForStats() {
        return articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .select(Article::getId, Article::getSlug, Article::getTitle,
                        Article::getStatus, Article::getPublishedAt)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED));
    }

    // ========================================================
    // 内部方法
    // ========================================================

    /**
     * 按 id 取文章，取不到就抛 404。
     *
     * @param id 文章 id
     * @return 文章实体
     */
    private Article requireArticle(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在（id=" + id + "）");
        }
        return article;
    }

    /**
     * 构造分页参数，并做好边界处理。
     *
     * <p>分页参数是前端传来的，<b>必须当成不可信输入</b>：
     * <ul>
     *   <li>{@code page} 传 0 或负数 → 按 1 处理。
     *       不处理的话 {@code LIMIT} 的偏移量会算成负数，SQL 直接报错</li>
     *   <li>{@code size} 超过上限 → 截到上限。
     *       不处理的话有人手改 URL 成 {@code size=999999}，
     *       一次查询就能把数据库和 3Mbps 的带宽一起打满</li>
     * </ul>
     *
     * @param page 页码
     * @param size 每页条数
     * @return MyBatis-Plus 的分页对象
     */
    private Page<Article> buildPage(Integer page, Integer size) {
        long current = (page == null || page < 1) ? 1L : page;
        long pageSize = (size == null || size < 1)
                ? blogProperties.getPage().getSize()
                : Math.min(size, blogProperties.getPage().getMaxSize());
        return new Page<>(current, pageSize);
    }

    /**
     * 把数据库分页结果转成前端结构，并补上 Redis 里的两个计数。
     *
     * @param page MyBatis-Plus 的分页结果
     * @return 前端用的分页结构
     */
    private PageVo<ArticleListItemVo> toPageVo(Page<Article> page) {
        List<Article> records = page.getRecords();

        List<Long> ids = records.stream().map(Article::getId).toList();

        // 【一次批量取，不是循环里逐个取】10 篇文章就是 2 次 Redis 往返
        // （点赞一次、浏览一次），而不是 20 次
        Map<Long, Long> likeCounts = counterService.getLikeCounts(ids);
        Map<Long, Long> viewCounts = counterService.getViewCounts(ids);

        List<ArticleListItemVo> list = records.stream()
                .map(article -> toListItemVo(
                        article,
                        likeCounts.getOrDefault(article.getId(), 0L),
                        viewCounts.getOrDefault(article.getId(), 0L)))
                .toList();

        return PageVo.of(page, list);
    }

    /**
     * 单篇文章 → 列表项。
     *
     * <p>注意这个方法<b>不碰 content</b>，所以列表接口天然不可能带上正文 ——
     * 这是结构上保证的，不靠写 SQL 的人记得。
     *
     * @param article 文章实体
     * @param likeCount 点赞数
     * @param viewCount 浏览量
     * @return 列表项
     */
    private ArticleListItemVo toListItemVo(Article article, long likeCount, long viewCount) {
        ArticleListItemVo vo = new ArticleListItemVo();
        vo.setId(article.getId());
        vo.setSlug(article.getSlug());
        vo.setTitle(article.getTitle());
        vo.setSummary(article.getSummary());
        vo.setStatus(article.getStatus());
        vo.setPublishedAt(article.getPublishedAt());
        vo.setUpdatedAt(article.getUpdatedAt());
        vo.setLikeCount(likeCount);
        vo.setViewCount(viewCount);
        return vo;
    }

    /**
     * 单篇文章 → 详情。
     *
     * @param article         文章实体
     * @param includeMarkdown 是否带上 Markdown 原文（后台编辑要，前台不要）
     * @return 详情
     */
    private ArticleDetailVo toDetailVo(Article article, boolean includeMarkdown) {
        ArticleDetailVo vo = new ArticleDetailVo();
        vo.setId(article.getId());
        vo.setSlug(article.getSlug());
        vo.setTitle(article.getTitle());
        vo.setSummary(article.getSummary());
        vo.setContentHtml(markdownRenderer.render(article.getContent()));
        if (includeMarkdown) {
            vo.setContentMarkdown(article.getContent());
        }
        vo.setStatus(article.getStatus());
        vo.setPublishedAt(article.getPublishedAt());
        vo.setCreatedAt(article.getCreatedAt());
        vo.setUpdatedAt(article.getUpdatedAt());
        vo.setLikeCount(counterService.getLikeCount(article.getId()));
        vo.setViewCount(counterService.getViewCount(article.getId()));
        return vo;
    }

    /**
     * 检查状态值合不合法。
     *
     * <p>{@code @NotNull} 只能拦住"没传"，拦不住"传了 7"。
     * 不检查的话数据库里会出现第三个谁都不认识的状态：
     * 前台查不到（不等于 1）、后台列表里显示成空白，只能手工改库。
     *
     * @param status 状态值
     */
    private void validateStatus(Integer status) {
        if (!ArticleStatus.isValid(status)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "文章状态只能是 0（草稿）或 1（已发布），收到的是 " + status);
        }
    }

    /**
     * 决定这篇用哪个 slug，并确保它没被别的文章占用。
     *
     * @param requestedSlug 请求里带的 slug，可为空
     * @param excludeId     编辑时传自己的 id —— 检查"有没有被占用"时要排除自己，
     *                      否则改一篇文章别的内容时会报"slug 已被占用"
     * @return 可用的 slug
     * @throws BusinessException slug 已被别的文章占用
     */
    private String resolveSlug(String requestedSlug, Long excludeId) {
        String slug = (requestedSlug == null) ? "" : requestedSlug.trim();

        if (slug.isEmpty()) {
            // 兜底：自动按时间生成一个。虽然不好看，但保证"标题写完了就能存下来"，
            // 不会因为没想好短名而卡住
            slug = "post-" + SLUG_TIME_FORMAT.format(LocalDateTime.now());
            log.info("创建文章时没提供短名，自动生成：{}", slug);
        }

        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .eq(Article::getSlug, slug);
        if (excludeId != null) {
            wrapper.ne(Article::getId, excludeId);
        }

        Long count = articleMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "短名「" + slug + "」已经被别的文章用了，换一个");
        }

        return slug;
    }

    /**
     * 决定摘要用什么。
     *
     * <p>作者填了就用自己的；没填就从正文里自动截一段。
     * 截取前先把 Markdown 记号去掉 —— 否则列表页会看到
     * {@code ## 标题}、{@code **加粗**} 这种原样的符号，
     * 很难看。
     *
     * @param summary 作者填的摘要，可为空
     * @param content 正文（Markdown 原文）
     * @return 摘要文本
     */
    private String resolveSummary(String summary, String content) {
        if (summary != null && !summary.isBlank()) {
            return summary.trim();
        }

        String plain = content
                // 代码块先去：它里面可能含有 # 和 *，留着会被后面的规则误伤。
                // [\s\S] 是为了让 . 能跨越换行
                .replaceAll("```[\\s\\S]*?```", " ")
                // 标题开头的 # 号
                .replaceAll("(?m)^#{1,6}\\s*", "")
                // 引用和列表开头的 > - * +
                .replaceAll("(?m)^[>\\-*+]\\s*", "")
                // 行内代码的反引号，只去符号留内容
                .replaceAll("`([^`]*)`", "$1")
                // 图片和链接，只留文字部分：![说明](地址) / [说明](地址)
                .replaceAll("!?\\[([^\\]]*)]\\([^)]*\\)", "$1")
                // 强调记号 * _ ~
                .replaceAll("[*_~]", "")
                // 连续空白压成一个空格（Markdown 里换行是排版，不是句子间隔）
                .replaceAll("\\s+", " ")
                .trim();

        if (plain.isEmpty()) {
            return null;
        }
        return plain.length() <= SUMMARY_LENGTH
                ? plain
                : plain.substring(0, SUMMARY_LENGTH) + "…";
    }

    /**
     * 算正文的 SHA-256，存进 {@code content_hash} 列。
     *
     * <p><b>这个值现在不参与任何判断</b>，是给第二步"机器人增量同步"预留的：
     * 机器人可以比较这次拉到的 hash 和上次存的，相同就说明正文没变，
     * 不用重复处理。光看 {@code updated_at} 不够 —— 改个错别字、
     * 甚至只是重新保存一次，时间戳都会变。
     *
     * @param text 正文
     * @return 64 个字符的十六进制串；算法不可用时返回 null
     */
    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            // HexFormat 是 JDK 17 自带的，不用自己写"字节转十六进制"那套循环
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法，正常永远不会走到这里。
            // 真走到了也不该让"保存文章"失败 —— 这个字段现在没用上
            log.warn("计算正文摘要失败（content_hash 将为空）：{}", e.getMessage());
            return null;
        }
    }
}
