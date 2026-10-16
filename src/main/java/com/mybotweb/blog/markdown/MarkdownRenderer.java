package com.mybotweb.blog.markdown;

import lombok.extern.slf4j.Slf4j;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/**
 * Markdown 渲染器 —— 把正文从 Markdown 转成 HTML，在服务端做。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么在服务端转，而不是把 Markdown 丢给浏览器用 JS 转</b>
 *
 * <p>三个理由，按重要性排：
 * <ol>
 *   <li><b>XSS 防线在服务端</b>。如果让浏览器渲染，就必须在浏览器里
 *       装一个 Markdown 库并信任它转过的东西；渲染放在服务端，
 *       出去的 HTML 长什么样我们说了算（见下面两个开关）</li>
 *   <li><b>结果可以缓存</b>。同一篇文章的渲染结果对所有访客都一样，
 *       没必要让每个人的浏览器各算一遍</li>
 *   <li><b>以后能复用</b>。第二步让 QQ 机器人读文章时，
 *       如果它需要纯文本或 HTML，同一份渲染逻辑直接拿来用</li>
 * </ol>
 *
 * <p>数据库里存的始终是 Markdown 原文 —— 这一点比上面三条都重要：
 * 源格式只有一份，渲染器可以随便换，历史文章不用动。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>⚠ 两个安全开关，别关掉</b>
 *
 * <p>{@code escapeHtml(true)} —— 把正文里的原始 HTML 当成普通文字显示。
 * 也就是说别人在文章里写 {@code <script>stealCookies()</script>}，
 * 页面上看到的是这行字本身，而不是执行它。
 * <b>但要注意这个开关只保护"文章正文"</b>：它让作者不能写 HTML，
 * 也就没人能借文章注入脚本。
 *
 * <p>{@code sanitizeUrls(true)} —— 处理链接和图片地址。
 * 只靠上面那个开关是不够的，因为 Markdown 里可以写
 * {@code [点我](javascript:alert(1))}，这串会变成
 * {@code <a href="javascript:alert(1)">} —— 里面没有 HTML 标签，
 * 转义管不到它，但点一下照样执行脚本。
 * 这个开关会把危险的协议（{@code javascript:}、{@code data:} 等）挡掉。
 *
 * <p>两个开关一个管"标签"，一个管"地址"，缺一个都留着口子。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class MarkdownRenderer {

    /**
     * Markdown 解析器：把文本变成语法树。
     *
     * <p>它是<b>线程安全</b>的，所以可以建一个放在字段里反复用，
     * 不用每次请求 new 一个。（CommonMark 官方文档明确说明了这一点。）
     */
    private final Parser parser = Parser.builder().build();

    /**
     * 把语法树渲染成 HTML 的渲染器。
     *
     * <p>同样是线程安全的，同样只建一次。
     */
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    /**
     * 渲染 Markdown 为 HTML 片段。
     *
     * <p>返回的是<b>片段</b>（比如 {@code <h1>标题</h1><p>正文</p>}），
     * 不带 {@code <html>}、{@code <body>} 这类外壳 ——
     * 外壳由页面自己提供，渲染结果只是塞进某个 {@code <div>} 里的内容。
     *
     * @param markdown Markdown 原文，可为 {@code null}
     * @return HTML 片段；入参为空时返回空串（而不是 null，
     *         免得前端还要判一次空）
     */
    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        try {
            Node document = parser.parse(markdown);
            return htmlRenderer.render(document);
        } catch (Exception e) {
            // 【为什么不让异常冒出去】渲染失败的原因几乎只有一种：
            // 正文里有渲染器处理不了的畸形语法。让整个详情页报 500 太过了 ——
            // 作者看到的是"页面打不开"，而真正的原因（某段 Markdown 有问题）
            // 完全看不出来。这里降级成"显示纯文本"，并记日志。
            log.warn("Markdown 渲染失败，降级为纯文本显示：{}", e.getMessage());

            // 转义后按原样显示 —— 注意这里也必须转义，
            // 不能直接把原文拼进 HTML，否则这条兜底路径自己就成了 XSS 入口
            return "<pre>" + escapeForHtml(markdown) + "</pre>";
        }
    }

    /**
     * 把文本里的 HTML 特殊字符转义。
     *
     * <p>只用于上面那条兜底路径。正常的渲染不用手动转义 ——
     * 那是 {@code escapeHtml(true)} 的活。
     *
     * @param text 原始文本
     * @return 可以安全放进 HTML 的文本
     */
    private String escapeForHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
