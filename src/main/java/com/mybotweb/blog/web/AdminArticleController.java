package com.mybotweb.blog.web;

import com.mybotweb.blog.dto.ArticleSaveRequest;
import com.mybotweb.blog.dto.ArticleStatusRequest;
import com.mybotweb.blog.service.ArticleService;
import com.mybotweb.blog.vo.ArticleDetailVo;
import com.mybotweb.blog.vo.ArticleListItemVo;
import com.mybotweb.blog.vo.PageVo;
import com.mybotweb.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的文章管理接口。
 *
 * <p><b>路径前缀 {@code /admin/api/**} 意味着这里的每一个方法都被
 * {@code AdminAuthInterceptor} 保护着</b> —— 没带有效令牌根本进不到方法体。
 * 所以方法里不需要再写任何"检查有没有登录"的代码，
 * 需要当前管理员身份时直接用 {@code AdminContextHolder.require()}。
 *
 * <p>这也是把路径设计成 {@code /admin/api/...} 的实际价值：
 * <b>权限可以用一条路径规则表达</b>，而不是在每个方法里各写一遍。
 * 后者只要有一个方法忘了写，就是一个漏洞。
 *
 * @author mybotweb
 */
@Slf4j
@RestController
@RequestMapping("/admin/api/articles")
@RequiredArgsConstructor
public class AdminArticleController {

    private final ArticleService articleService;

    /**
     * 文章列表 —— 草稿和已发布都能看到。
     *
     * <p>示例：{@code GET /admin/api/articles?status=0&page=1&size=10}
     *
     * @param status 只看某个状态：0 = 草稿，1 = 已发布；不传表示全部
     * @param page   第几页
     * @param size   每页几条
     * @return 分页列表（不含正文）
     */
    @GetMapping
    public Result<PageVo<ArticleListItemVo>> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(articleService.listForAdmin(status, page, size));
    }

    /**
     * 取一篇文章用于编辑。
     *
     * <p>和前台详情的区别：<b>这里会带上 Markdown 原文</b>
     * （{@code contentMarkdown}），编辑框里要填的就是它；
     * 草稿也能取到，而前台取草稿会返回 404。
     *
     * @param id 文章 id
     * @return 文章详情，含原文
     */
    @GetMapping("/{id}")
    public Result<ArticleDetailVo> detail(@PathVariable Long id) {
        return Result.success(articleService.getForEdit(id));
    }

    /**
     * 新建文章。
     *
     * <p>示例：
     * <pre>
     * POST /admin/api/articles
     * {
     *   "title": "为什么机器人回了 402",
     *   "slug": "why-bot-said-402",
     *   "summary": "",
     *   "content": "# 起因\n\n那天……",
     *   "status": 1
     * }
     * </pre>
     *
     * @param request 文章内容
     * @return 新文章的 id
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ArticleSaveRequest request) {
        return Result.success("文章已保存", articleService.create(request));
    }

    /**
     * 编辑文章。
     *
     * <p>用 PUT 而不是 POST：PUT 的语义是"用这份内容替换那一篇"，
     * 和这里的行为一致。前端那边对应 {@code fetch(url, {method:'PUT'})}。
     *
     * @param id      文章 id
     * @param request 新内容
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody ArticleSaveRequest request) {
        articleService.update(id, request);
        return Result.success("已保存", null);
    }

    /**
     * 只切换发布状态（草稿 ⇄ 已发布）。
     *
     * <p>给列表页的"发布 / 转草稿"按钮用。用 PATCH 而不是 PUT：
     * 这个请求只改一个字段，语义上就是局部更新。
     *
     * @param id      文章 id
     * @param request 目标状态
     */
    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody ArticleStatusRequest request) {
        articleService.updateStatus(id, request.getStatus());
        return Result.success("状态已更新", null);
    }

    /**
     * 删除文章。
     *
     * <p><b>真删，删了就没了。</b> 前端必须弹一个确认框再调 ——
     * 这个接口没有"回收站"，也没有撤销。
     *
     * @param id 文章 id
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        articleService.delete(id);
        return Result.success("已删除", null);
    }
}
