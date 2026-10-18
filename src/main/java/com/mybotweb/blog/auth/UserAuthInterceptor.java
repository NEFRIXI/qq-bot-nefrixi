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
 * 前台接口的用户身份校验。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>它比 {@link AdminAuthInterceptor} 多一个"可选登录"模式</b>
 *
 * <p>后台接口只有一种情况：必须登录，没令牌就 401。
 * 前台不一样 —— 看文章不需要登录，评论和发帖需要。
 * 如果也做成"没令牌就拒"，那访客连文章列表都打不开了。
 *
 * <p>所以这里分两类路径：
 * <ul>
 *   <li><b>公开路径</b>（文章列表、详情、注册、登录）——
 *       带了合法令牌就顺便把身份放进 ThreadLocal（这样页面能显示
 *       "我的点赞状态"之类），没带也照常放行，只是身份为空</li>
 *   <li><b>受限路径</b>（我的信息、发评论、发帖）——
 *       必须带合法用户令牌，否则 401</li>
 * </ul>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么不接受管理员令牌</b>
 *
 * <p>管理员和用户共用签名密钥，靠载荷里的 {@code type} 区分。
 * 这里只认 {@code type = USER}。理由：两套身份的权限边界必须清楚 ——
 * 如果管理员的令牌也能发帖，那"文章作者是普通用户"这个前提就破了，
 * 后面"只能改自己的文章"之类的判断会变得无法推理。
 * 管理员要发文，走后台接口，那边有完整的编辑器。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserAuthInterceptor implements HandlerInterceptor {

    private static final String HEADER_NAME = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    private final TokenRevokeStore tokenRevokeStore;

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        String path = request.getRequestURI();
        boolean mustLogin = requiresLogin(request, path);

        String token = resolveToken(request);

        // ---- 没带令牌 ----
        if (token == null) {
            if (mustLogin) {
                reject(response, "请先登录");
                return false;
            }
            return true;          // 公开路径，匿名访问
        }

        // ---- 带了令牌，校验 ----
        try {
            Claims claims = jwtTokenProvider.parse(token);

            // 只认普通用户的令牌（管理员令牌一律不认，见类注释）
            if (!JwtTokenProvider.TYPE_USER.equals(jwtTokenProvider.typeOf(claims))) {
                return handleInvalid(response, mustLogin, "这不是用户令牌");
            }

            // 被登出、或者管理员封禁时被批量注销掉了
            if (!tokenRevokeStore.isActiveUser(claims.getId())) {
                return handleInvalid(response, mustLogin, "会话已失效");
            }

            UserContextHolder.set(jwtTokenProvider.toUserPrincipal(claims));
            return true;

        } catch (JwtException | IllegalArgumentException e) {
            // 过期、签名不对、格式错都归这里。
            // 不把原因告诉前端 —— 那是免费情报
            log.debug("用户令牌校验失败：{}", e.getMessage());
            return handleInvalid(response, mustLogin, "令牌无效");
        }
    }

    /**
     * 请求结束清理线程上下文。
     *
     * <p>和 {@link AdminAuthInterceptor#afterCompletion} 同样的道理：
     * 不清就会把身份残留给复用同一线程的下一个请求，那是越权。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContextHolder.clear();
    }

    /**
     * 令牌无效时的统一处理。
     *
     * <p>受限路径直接拒绝；公开路径则"当作没登录"继续走 ——
     * 访客拿着一个过期令牌看文章，不该被 401 拦在门外。
     *
     * @param mustLogin 当前路径是否必须登录
     * @return 受限路径返回 {@code false}，公开路径返回 {@code true}
     */
    private boolean handleInvalid(HttpServletResponse response, boolean mustLogin, String reason)
            throws IOException {
        if (mustLogin) {
            reject(response, "登录已失效，请重新登录");
            return false;
        }
        log.debug("公开路径上忽略了无效令牌：{}", reason);
        return true;
    }

    /**
     * 判断这个请求是否必须登录。
     *
     * <p>规则写在这个方法里而不是散在配置中，是为了"哪些接口要登录"
     * 这件事只有一处定义，改的时候不会漏。
     *
     * @param request 请求
     * @param path    请求路径
     * @return 需要登录返回 {@code true}
     */
    private boolean requiresLogin(HttpServletRequest request, String path) {
        // 注册、登录本身当然不能要求先登录（否则永远登不进来）
        if (path.equals("/client/api/user/register") || path.equals("/client/api/user/login")) {
            return false;
        }

        // 我的信息、登出、我的帖子 —— 一律要登录
        if (path.startsWith("/client/api/user/")) {
            return true;
        }

        // 发评论要登录；看评论列表不用
        if (path.startsWith("/client/api/comments")) {
            return !"GET".equalsIgnoreCase(request.getMethod());
        }

        // 发帖要登录；看帖子列表不用。
        // 但 /posts/mine 是"我的帖子"，它是 GET 却必须登录 ——
        // 这个特例单独判，否则会漏：GET 一律放行的话，
        // 未登录的人也能调它（虽然查出来是空列表，但语义是错的）
        if (path.startsWith("/client/api/posts")) {
            if (path.endsWith("/mine")) {
                return true;
            }
            return !"GET".equalsIgnoreCase(request.getMethod());
        }

        // 其余（文章列表/详情）公开
        return false;
    }

    /**
     * 从请求头里取令牌，支持带或不带 {@code Bearer} 前缀。
     *
     * @param request 请求
     * @return 令牌字符串，没有则 {@code null}
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_NAME);
        if (header == null || header.isBlank()) {
            return null;
        }
        String token = header.trim();
        if (token.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            token = token.substring(BEARER_PREFIX.length()).trim();
        }
        return token.isBlank() ? null : token;
    }

    /**
     * 写回统一格式的错误响应（HTTP 200 + 业务码，沿用项目约定）。
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
