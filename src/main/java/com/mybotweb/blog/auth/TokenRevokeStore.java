package com.mybotweb.blog.auth;

import com.mybotweb.blog.config.BlogProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 已签发令牌的登记处（存在 Redis）。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么需要它 —— JWT 天生的短板</b>
 *
 * <p>JWT 是<b>无状态</b>的：服务器签发之后不存任何东西，
 * 只靠签名验证。这带来一个绕不过去的问题：
 * <b>签出去的令牌，服务器收不回来。</b>
 *
 * <p>具体表现为两件让人难受的事：
 * <ol>
 *   <li>点了"登出"，把浏览器里的令牌删掉 —— 但如果那串令牌已经被
 *       复制走了，它照样能用到过期为止</li>
 *   <li>改了密码，旧令牌照样有效 —— 而用户改密码的心理预期是
 *       "把所有登录踢下线"</li>
 * </ol>
 *
 * <p>解法就是"记一份名单"：签发时往 Redis 写一条
 * {@code admin:token:{jti}}，登出时删掉。校验时先验签名，
 * 再问一句"这条还在名单里吗"，两个都过才放行。
 *
 * <p>于是 JWT 从"完全无状态"变成了"有个小账本"——
 * 代价是每次请求多一次 Redis 查询（内存操作，微秒级），
 * 换来的是登出真的有效。
 *
 * <p><b>为什么不用"黑名单"（只记被吊销的）而用"白名单"（记全部有效的）</b>
 * 黑名单的问题是它得一直留着：一条记录被吊销后要留到令牌自然过期才能删，
 * 期间只要 Redis 被清过一次，所有已吊销的令牌就"复活"了。
 * 白名单则相反 —— Redis 一清，所有人都要重新登录，
 * 这是安全的方向（宁可多登一次），而不是危险的方向。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRevokeStore {

    /**
     * key 前缀。完整形式：{@code admin:token:{jti}}
     *
     * <p>用冒号分层是 Redis 的惯例，好处是在 {@code redis-cli} 里
     * 敲 {@code keys admin:token:*} 就能看到所有登录中的会话。
     */
    private static final String KEY_PREFIX = "admin:token:";

    private final StringRedisTemplate redisTemplate;

    private final BlogProperties blogProperties;

    /**
     * 登记一个刚签发的令牌。
     *
     * <p>存的 value 是登录名，作用是排查时能直接看出"这是谁的会话"，
     * 不用再去解令牌。判断有效性只看 key 在不在，不看 value。
     *
     * @param tokenId  令牌里的 jti
     * @param username 登录名
     */
    public void register(String tokenId, String username) {
        if (!isEnabled()) {
            return;
        }
        // 过期时间和令牌本身的有效期一致：令牌到期后这条记录自动消失，
        // 不用写清理任务，也不会在 Redis 里留垃圾
        redisTemplate.opsForValue().set(
                KEY_PREFIX + tokenId,
                username,
                Duration.ofSeconds(currentExpireSeconds()));
        log.debug("登记登录会话：{}", username);
    }

    /**
     * 这个令牌是否还在有效名单里。
     *
     * <p>返回 {@code false} 的两种情形：已登出、或者这条记录已经
     * 因为到期被 Redis 自动删了。后者其实是正常情况 ——
     * 令牌本身就过期了，签名校验那一步会先拦下来。
     *
     * @param tokenId 令牌里的 jti
     * @return 还在名单里返回 {@code true}
     */
    public boolean isActive(String tokenId) {
        if (!isEnabled() || tokenId == null) {
            // 关掉吊销功能时一律放行，只靠签名和有效期保护
            return true;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
    }

    /**
     * 注销一个令牌 —— 这就是"登出"在服务端做的全部事情。
     *
     * @param tokenId 令牌里的 jti
     */
    public void revoke(String tokenId) {
        if (!isEnabled() || tokenId == null) {
            return;
        }
        redisTemplate.delete(KEY_PREFIX + tokenId);
        log.info("已注销登录会话：{}", tokenId);
    }

    /**
     * 当前有效会话数，用来在后台首页显示"几个地方登录着"。
     *
     * <p>用 {@code keys} 命令统计。会话数很少（通常就一两个），
     * 所以这里不担心性能问题；但如果以后要做"一键踢掉所有设备"，
     * 改用 {@code SCAN} 更稳妥 —— {@code keys} 在大 key 空间下会阻塞 Redis。
     *
     * @return 有效会话条数
     */
    public long countActiveSessions() {
        if (!isEnabled()) {
            return 0;
        }
        try {
            var keys = redisTemplate.keys(KEY_PREFIX + "*");
            return keys == null ? 0 : keys.size();
        } catch (Exception e) {
            // 统计失败不该让整个后台首页打不开，所以这里吞掉异常只记日志
            log.warn("统计有效会话数失败：{}", e.getMessage());
            return 0;
        }
    }

    /**
     * Redis 里那条记录的存活时长，从令牌配置里取。
     *
     * <p>为什么实时从配置读而不是在字段里缓存一次：
     * 这个类和方法都很轻，读一次属性几乎没有开销；
     * 而缓存下来就多了一处"配置改了但值没变"的坑。
     *
     * @return 有效秒数
     */
    private long currentExpireSeconds() {
        return blogProperties.getJwt().getExpireMinutes() * 60L;
    }

    /** 吊销功能是否开启（对应 {@code app.blog.jwt.revoke-enabled}） */
    private boolean isEnabled() {
        return blogProperties.getJwt().isRevokeEnabled();
    }
}
