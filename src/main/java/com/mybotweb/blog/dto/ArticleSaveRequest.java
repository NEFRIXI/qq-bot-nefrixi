package com.mybotweb.blog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 编辑文章的请求体。
 *
 * <p>新建和编辑共用一个类：两种情况要填的东西完全一样
 * （编辑时 id 在 URL 里，不在请求体里）。
 * 分成两个类只会让前端多写一份表单处理逻辑。
 *
 * @author mybotweb
 */
@Data
public class ArticleSaveRequest {

    /**
     * 标题。
     *
     * <p>{@code max = 200} 对应数据库的 {@code title VARCHAR(200)}。
     * 这两个数字必须一致 —— 不限制的话超长标题会一路传到 SQL 层
     * 才被数据库拒绝，报出来的是一个看不懂的 SQL 异常。
     */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最多 200 个字")
    private String title;

    /**
     * URL 短名，如 {@code why-bot-said-402}。
     *
     * <p><b>格式限制很严，是刻意的</b>：它要出现在网址里，
     * 所以只允许小写字母、数字和短横线。
     * 允许中文或大写会带来一堆麻烦：网址复制出来变成一长串
     * {@code %E4%B8%BA%E4%BB%80%E4%B9%88}，而且 Linux 服务器
     * 对大小写敏感，{@code My-Post} 和 {@code my-post} 会变成两个地址。
     *
     * <p>可以留空 —— 留空时 Service 会按时间自动生成一个
     * （形如 {@code post-20261017203510}），保证能建出来。
     * 但自动生成的不好看也不利于记，建议还是自己起一个。
     */
    @Size(max = 120, message = "短名最多 120 个字符")
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
            message = "短名只能用小写字母、数字和短横线，且不能以短横线开头或结尾")
    private String slug;

    /**
     * 摘要，列表页显示。
     *
     * <p>允许留空：Service 会从正文里自动截一段。
     * 正则里的 {@code (?s)} 让 {@code .} 能匹配换行，
     * 因为正文是多行的。
     */
    @Size(max = 300, message = "摘要最多 300 个字")
    private String summary;

    /**
     * 正文，Markdown 原文。
     *
     * <p><b>这里不限制最小长度之外的格式</b> —— 正文里可以有任何字符，
     * 包括 {@code <script>}。安全性不靠校验拦，靠渲染时转义
     * （见 {@code MarkdownRenderer}）：那样作者想写代码示例时
     * 照样写得出来，而写进去的标签不会被执行。
     */
    @NotBlank(message = "正文不能为空")
    private String content;

    /**
     * 状态：0 = 草稿，1 = 已发布。
     *
     * <p>必填。Service 里还会再校验一次取值范围 ——
     * 注解只能保证"不为 null"，管不住"传了 7 进来"。
     */
    @NotNull(message = "请选择文章状态")
    private Integer status;
}
