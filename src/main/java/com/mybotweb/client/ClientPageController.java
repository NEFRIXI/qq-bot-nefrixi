package com.mybotweb.client;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 客户端页面路由 —— 把干净的路径映射到静态页面上。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>这个类是干什么的？为什么不能直接把 HTML 放 static 目录就完事？</b>
 *
 * <p>Spring Boot 默认会把 {@code src/main/resources/static/} 下的文件
 * 直接暴露成静态资源。所以 {@code static/client/a.html} 用
 * {@code http://域名/client/a.html} 就能访问——<b>但路径里会多个 .html</b>。
 *
 * <p>本类做的事就是把 {@code /client/a} 这样的干净路径<b>转发</b>到那个
 * 真实的 HTML 文件上。用 {@code forward:} 前缀而不是 {@code redirect:}，
 * 区别是：
 * <ul>
 *   <li>{@code forward:} —— 服务器内部转发，<b>浏览器地址栏保持 {@code /client/a} 不变</b> ✅</li>
 *   <li>{@code redirect:} —— 让浏览器重新请求，地址栏会变成 {@code /client/a.html} ❌</li>
 * </ul>
 *
 * <p>为什么要费这个事：地址栏干净，而且以后如果换成模板引擎
 * （Thymeleaf 之类）或者前端框架，<b>外面看到的 URL 不用变</b>——
 * 别人收藏的链接、你写进文档的地址都不会失效。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>当前阶段只做两个页面，都是空白占位</b>
 *
 * <table border="1">
 *   <tr><th>路径</th><th>实际文件</th><th>说明</th></tr>
 *   <tr>
 *     <td>{@code /client}</td>
 *     <td>{@code static/client/index.html}</td>
 *     <td>欢迎页面占位</td>
 *   </tr>
 *   <tr>
 *     <td>{@code /client/a}</td>
 *     <td>{@code static/client/a.html}</td>
 *     <td>主页面 A 占位（将来放博客信息展示）</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么用静态 HTML 而不是模板引擎？</b>
 * 两个原因：一是页面现在是空白的，模板引擎（Thymeleaf/JSP）能提供的能力
 * （变量、循环、条件）一点都用不上；二是少一个依赖，部署到 Linux 时
 * 少一处可能出问题的地方。等页面真需要动态渲染了再加也不迟——
 * 那时改的只是本类的返回值（{@code forward:...} 换成模板名）。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么用 @Controller 而不是 @RestController？</b>
 *
 * <p>这两个注解的区别只在返回值的含义上：
 * <ul>
 *   <li>{@code @RestController} —— 返回值<b>直接写进响应体</b>
 *       （所以我们那些 {@code /client/articles} 接口用它，返回 JSON）</li>
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
     * 主页面 A。
     *
     * <p>现在是个空白占位。将来这个页面会从
     * {@code /client/articles} 读数据渲染出来——但那是数据库配好之后的事。
     *
     * @return 转发到静态 A 页面
     */
    @GetMapping("/client/a")
    public String pageA() {
        return "forward:/client/a.html";
    }
}
