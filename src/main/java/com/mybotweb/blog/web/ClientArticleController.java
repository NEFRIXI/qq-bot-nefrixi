package com.mybotweb.blog.web;

import com.mybotweb.blog.service.ArticleCounterService;
import com.mybotweb.blog.service.ArticleService;
import com.mybotweb.blog.vo.ArticleDetailVo;
import com.mybotweb.blog.vo.ArticleListItemVo;
import com.mybotweb.blog.vo.PageVo;
import com.mybotweb.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台接口 —— 给访客用的 JSON 数据。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>这四个接口全都不需要登录，是刻意的</b>
 *
 * <p>访客只做三件事：看列表、看详情、点赞。这三件事都不碰敏感数据，
 * 所以没必要让他们先登录 —— 用户明确要求"普通用户不做身份鉴定"。
 *
 * <p>那安全性靠什么保证？靠<b>这些接口能访问到的数据范围</b>：
 * <ul>
 *   <li>列表和详情都只返回 {@code status = 已发布} 的文章，草稿查不到</li>
 *   <li>返回的字段是标题、摘要、正文、时间、计数 ——
 *       没有作者邮箱、没有内部 id 之外的任何管理信息</li>
 *   <li>写操作只有"点赞 +1"这一个，它改不了任何文章内容</li>
 * </ul>
 * 也就是说：<b>公开的前提是这些数据本来就打算给所有人看</b>，
 * 不是因为"反正是公开的所以无所谓"。
 *
 * @author mybotweb
 */
@Slf4j
@RestController
@RequestMapping("/client/api/articles")
@RequiredArgsConstructor
public class ClientArticleController {

    private final ArticleService articleService;

    private final ArticleCounterService counterService;

    /**
     * 文章列表（分页）。
     *
     * <p>示例：{@code GET /client/api/articles?page=1&size=10}
     *
     * @param page 第几页，从 1 开始，不传默认 1
     * @param size 每页几条，不传用配置里的默认值（10）；超过上限会被截到上限
     * @return 分页的文章列表，<b>不含正文</b>
     */
    @GetMapping
    public Result<PageVo<ArticleListItemVo>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(articleService.listPublished(page, size));
    }

    /**
     * 文章详情。
     *
     * <p>示例：{@code GET /client/api/articles/why-bot-said-402}
     *
     * <p><b>这个接口会顺带把浏览量 +1</b>（在 Service 里做的），
     * 所以每调一次数字就涨一次，刷新页面也涨 —— 这是用户要的行为。
     *
     * @param slug URL 短名
     * @return 文章详情，含渲染好的 HTML
     */
    @GetMapping("/{slug}")
    public Result<ArticleDetailVo> detail(@PathVariable String slug) {
        return Result.success(articleService.getPublishedBySlug(slug));
    }

    /**
     * 给文章点赞。
     *
     * <p>示例：{@code POST /client/api/articles/12/like}
     *
     * <p>═══════════════════════════════════════════════════════════
     * <p><b>⚠ 这个接口不做任何去重 —— 点一次加一次</b>
     *
     * <p>用户明确要求：<b>不识别真人还是机器人，点一下就 +1。</b>
     * 所以这里没有"同一个人只能点一次"的逻辑，是刻意不做的：
     * <ul>
     *   <li>没有按 IP 去重 —— 同一个 WiFi 下所有人算一个人，本来就不准</li>
     *   <li>没有按 User-Agent 去重 —— 同款手机同款浏览器分不开</li>
     *   <li>没有用 localStorage 记 —— 清一下缓存就重置，还能手动改</li>
     * </ul>
     * 结论就是：<b>连点十次就是 +10。</b>
     *
     * <p>要改成"一人一赞"的话，加一个 {@code article:liked:{id}} 的 Redis
     * 集合、用 {@code SADD} 判断是否已点过即可，现有计数不用动。
     *
     * <p><b>为什么用 POST 而不是 GET</b>：这个接口<b>会改数据</b>。
     * 用 GET 的话浏览器、CDN、甚至聊天软件的链接预览都可能顺手访问一下，
     * 变成"发个链接出去，赞数自己涨了"。
     *
     * @param id 文章 id
     * @return 加完之后的点赞总数
     */
    @PostMapping("/{id}/like")
    public Result<Long> like(@PathVariable Long id) {
        // 先确认这篇文章存在且已发布。
        // 【为什么必须查这一次】不查的话，随便请求一个不存在的 id
        // （比如 /999999/like）也会在 Redis 里创建出一个计数器 ——
        // 时间长了会攒下一堆永远不会被读到的垃圾 key
        articleService.requirePublished(id);

        long likeCount = counterService.incrementLike(id);
        return Result.success(likeCount);
    }
}
