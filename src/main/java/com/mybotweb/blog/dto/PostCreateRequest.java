package com.mybotweb.blog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 普通用户发帖的请求 —— {@code POST /client/api/posts}。
 *
 * <p>形如：
 * <pre>{@code
 * { "title": "我的第一篇", "content": "# 你好\n正文…", "summary": "可选" }
 * }</pre>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么用户版比管理员的 {@link ArticleSaveRequest} 少了两个字段</b>
 *
 * <p>少了 {@code slug} 和 {@code status}：
 * <ul>
 *   <li><b>slug</b> —— 让用户自己想 URL 短名不现实，还会撞唯一索引。
 *       一律由后端按时间自动生成（{@code post-20261018013000}），
 *       反正普通用户也不需要好看的自定义地址</li>
 *   <li><b>status</b> —— 用户发帖就是发布，没有"草稿箱"这个概念。
 *       留着它等于给了前端一个"传 0 就能绕过草稿态"的入口，
 *       徒增要校验的东西</li>
 * </ul>
 *
 * <p>这两个字段在后端是<b>写死</b>的，不是"没传就用默认值"——
 * 用户根本没有途径影响它们。
 *
 * @author mybotweb
 */
@Data
public class PostCreateRequest {

    /** 标题。200 对应数据库 {@code title VARCHAR(200)} */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最多 200 个字")
    private String title;

    /**
     * 正文，Markdown 原文。
     *
     * <p>不限制字符集 —— 作者写代码示例时会用到 {@code <script>} 之类，
     * 安全性靠渲染时转义（{@code MarkdownRenderer}），不靠这里拦。
     */
    @NotBlank(message = "正文不能为空")
    private String content;

    /** 摘要，可选。留空时后端从正文自动截取 */
    @Size(max = 300, message = "摘要最多 300 个字")
    private String summary;
}
