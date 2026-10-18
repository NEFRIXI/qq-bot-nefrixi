package com.mybotweb.blog.web;

import com.mybotweb.blog.auth.AppUserAuthService;
import com.mybotweb.blog.auth.UserContextHolder;
import com.mybotweb.blog.config.BlogProperties;
import com.mybotweb.blog.dto.LoginRequest;
import com.mybotweb.blog.dto.UserRegisterRequest;
import com.mybotweb.blog.vo.LoginVo;
import com.mybotweb.blog.vo.UserInfoVo;
import com.mybotweb.common.result.Result;
import com.mybotweb.security.ClientIpResolver;
import com.mybotweb.security.RedisRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 普通用户的注册、登录、登出、当前用户信息。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么注册和登录要按 IP 限流，而登出不用</b>
 *
 * <p>注册和登录是<b>未认证</b>接口 —— 这是唯一能拿来做坏事的入口：
 * 批量注册会污染用户表，暴力试密码会消耗 BCrypt 的计算力（每次 100 毫秒，
 * 打多了就是一次小型的 CPU 拒绝服务）。
 *
 * <p>登出必须<b>已经登录</b>，本来就有令牌，攻击者拿不到，
 * 而且它只删一条 Redis 记录，成本极低。给它加限流只是徒增复杂度。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>两层防护是叠加的，不是重复的</b>
 *
 * <ul>
 *   <li><b>按 IP</b>（本类）：防"一个来源扫很多账号 / 批量注册"</li>
 *   <li><b>按账号</b>（{@code AppUserAuthService} 里的失败锁定）：
 *       防"盯着一个账号反复试密码"</li>
 * </ul>
 * 只有前者，攻击者换 IP 就能继续猜同一个账号；
 * 只有后者，攻击者可以遍历几万个账号各试一次。
 * 两个都要。
 *
 * @author mybotweb
 */
@Slf4j
@RestController
@RequestMapping("/client/api/user")
@RequiredArgsConstructor
public class UserAuthController {

    /** 限流窗口：一分钟 */
    private static final long ONE_MINUTE_MS = 60_000L;

    private final AppUserAuthService authService;

    private final ClientIpResolver ipResolver;

    private final RedisRateLimiter rateLimiter;

    private final BlogProperties blogProperties;

    /**
     * 注册。成功后<b>直接返回令牌</b>，用户不需要再登录一次。
     *
     * @param request     注册信息
     * @param httpRequest 原始请求，用来取 IP
     * @return 令牌和显示名
     */
    @PostMapping("/register")
    public Result<LoginVo> register(@Valid @RequestBody UserRegisterRequest request,
                                    HttpServletRequest httpRequest) {

        rateLimiter.checkOrThrow(
                "rl:user:register:" + clientIp(httpRequest),
                blogProperties.getRateLimit().getRegisterPerMinute(),
                ONE_MINUTE_MS,
                "注册太频繁了，请稍后再试");

        return Result.success(authService.register(request));
    }

    /**
     * 登录。
     *
     * @param request     登录信息
     * @param httpRequest 原始请求，用来取 IP
     * @return 令牌和显示名
     */
    @PostMapping("/login")
    public Result<LoginVo> login(@Valid @RequestBody LoginRequest request,
                                 HttpServletRequest httpRequest) {

        rateLimiter.checkOrThrow(
                "rl:user:login:" + clientIp(httpRequest),
                blogProperties.getRateLimit().getLoginPerMinute(),
                ONE_MINUTE_MS,
                "登录尝试太频繁，请稍后再试");

        return Result.success(authService.login(request));
    }

    /**
     * 登出。
     *
     * <p>没登录时调它也不报错 —— 前端清理本地令牌时可能顺手调一次，
     * 为这种情况回一个 401 没有意义。
     *
     * @return 空结果
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        var principal = UserContextHolder.get();
        if (principal != null) {
            authService.logout(principal);
        }
        return Result.success();
    }

    /**
     * 当前登录用户的信息。
     *
     * <p>前端每次进站都会调它：有令牌且有效就显示"已登录"，
     * 否则当作访客。这也是判断"本地令牌还作不作数"的唯一可靠办法 ——
     * 光看 localStorage 里有没有字符串是判断不出来的。
     *
     * @return 用户信息（不含密码）
     */
    @GetMapping("/me")
    public Result<UserInfoVo> me() {
        return Result.success(authService.currentUser(UserContextHolder.require()));
    }

    /**
     * 取客户端 IP。
     *
     * <p>抽成一行是因为三个接口都要用，而且"信不信任代理"
     * 这个开关只有一个来源（配置），不该在每处各读一次。
     *
     * @param request 请求
     * @return IP 字符串
     */
    private String clientIp(HttpServletRequest request) {
        return ipResolver.resolve(request, blogProperties.isTrustProxy());
    }
}
