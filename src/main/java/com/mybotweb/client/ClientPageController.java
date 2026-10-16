package com.mybotweb.client;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 客户端页面路由 —— 把干净的路径映射到静态页面上。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>这个类是干什么的？为什么不能直接把 HTML 放 static 目录就完事？</b>
 *
 * <p>Spring Boot 默认会把 {@code src/main/resources/static/} 下的文件
 * 直接暴露成静态资源。所以 {@code static/client/index.html} 用
 * {@code http://域名/client/index.html} 就能访问——<b>但路径里会多个 .html</b>。
 *
 * <p>本类做的事就是把 {@code /client} 这样的干净路径<b>转发</b>到那个
 * 真实的 HTML 文件上。用 {@code forward:} 前缀而不是 {@code redirect:}，
 * 区别是：
 * <ul>
 *   <li>{@code forward:} —— 服务器内部转发，<b>浏览器地址栏保持 {@code /client} 不变</b> ✅</li>
 *   <li>{@code redirect:} —— 让浏览器重新请求，地址栏会变成 {@code /client/index.html} ❌</li>
 * </ul>
 *
 * <p>为什么要费这个事：地址栏干净，而且以后如果换成模板引擎
 * （Thymeleaf 之类）或者前端框架，<b>外面看到的 URL 不用变</b>——
 * 别人收藏的链接、你写进文档的地址都不会失效。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>当前的页面</b>
 *
 * <table border="1">
 *   <tr><th>路径</th><th>实际文件</th><th>说明</th></tr>
 *   <tr>
 *     <td>{@code /client}</td>
 *     <td>{@code static/client/index.html}</td>
 *     <td>欢迎页：唯一对外的公开入口</td>
 *   </tr>
 *   <tr>
 *     <td>{@code /client/main}</td>
 *     <td>{@code static/client/main.html}</td>
 *     <td>博客文章列表</td>
 *   </tr>
 *   <tr>
 *     <td>{@code /client/main/{slug}}</td>
 *     <td>{@code static/client/article.html}</td>
 *     <td>文章详情（{@code slug} 由页面里的 JS 自己解析）</td>
 *   </tr>
 * </table>
 *
 * <p>页面里的数据全部由 JS 调 {@code /client/api/**} 拿，所以这些 HTML
 * 是纯静态的，不需要模板引擎。等哪天真需要服务端渲染 HTML 再说 ——
 * 那时改的只是本类的返回值（{@code forward:...} 换成模板名）。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么用 @Controller 而不是 @RestController？</b>
 *
 * <p>这两个注解的区别只在返回值的含义上：
 * <ul>
 *   <li>{@code @RestController} —— 返回值<b>直接写进响应体</b>
 *       （所以 {@code /client/api/**} 那些接口用它，返回 JSON）</li>
 *   <li>{@code @Controller} —— 返回值被当成<b>视图名</b>或转发指令解析
 *       （本类用它，返回 {@code forward:/xxx.html}）</li>
 * </ul>
 * 用错了的表现是：浏览器里直接显示 "forward:/client/index.html" 这串文字。
 *
 * @author mybotweb
 */
@Controller
public class ClientPageController {

    /**
     * 欢迎页面。
     *
     * <p><b>这是唯一对外的公开入口</b>，从这个页面出发有两条路：
     * <pre>
     * 点【进入】  → /client/main    普通访客，不登录，直接看文章
     * 点【登录】  → /admin/login    只有你自己用，进后台
     * </pre>
     *
     * <p>注意路径写的是 {@code /client} 而不是 {@code /client/}。
     * Spring 默认会把 {@code /client/} 也匹配到 {@code /client} 上
     * （尾部斜杠匹配），所以两种访问方式都能通，不用重复写一个方法。
     *
     * @return 转发到静态首页
     */
    @GetMapping("/client")
    public String welcomePage() {
        return "forward:/client/index.html";
    }

    /**
     * 博客文章列表。
     *
     * <p><b>这个地址可以被直接访问，这是正常的。</b>
     * 浏览器不提供"禁止用户手输 URL"的能力 —— 任何页面只要知道地址就能打开。
     * 能做到的只是"让 index 成为入口，其他页面从它点进去"。
     * 何况这里的内容本来就是公开的，直接访问不影响任何安全性。
     *
     * @return 转发到文章列表页
     */
    @GetMapping("/client/main")
    public String mainPage() {
        return "forward:/client/main.html";
    }

    /**
     * 文章详情。
     *
     * <p>地址形如 {@code /client/main/why-bot-said-402}，其中那一段
     * 是文章的 slug（英文短名），不是数字 id —— 数字 id 看不出内容，
     * 而且换数据库时会变。
     *
     * <p>和后台编辑页一样，{@code slug} 在服务端是<b>用不到</b>的：
     * 页面是同一份静态文件，真正拿它去请求数据的是页面里的 JS。
     * 但它必须出现在路由里，否则这个地址会 404。
     *
     * @param slug 文章短名（服务端不使用，仅供路由匹配）
     * @return 转发到文章详情页
     */
    @GetMapping("/client/main/{slug}")
    public String articlePage(@PathVariable String slug) {
        return "forward:/client/article.html";
    }
}
