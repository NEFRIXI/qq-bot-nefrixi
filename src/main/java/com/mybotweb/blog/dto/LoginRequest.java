package com.mybotweb.blog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求 —— 前台提交给 {@code POST /admin/api/login} 的 JSON。
 *
 * <p>形如：
 * <pre>{@code
 * { "username": "admin", "password": "xxxx" }
 * }</pre>
 *
 * <p><b>为什么单独建一个类，而不是直接用 Map 接收</b>：
 * 用 Map 的话，前端字段名写错了（比如写成 {@code user}）不会报错，
 * 只会得到一个 null，然后在校验密码时莫名其妙地失败。
 * 用类接收，字段名错了就是错，而且上面这些 {@code @NotBlank}
 * 会在进入业务代码之前就把空值拦下来。
 *
 * @author mybotweb
 */
@Data
public class LoginRequest {

    /**
     * 登录名。
     *
     * <p>{@code max = 50} 对应数据库里 {@code username VARCHAR(50)} ——
     * 这两个数字必须一致。不限制长度的话，一个超长字符串会一路传到
     * SQL 层才被数据库拒绝，报出来的是看不懂的 SQL 异常。
     */
    @NotBlank(message = "请输入用户名")
    @Size(max = 50, message = "用户名太长了")
    private String username;

    /**
     * 密码（明文）。
     *
     * <p>它只在这次请求的内存里存在，不会写进任何日志、也不会存库 ——
     * 数据库里存的是 BCrypt 哈希。
     *
     * <p>{@code max = 72} 不是随便定的：<b>BCrypt 算法本身只取前 72 个字节</b>，
     * 超出的部分会被静默忽略。不限制的话会出现"用户以为自己设了 100 位长密码，
     * 实际只有前 72 位在起作用"这种很坑的情况。
     */
    @NotBlank(message = "请输入密码")
    @Size(max = 72, message = "密码太长了")
    private String password;
}
