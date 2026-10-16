package com.mybotweb.blog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 博客功能的 Bean 定义。
 *
 * <p>现在只有一个：密码编码器。放在这里而不是直接在 Service 里 {@code new}，
 * 有两个实际好处：
 * <ul>
 *   <li>要用它的地方（登录校验、建初始管理员）注入同一个实例，
 *       不会出现两处配的参数不一样</li>
 *   <li>以后想换成 Argon2 之类，只改这一个方法，
 *       用它的地方一行都不用动</li>
 * </ul>
 *
 * <p><b>为什么项目里没引 {@code spring-boot-starter-security}，却能用
 * {@code BCryptPasswordEncoder}</b>：这个类在 {@code spring-security-crypto}
 * 这个独立的包里 —— 它只做加密，不注册任何过滤器、不拦截任何请求。
 * 引整个 security starter 的话，机器人那些接口会全部被要求登录。
 *
 * @author mybotweb
 */
@Configuration
public class BlogBeanConfig {

    /**
     * 密码编码器（BCrypt）。
     *
     * <p><b>BCrypt 是什么</b>：一种专门用来存密码的哈希算法。
     * 和 MD5/SHA 的关键区别是它<b>故意很慢</b>（算一次约 100 毫秒）——
     * 对正常登录只慢 0.1 秒，感觉不出来；
     * 但对拿着一张字典表逐个试密码的人来说，速度被压到了没法忍的程度。
     *
     * <p>另一个区别是每个密码都会掺一段随机"盐"：
     * 同一个密码两次哈希出来的结果完全不同，
     * 所以没法靠"两个人哈希值一样"推断他们密码相同，
     * 也没法预先算好一张彩虹表来查。
     *
     * <p>代价是校验不能直接比较字符串，必须用
     * {@code encoder.matches(明文, 哈希)} —— 它会从哈希里把盐取出来再算一遍。
     *
     * @return 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
