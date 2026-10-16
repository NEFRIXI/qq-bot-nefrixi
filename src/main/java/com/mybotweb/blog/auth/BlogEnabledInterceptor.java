package com.mybotweb.blog.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybotweb.blog.config.BlogProperties;
import com.mybotweb.common.result.Result;
import com.mybotweb.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 博客总开关拦截器 —— 拦下所有网站路径，检查 {@code app.blog.enabled}。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么用拦截器，而不是 {@code @ConditionalOnProperty} 让 Bean 干脆不存在</b>
 *
 * <p>条件装配看起来更彻底，但有两个实际问题：
 * <ol>
 *   <li>开关是在<b>启动时</b>读的，改了要重启。而拦截器是每次请求读配置，
 *       改完（如果以后接上热更新）立刻生效</li>
 *   <li>Bean 不存在时，注入它的地方（比如另一个拦截器、或者某个 Service）
 *       会因为找不到依赖而启动失败。开关一关，应用反而起不来了 ——
 *       正好和"博客关掉不能影响机器人"的要求相反</li>
 * </ol>
 *
 * <p>所以这里选了"Bean 一直在，但每次请求看一眼开关"的做法。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>它拦住的范围</b>
 *
 * <p>{@code /client/main}、{@code /client/api/**}、{@code /admin/**}。
 * <b>不拦 {@code /client}</b> 欢迎页 —— 那是一个纯静态页面，
 * 不含任何数据库内容，本机自己打开看看无妨。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlogEnabledInterceptor implements HandlerInterceptor {

    private final BlogProperties blogProperties;

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        if (blogProperties.isEnabled()) {
            return true;
        }

        String path = request.getRequestURI();
        log.debug("博客功能已关闭，拒绝访问：{}", path);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Result.error(ResultCode.BLOG_DISABLED,
                        "本站的博客功能当前未开启（app.blog.enabled = false）"));
        return false;
    }
}
