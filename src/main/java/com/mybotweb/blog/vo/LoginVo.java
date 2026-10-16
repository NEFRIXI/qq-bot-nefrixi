package com.mybotweb.blog.vo;

import lombok.Data;

/**
 * 登录成功后返回给前台的内容。
 *
 * <p>形如：
 * <pre>{@code
 * {
 *   "code": 0,
 *   "message": "成功",
 *   "data": {
 *     "token": "eyJhbGciOiJIUzI1NiJ9....",
 *     "expireSeconds": 604800,
 *     "username": "admin",
 *     "nickname": "站长"
 *   }
 * }
 * }</pre>
 *
 * <p><b>这里没有密码相关字段，是刻意的</b>：
 * 登录接口的返回值是最容易被前端打进控制台、被浏览器开发者工具看到的地方，
 * 少放一个字段就少一次泄露机会。哈希也不返回 —— 它虽然不可逆，
 * 但泄露出去等于给暴力破解提供了离线尝试的材料。
 *
 * <p><b>前端拿这几个字段做什么</b>：
 * <ul>
 *   <li>{@code token} —— 存进 localStorage，之后每个后台请求带在
 *       {@code Authorization} 头上</li>
 *   <li>{@code expireSeconds} —— 用来提前提醒"登录快过期了"，
 *       也可以用来在本地判断令牌是否过期，省一次必然失败的请求</li>
 *   <li>{@code nickname} —— 显示在后台右上角</li>
 * </ul>
 *
 * @author mybotweb
 */
@Data
public class LoginVo {

    /** 登录令牌 */
    private String token;

    /**
     * 令牌有效期，单位秒。
     *
     * <p>用秒而不是"过期时间点"，是因为前端算剩余时间更方便：
     * 拿到响应的时刻加上这个秒数就是过期时刻。
     * 如果返回时间点，前端还得处理和服务器之间的时钟差。
     */
    private long expireSeconds;

    /** 登录名 */
    private String username;

    /** 显示名 */
    private String nickname;
}
