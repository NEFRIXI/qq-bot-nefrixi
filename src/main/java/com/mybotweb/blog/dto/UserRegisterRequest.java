package com.mybotweb.blog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 普通用户注册请求 —— 前台提交给 {@code POST /client/api/user/register} 的 JSON。
 *
 * <p>形如：
 * <pre>{@code
 * { "username": "zhangsan", "password": "xxxxxx", "nickname": "张三" }
 * }</pre>
 *
 * <p><b>为什么用户名要限制字符集</b>
 * 用户名会出现在 URL（将来可能有 {@code /user/{username}} 主页）、
 * 评论里的 @、以及日志里。放开的话，一个带空格或斜杠的名字
 * 就能把路由和日志搅乱。这里只允许字母、数字、下划线、连字符，
 * 长度 3~50 —— 和数据库的 {@code VARCHAR(50)} 对齐。
 *
 * <p><b>为什么密码最小 6 位</b>
 * 严格说这不是安全底线（真正的底线是 BCrypt 的计算成本），
 * 但一个 1 位数的密码在字典攻击面前等于没有。6 位是"不惹恼用户"
 * 和"至少不是白给"之间的常见折中。
 *
 * @author mybotweb
 */
@Data
public class UserRegisterRequest {

    /**
     * 登录名。
     *
     * <p>正则说明：{@code [A-Za-z0-9_-]{3,50}} ——
     * 只允许英文字母、数字、下划线、连字符。
     * 不允许中文和空格，理由见类注释。
     */
    @NotBlank(message = "请输入用户名")
    @Pattern(regexp = "^[A-Za-z0-9_-]{3,50}$",
            message = "用户名只能用字母、数字、下划线或连字符，长度 3~50")
    private String username;

    /**
     * 密码（明文）。
     *
     * <p>{@code max = 72} 对应 BCrypt 只取前 72 字节的特性 ——
     * 超过的部分会被算法静默忽略，不限制反而会让人以为更长的密码更安全。
     */
    @NotBlank(message = "请输入密码")
    @Size(min = 6, max = 72, message = "密码长度需要 6~72 位")
    private String password;

    /**
     * 显示名，可以不填。
     *
     * <p>不填时后端回退用用户名 —— 前端显示层用
     * {@code UserPrincipal.displayName()} 统一处理，
     * 而不是在这里强行塞一个和用户名一样的值进库。
     * 两者分开存的好处是：用户以后能把昵称改成中文，
     * 而登录名保持不变。
     */
    @Size(max = 50, message = "昵称太长了")
    private String nickname;
}
