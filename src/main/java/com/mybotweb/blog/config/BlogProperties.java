package com.mybotweb.blog.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 博客功能配置 —— 对应 {@code application.yml} 里 {@code app.blog.*} 那一段。
 *
 * <p><b>为什么不把它并进现有的 {@link com.mybotweb.config.AppProperties}？</b>
 * 那个类是 QQ 机器人的配置，已经 618 行。博客是另一件事，
 * 混进去以后想看清"哪些配置属于网站"就得翻半天。
 * 分开还有一个实际好处：将来把网站整个摘掉时，删这个类就行，
 * 不用去动机器人的配置类。
 *
 * <p><b>怎么生效的？</b> 启动类上已有 {@code @ConfigurationPropertiesScan}，
 * 它会自动扫描到这个类并注册成 Bean，所以本类不需要 {@code @Component}。
 *
 * <p>字段都给了默认值。这样即使 yml 里整段漏配，也不会拿到 null 而空指针 ——
 * 尤其是 {@link #enabled}，漏配时应当是"能用"，而不是"启动就报错"。
 *
 * @author mybotweb
 */
@Data
@ConfigurationProperties(prefix = "app.blog")
public class BlogProperties {

    /**
     * 博客总开关。
     *
     * <p>关掉之后：{@code /client/main}、{@code /client/api/**}、{@code /admin/**}
     * 一律返回 503；Spring 的 Flyway 也不会去连数据库
     * （见 {@code application.yml} 里 {@code spring.flyway.enabled} 的写法）。
     *
     * <p>所以这个开关不只是"藏起几个页面"，它让博客功能<b>完全不参与启动</b>——
     * 本机跑机器人时就算 MySQL 没启动，应用照样能起来。
     */
    private boolean enabled = true;

    /** 初始管理员账号 */
    private Admin admin = new Admin();

    /** 登录令牌相关 */
    private Jwt jwt = new Jwt();

    /** 分页相关 */
    private Page page = new Page();

    /**
     * 初始管理员，对应 {@code app.blog.admin.*}
     */
    @Data
    public static class Admin {

        /** 登录名 */
        private String username = "admin";

        /** 显示名，会出现在后台页面右上角 */
        private String nickname = "站长";

        /**
         * 初始密码（明文，只在启动时读一次）。
         *
         * <p>它的用途只有一个：应用启动时如果 {@code admin_user} 表是空的，
         * 就用它建出第一个管理员 —— 存进数据库之前会用 BCrypt 哈希，
         * 库里不存明文。
         *
         * <p>表里已经有账号时这个字段被完全忽略，所以改它不会影响已建好的账号。
         *
         * <p>没有默认值：留空时跳过创建并打警告，而不是用一个人人都知道的
         * 默认密码建出一个后台入口。
         */
        private String password;
    }

    /**
     * JWT 令牌，对应 {@code app.blog.jwt.*}
     */
    @Data
    public static class Jwt {

        /**
         * 签名密钥。
         *
         * <p>留空时 {@link com.mybotweb.blog.auth.JwtTokenProvider} 会随机生成一个
         * 并打警告 —— 后果是应用一重启，已发出的登录全部失效。
         * 开发期无所谓，正式部署要固定填一个。
         *
         * <p>长度至少要 32 个字符：HS256 要求密钥不短于摘要长度（256 位 = 32 字节），
         * 短了 jjwt 会直接抛 WeakKeyException。
         */
        private String secret;

        /** 令牌有效期（分钟）。默认 7 天 */
        private long expireMinutes = 10080;

        /**
         * 是否把签发的令牌记到 Redis、登出时删掉。
         *
         * <p>JWT 本身是无状态的：一旦签出去就收不回来，改密码之后旧令牌照样能用。
         * 加这一层才有真正的"登出"和"立即失效"。
         * 关掉它的话登出只是前端把浏览器里的令牌删掉，服务端管不了。
         */
        private boolean revokeEnabled = true;
    }

    /**
     * 分页，对应 {@code app.blog.page.*}
     */
    @Data
    public static class Page {

        /** 默认每页条数 */
        private int size = 10;

        /**
         * 单页最大条数。
         *
         * <p>必须要有：分页参数是前端传来的，如果有人把 size 改成 100000，
         * 一次查询就能把数据库和带宽打满。超过这个值就按这个值算，
         * 而不是报错 —— 报错会让正常的翻页脚本因为一个小改动就崩掉。
         */
        private int maxSize = 50;
    }
}
