package com.mybotweb.blog.auth;

import com.mybotweb.blog.entity.AppUser;
import lombok.Data;

import java.io.Serializable;

/**
 * 当前登录的普通用户 —— 放进 ThreadLocal 的就是这个对象。
 *
 * <p>和 {@link AdminPrincipal} 是<b>两套并列的东西</b>，互不继承、互不转换。
 * 唯一共同的来源是它们都由 JWT 载荷还原出来，但代表了完全不同的身份。
 * 分开写是为了让"拿错身份"这件事在类型上就通不过编译。
 *
 * <p>和 {@link AdminPrincipal} 一样，这里刻意<b>不带 passwordHash</b>：
 * 用得着的字段才留下，是减少泄露最省事的办法。
 *
 * @author mybotweb
 */
@Data
public class UserPrincipal implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 id，对应 app_user.id */
    private Long id;

    /** 登录名 */
    private String username;

    /** 显示名。评论、帖子署名用它；为空时前端回退到 username */
    private String nickname;

    /**
     * 本次登录令牌的 jti。
     *
     * <p>登出时靠它删掉 Redis 里那条会话记录。
     * 记日志只能记这个，不能记整串 token —— 后者等于泄露登录状态。
     */
    private String tokenId;

    /**
     * 从数据库实体转成上下文对象。
     *
     * @param user 数据库里查出来的用户
     * @return 只含必要字段的上下文对象
     */
    public static UserPrincipal from(AppUser user) {
        UserPrincipal principal = new UserPrincipal();
        principal.setId(user.getId());
        principal.setUsername(user.getUsername());
        principal.setNickname(user.getNickname());
        return principal;
    }

    /**
     * 显示名：优先用昵称，没设就用登录名。
     *
     * <p>评论和文章署名都调它，免得每个地方各写一次三元表达式、
     * 还写得不一样。
     *
     * @return 能给人看的名字
     */
    public String displayName() {
        return (nickname == null || nickname.isBlank()) ? username : nickname;
    }

    /**
     * 适合打日志的身份标签。
     *
     * @return 形如 {@code 用户(zhangsan)}
     */
    public String toLogTag() {
        return "用户(" + username + ")";
    }
}
