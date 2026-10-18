package com.mybotweb.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 从请求里解析客户端真实 IP。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么不能直接 {@code request.getRemoteAddr()}</b>
 *
 * <p>一旦前面有 Nginx / 云负载均衡 / CDN，{@code getRemoteAddr()} 拿到的是
 * <b>代理的 IP</b>，所有人都是同一个值 —— 拿它做限流等于对全网生效，
 * 一个人刷爆就把所有人挡在外面。
 *
 * <p>代理会把原始 IP 放进 {@code X-Forwarded-For}（可能是一串，逗号分隔，
 * 最后一个是最近的代理）。所以要优先取它。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>⚠ 这里有个安全权衡，必须写清楚</b>
 *
 * <p>{@code X-Forwarded-For} 是<b>客户端可以伪造</b>的 —— 攻击者随便填一个
 * 不存在的 IP，就能绕开"按 IP 限流"。
 *
 * <p>所以真实部署的规则是：
 * <ul>
 *   <li><b>直接暴露在有代理后面</b>：信任 XFF，因为代理会覆盖它</li>
 *   <li><b>直接暴露在公网、前面没有代理</b>：<b>不能</b>信 XFF，
 *       否则等于没限流</li>
 * </ul>
 *
 * <p>用一个配置开关决定（{@code app.blog.trust-proxy}）。
 * 默认关闭 —— 宁可在配了代理但没开开关时"误限"（限到代理 IP，
 * 影响所有共享出口的用户），也不要默认开着让冒充随便绕。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class ClientIpResolver {

    /** 标准头 */
    private static final String HEADER_XFF = "X-Forwarded-For";

    /** 部分代理用这个（如 Cloudflare） */
    private static final String HEADER_REAL_IP = "X-Real-IP";

    /**
     * 解析客户端 IP。
     *
     * @param request     请求
     * @param trustProxy  是否信任反向代理传来的头
     * @return IP 字符串，拿不到时返回 {@code "unknown"}
     */
    public String resolve(HttpServletRequest request, boolean trustProxy) {
        if (trustProxy) {
            String xff = request.getHeader(HEADER_XFF);
            if (xff != null && !xff.isBlank()) {
                // 取第一个 —— 那是最初发起请求的客户端。
                // 链路上后续的代理会往后追加，第一个才是"源头"
                String first = xff.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return normalize(first);
                }
            }

            String realIp = request.getHeader(HEADER_REAL_IP);
            if (realIp != null && !realIp.isBlank()) {
                return normalize(realIp.trim());
            }
        }

        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : normalize(remote);
    }

    /**
     * 归一化 IP。
     *
     * <p>只做一件事：把 IPv6 的 {@code ::1} 和 IPv4 的 {@code 127.0.0.1}
     * 当成两个不同的值 —— 它们确实是不同的协议，但都代表本机，
     * 拿来当 Redis key 时没必要区分。
     *
     * <p>另外去掉 {@code :端口} 后缀（有些头里会带），否则同一台机器
     * 每次换端口就是一个新 key，限流就形同虚设了。
     *
     * @param ip 原始 IP
     * @return 干净的 IP
     */
    private String normalize(String ip) {
        String value = ip.trim();

        // IPv4 带端口：1.2.3.4:5678 → 1.2.3.4
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            // 只有一个冒号，说明是 IPv4:port
            value = value.substring(0, colon);
        }

        if ("0:0:0:0:0:0:0:1".equals(value) || "::1".equals(value)) {
            return "127.0.0.1";
        }
        return value;
    }
}
