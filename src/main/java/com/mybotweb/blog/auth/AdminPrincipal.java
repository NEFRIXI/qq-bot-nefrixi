package com.mybotweb.blog.auth;

import com.mybotweb.blog.entity.AdminUser;
import lombok.Data;

import java.io.Serializable;

/**
 * 当前登录的管理员 —— 放进 ThreadLocal 的就是这个对象。
 *
 * <p><b>为什么不用 {@link AdminUser} 直接放进去？</b>
 * 因为 {@code AdminUser} 里有 {@code passwordHash}。
 * 放进 ThreadLocal 就意味着它会在整个请求处理期间驻留在内存里，
 * 而且在日志、异常信息、未来的某个 toString() 里都更容易被带出去。
 * 这里只保留"这次请求需要知道的三件事"：id、登录名、显示名。
 * <b>用得着的字段才留下</b>，是减少这类泄露最省事的办法。
 *
 * @author mybotweb
 */
@Data
public class AdminPrincipal implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 管理员 id，对应 admin_user.id */
    private Long id;

    /** 登录名 */
    private String username;

    /** 显示名，后台页面右上角显示的就是它 */
    private String nickname;

    /**
     * 本次登录令牌的 id（JWT 里的 {@code jti}）。
     *
     * <p>登出时要用它把 Redis 里对应的记录删掉。
     * 它和 token 本身不同：token 是一整串，jti 只是里面的一个随机标识，
     * 把 jti 记进日志是安全的，记整串 token 就等于泄露了登录状态。
     */
    private String tokenId;

    /**
     * 从数据库实体转成上下文对象。
     *
     * <p>刻意不带 {@code passwordHash} —— 这个是编译期就保证的：
     * 本类里根本没有这个字段，想带也带不过来。
     *
     * @param user 数据库里查出来的管理员
     * @return 只含必要字段的上下文对象
     */
    public static AdminPrincipal from(AdminUser user) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setId(user.getId());
        principal.setUsername(user.getUsername());
        principal.setNickname(user.getNickname());
        return principal;
    }

    /**
     * 返回适合打日志的身份标签。
     *
     * <p>只打登录名，不打 id、更不打令牌 ——
     * 日志文件可能被别处收集，能少写就少写。
     *
     * @return 形如 {@code 管理员(admin)}
     */
    public String toLogTag() {
        return "管理员(" + username + ")";
    }
}
