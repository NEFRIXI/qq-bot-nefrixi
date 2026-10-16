package com.mybotweb.blog.config;

import com.mybotweb.blog.auth.AdminAuthInterceptor;
import com.mybotweb.blog.auth.BlogEnabledInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册博客的两个拦截器，并规定它们各管哪一段路径。
 *
 * <p>把"哪些路径要做什么检查"集中在这一个方法里，而不是散在各个 Controller 上 ——
 * 想知道"访问某个地址会经过哪些检查"时，看这里就够了。
 *
 * @author mybotweb
 */
@Configuration
@RequiredArgsConstructor
public class BlogWebConfig implements WebMvcConfigurer {

    private final BlogEnabledInterceptor blogEnabledInterceptor;

    private final AdminAuthInterceptor adminAuthInterceptor;

    /**
     * 拦截器的注册顺序很重要，这里体现为 {@code order}。
     *
     * <p>先查总开关、再查登录：<b>博客关掉时应该报"功能未开启"</b>，
     * 而不是报"请先登录"。反过来的话，本机（博客关闭）访问后台，
     * 看到的是"请先登录"，会让人以为只是没登录而已。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // ---- 第一道：博客总开关 ----
        //
        // 【为什么只拦 API，不拦页面】
        // 页面（/client/main、/admin 这些）是纯静态的 HTML 空壳，
        // 里面一个字节的数据都没有 —— 数据全是页面打开后由 JS
        // 再去请求 /client/api/**、/admin/api/** 拿的。
        //
        // 所以拦住 API 就足以让数据出不去。反过来，如果连页面一起拦，
        // 浏览器打开 /client/main 看到的会是一坨 JSON：
        //     {"code":503,"message":"本站的博客功能当前未开启…"}
        // 放行页面的话，页面能正常打开（有导航、有样式），
        // JS 请求拿到 503 后在内容区显示同样这句话 —— 好看得多，也看得懂。
        //
        // 欢迎页 /client 从来就不在拦截范围里：它是纯静态的，
        // 博客关掉也照样能看。
        registry.addInterceptor(blogEnabledInterceptor)
                .addPathPatterns(
                        "/client/api/**",
                        "/admin/api/**")
                .order(0);

        // ---- 第二道：管理员登录校验 ----
        // 只拦后台的 JSON 接口。
        //
        // 【为什么不用 /** 连页面一起拦】后台页面不含数据，
        // 数据全靠 JS 再请求接口拿。守住接口这一层，数据就出不去；
        // 页面被陌生人打开只会看到一个空壳，然后被 JS 弹回登录页。
        //
        // 【为什么排除 /admin/api/login】登录接口本身就是用来"从没有令牌
        // 变成有令牌"的，要求它带令牌就成了死循环 —— 永远登不进去。
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/api/**")
                .excludePathPatterns("/admin/api/login")
                .order(1);
    }
}
