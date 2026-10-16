package com.mybotweb.blog.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章详情。前台详情页和后台编辑页都用它。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>两个正文字段，各用一半</b>
 *
 * <table border="1">
 *   <tr><th>场景</th><th>contentHtml</th><th>contentMarkdown</th></tr>
 *   <tr>
 *     <td>前台详情（访客看的）</td><td>有</td><td>{@code null}</td>
 *   </tr>
 *   <tr>
 *     <td>后台编辑（你自己改）</td><td>有（预览用）</td><td>有（编辑器里要填原文）</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么前台不返回 Markdown 原文</b>：
 * 那是"内容的另一种写法"，前台显示用不到，白白多传一份正文 ——
 * 而带宽只有 3 Mbps，正文重复一遍就是几百 KB。
 * 更要紧的是：原文里可能带着作者写的注释、待办、删了一半的段落，
 * 而渲染后的 HTML 只呈现成品。少给一份就少一份信息暴露。
 *
 * @author mybotweb
 */
@Data
public class ArticleDetailVo {

    /** 文章 id */
    private Long id;

    /** URL 短名 */
    private String slug;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /**
     * 渲染后的 HTML 片段。
     *
     * <p>由 {@code MarkdownRenderer} 把 content 从 Markdown 转过来，
     * 已经做过 HTML 转义和危险链接过滤，可以直接插进页面。
     *
     * <p><b>前端插入时用 {@code innerHTML}</b>（它本来就是 HTML），
     * 但同一页面里插入<b>其它</b>字段（标题、摘要）时
     * 一定要用 {@code textContent} 或者公共的 {@code escapeHtml()} ——
     * 那些字段是纯文本，直接当 HTML 插进去就是 XSS 入口。
     */
    private String contentHtml;

    /**
     * Markdown 原文。只有后台编辑接口会填，前台恒为 {@code null}。
     */
    private String contentMarkdown;

    /** 状态：0 = 草稿，1 = 已发布 */
    private Integer status;

    /** 发布时间，草稿为 {@code null} */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedAt;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /** 最后修改时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    /** 点赞数（来自 Redis） */
    private long likeCount;

    /** 浏览量（来自 Redis） */
    private long viewCount;
}
