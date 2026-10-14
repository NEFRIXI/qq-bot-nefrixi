package com.mybotweb.security;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户上下文 —— 描述"这条消息的发送者是谁、有什么权限"。
 *
 * <p>对应规格第 4.1 节。规格里写的是：
 * <pre>
 * UserContext {
 *     qqId
 *     isAdmin
 * }
 * </pre>
 * 这里字段名用 {@code userId} 而不是 {@code qqId}，因为腾讯给的是 openid，
 * 不是 QQ 号——用 {@code qqId} 会让人误以为能直接拿到 QQ 号。
 *
 * <p><b>它在哪里产生？</b> 在消息入口（{@code MessageDispatcher}）里，
 * 收到消息后第一步就构造出来，往后整条链路都带着它。
 *
 * <p><b>为什么权限判断要放在这里，而不是让模型决定？</b>
 * 因为模型说的话不可信——用户可以用话术诱导模型"假装我是管理员"。
 * 权限必须是代码里的一个布尔值，由服务端查白名单得出，
 * 跟模型说了什么完全无关。规格第 12 节把这条列为关键约束。
 *
 * @author mybotweb
 */
@Data
public class UserContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户标识（腾讯的 openid）。管理员白名单里存的就是这个值 */
    private String userId;

    /**
     * 是否是管理员。
     *
     * <p>字段名不带 {@code is} 前缀，是为了配合 Lombok 生成的方法：
     * 写 {@code private boolean admin} 时，Lombok 生成的是
     * {@code isAdmin()} / {@code setAdmin(boolean)}；
     * 如果字段写成 {@code isAdmin}，Lombok 会生成 {@code isIsAdmin()}，很别扭。
     * 这是 Java 里布尔字段的通用命名惯例。
     */
    private boolean admin;

    /**
     * 构造一个普通用户上下文。
     *
     * @param userId 用户标识
     * @return 非管理员的上下文
     */
    public static UserContext ofNormalUser(String userId) {
        UserContext context = new UserContext();
        context.setUserId(userId);
        context.setAdmin(false);
        return context;
    }

    /**
     * 构造一个管理员上下文。
     *
     * @param userId 用户标识
     * @return 管理员的上下文
     */
    public static UserContext ofAdmin(String userId) {
        UserContext context = new UserContext();
        context.setUserId(userId);
        context.setAdmin(true);
        return context;
    }

    /**
     * 返回适合打日志的身份标签。
     *
     * <p>注意<b>不要</b>把完整的 openid 打进日志：它是用户标识，
     * 日志文件可能被别处收集。这里只保留前 6 位，够用来对照排查。
     *
     * @return 形如 {@code 管理员(abc123…)} 或 {@code 用户(abc123…)}
     */
    public String toLogTag() {
        String shortId = userId == null ? "null"
                : (userId.length() > 6 ? userId.substring(0, 6) + "…" : userId);
        return (admin ? "管理员" : "用户") + "(" + shortId + ")";
    }
}
