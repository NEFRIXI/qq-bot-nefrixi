package com.mybotweb.blog.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 后台页面路由 —— 把干净的路径转发到 {@code static/admin/} 下的静态页面。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>这个类和 {@code ClientPageController} 是同一个套路</b>
 *
 * <p>Spring Boot 会把 {@code static/} 下的文件直接暴露成静态资源，
 * 所以 {@code static/admin/login.html} 用
 * {@code http://地址/admin/login.html} 就能访问 —— 但地址里多一个 {@code .html}。
 * 本类做的事就是让 {@code /admin/login} 也能打开它，用 {@code forward:}
 * 做服务器内部转发，<b>浏览器地址栏保持干净的那个</b>。
 *
 * <p>详细的 forward 和 redirect 区别见 {@code ClientPageController} 的注释。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>这些页面本身不检查登录，是刻意的</b>
 *
 * <p>页面文件里没有任何数据 —— 数据都是页面加载后由 JS 再请求
 * {@code /admin/api/**} 拿的，而那些接口有拦截器守着。
 * 所以陌生人打开这个页面只会看到一个空壳，然后被 JS 弹回登录页。
 *
 * <p>为什么不在这一层也拦：那样就需要在服务端判断"浏览器有没有登录"，
 * 而浏览器发的页面请求是不带 {@code Authorization} 头的
 * （那个头是 JS 自己加的），只能改用 Cookie 传令牌 ——
 * 而 Cookie 会自动带上，反而更容易被 CSRF 利用。现在这套更简单也更安全。
 *
 * @author mybotweb
 */
@Controller
public class AdminPageController {

    /**
     * 登录页。欢迎页右上角那个"登录"按钮跳到这里。
     *
     * @return 转发到登录页
     */
    @GetMapping("/admin/login")
    public String loginPage() {
        return "forward:/admin/login.html";
    }

    /**
     * 后台首页 —— 概览和各个功能的入口。
     *
     * @return 转发到后台首页
     */
    @GetMapping("/admin")
    public String homePage() {
        return "forward:/admin/index.html";
    }

    /**
     * 文章列表（含草稿）。
     *
     * <p><b>为什么路径是 {@code /admin/work/articles} 而不是 {@code /admin/articles}</b>：
     * 用户要求"所有管理员操作都挂在 {@code /admin/work} 下面，路径要一眼读懂"。
     * 中间这层 {@code work} 把"管理操作"和"后台自己的东西"（比如登录页）
     * 分开了，以后加"文件管理""系统设置"都是 {@code /admin/work/xxx}，
     * 看路径就知道这属于管理功能。
     *
     * @return 转发到文章列表页
     */
    @GetMapping("/admin/work/articles")
    public String articleListPage() {
        return "forward:/admin/articles.html";
    }

    /**
     * 新建文章。
     *
     * <p>它和下面的编辑页共用同一个 HTML —— 页面里靠地址栏判断
     * "是新建还是编辑"（有 id 就是编辑），表单结构完全一样，
     * 没必要写两个几乎是复制的页面。
     *
     * @return 转发到文章编辑页
     */
    @GetMapping("/admin/work/articles/new")
    public String articleNewPage() {
        return "forward:/admin/article-edit.html";
    }

    /**
     * 编辑文章。
     *
     * <p><b>{@code {id}} 这个路径变量在这里是被"忽略"的</b> ——
     * 方法没有声明这个参数，因为它对服务端没用：
     * 页面是同一份静态文件，真正需要 id 的是页面里的 JS，
     * 它自己会从地址栏里把数字解析出来。
     *
     * <p>这样写的好处是地址长得对（{@code /admin/work/articles/12/edit}），
     * 刷新页面不会丢 id，收藏起来的地址也还能用。
     *
     * @return 转发到文章编辑页
     */
    @GetMapping("/admin/work/articles/{id}/edit")
    public String articleEditPage(@PathVariable String id) {
        // 参数没用上，但方法签名里保留它，是为了让读代码的人
        // 一眼看出"这个地址里有个 id"，而不是以为路由配错了
        return "forward:/admin/article-edit.html";
    }

    /**
     * 服务器状态页：JVM 内存、机器内存、CPU、磁盘、MySQL/Redis 通不通。
     *
     * @return 转发到状态页
     */
    @GetMapping("/admin/work/system")
    public String systemPage() {
        return "forward:/admin/system.html";
    }

    /**
     * 访问统计页：总浏览、总点赞、两个排行榜。
     *
     * @return 转发到统计页
     */
    @GetMapping("/admin/work/stats")
    public String statsPage() {
        return "forward:/admin/stats.html";
    }

    /**
     * 用户管理页：前台注册的普通用户列表，可封禁 / 解封。
     *
     * <p>路径前缀 {@code /admin/work} 是项目约定（见类注释）：
     * 所有"管理操作"都挂在这一层下面，和登录页这类"后台自己的东西"分开。
     *
     * @return 转发到用户管理页
     */
    @GetMapping("/admin/work/users")
    public String usersPage() {
        return "forward:/admin/users.html";
    }
}
