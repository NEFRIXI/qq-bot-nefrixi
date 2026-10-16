package com.mybotweb.blog.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybotweb.common.result.Result;
import com.mybotweb.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 后台接口的登录校验 —— 拦住所有没带有效令牌的 {@code /admin/api/**} 请求。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>⚠ 这个类的 {@code afterCompletion} 是安全相关的，不是收尾工作</b>
 *
 * <p>{@code preHandle} 里往 {@link AdminContextHolder} 设了当前管理员，
 * {@code afterCompletion} 里必须清掉。原因见那个类的注释：
 * Tomcat 复用线程，不清就会出现"未登录的人读到上一个管理员的身份"。
 *
 * <p>为什么放在 {@code afterCompletion} 而不是在 Controller 里清：
 * 因为 Controller 抛异常时不会走到"方法最后一行"，
 * 但 {@code afterCompletion} 一定会执行 —— 它的地位等同于 try/finally 的 finally。
 * 把这个动作放在框架回调里，就不会有人"忘了写"。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么只拦 {@code /admin/api/**}，不拦后台的 HTML 页面</b>
 *
 * <p>HTML 页面本身不含任何数据（数据都是页面加载后由 JS 再请求接口拿的），
 * 所以页面被别人打开无所谓 —— 它打开后拿不到数据，JS 会把浏览器弹回登录页。
 * 真正的门在接口这一层，这里守住了，数据就出不去。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    /** 令牌放在这个请求头上 */
    private static final String HEADER_NAME = "Authorization";

    /** 标准的令牌前缀，格式：{@code Authorization: Bearer eyJhbGci...} */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    private final TokenRevokeStore tokenRevokeStore;

    private final ObjectMapper objectMapper;

    /**
     * 进来之前先验令牌。
     *
     * @return 验过了返回 {@code true}（继续走 Controller），否则返回 {@code false}
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        String token = resolveToken(request);
        if (token == null) {
            reject(response, "请先登录");
            return false;
        }

        try {
            Claims claims = jwtTokenProvider.parse(token);

            // 签名和有效期都过了，再问一句"这条被登出过吗"
            if (!tokenRevokeStore.isActive(claims.getId())) {
                reject(response, "登录已失效，请重新登录");
                return false;
            }

            // 校验全过，把身份放进本线程 —— 之后 Service 层用
            // AdminContextHolder.require() 就能拿到
            AdminContextHolder.set(jwtTokenProvider.toPrincipal(claims));
            return true;

        } catch (JwtException | IllegalArgumentException e) {
            // 过期、签名不对、格式错误都归到这里。
            //
            // 【不要把 e.getMessage() 返回给前端】它会说清是"过期了"
            // 还是"签名不对"—— 对攻击者是免费的情报，对正常用户又没用。
            // 详细原因记在 DEBUG 日志里，排查时能看到。
            log.debug("后台令牌校验失败：{}", e.getMessage());
            reject(response, "登录已失效，请重新登录");
            return false;
        }
    }

    /**
     * 请求处理完（无论成功还是抛异常）清理线程上下文。
     *
     * <p>这三行是本类存在的第二个理由，删掉它就会出越权事故。
     *
     * @param ex Controller 抛出的异常，正常结束时为 {@code null}
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AdminContextHolder.clear();
    }

    /**
     * 从请求里取出令牌。
     *
     * <p>标准写法是 {@code Authorization: Bearer <令牌>}，
     * 但也接受不带 {@code Bearer} 前缀的裸令牌 ——
     * 用 curl 手测时少打几个字，代价只是多一个分支。
     *
     * @param request 请求
     * @return 令牌字符串，没有则返回 {@code null}
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_NAME);
        if (header == null || header.isBlank()) {
            return null;
        }

        String token = header.trim();
        if (token.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            // regionMatches 的第三个参数是"忽略大小写"，
            // 所以 Bearer / bearer / BEARER 都认
            token = token.substring(BEARER_PREFIX.length()).trim();
        }
        return token.isBlank() ? null : token;
    }

    /**
     * 拒绝这次请求，并写回统一格式的错误响应。
     *
     * <p><b>HTTP 状态码给 200，业务码放在响应体的 {@code code} 里</b> ——
     * 这是沿用项目已有的约定（见 {@code ResultCode} 的类注释），
     * 前端只需要判断 {@code code} 一个地方。
     *
     * @param response 响应
     * @param message  给用户看的一句话
     */
    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Result.error(ResultCode.UNAUTHORIZED, message));
    }
}
