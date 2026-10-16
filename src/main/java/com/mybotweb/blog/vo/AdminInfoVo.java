package com.mybotweb.blog.vo;

import com.mybotweb.blog.auth.AdminPrincipal;
import lombok.Data;

/**
 * 当前登录管理员的公开信息 —— 给前端显示"现在是谁登录着"。
 *
 * <p>它和 {@link LoginVo} 的区别：这个<b>不带令牌</b>。
 * 用途是页面刷新后问一句"我上次那个令牌还有效吗"，
 * 顺便拿到昵称填到右上角。
 *
 * <p>为什么不直接把 {@link AdminPrincipal} 返回去：那个类里有
 * {@code tokenId}。它虽然不能单独用来伪造令牌，但它是服务端
 * 定位会话的钥匙 —— 前端已经拿着完整的令牌了，没必要再给一份钥匙。
 * <b>能少给一个字段就少给一个</b>，这类东西泄露从来不是因为一次给太多。
 *
 * @author mybotweb
 */
@Data
public class AdminInfoVo {

    /** 管理员 id */
    private Long id;

    /** 登录名 */
    private String username;

    /** 显示名 */
    private String nickname;

    /**
     * 从登录上下文转换。
     *
     * @param principal 当前登录管理员
     * @return 只含公开字段的对象
     */
    public static AdminInfoVo from(AdminPrincipal principal) {
        AdminInfoVo vo = new AdminInfoVo();
        vo.setId(principal.getId());
        vo.setUsername(principal.getUsername());
        vo.setNickname(principal.getNickname());
        return vo;
    }
}
