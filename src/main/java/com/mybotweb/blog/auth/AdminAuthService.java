package com.mybotweb.blog.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mybotweb.blog.config.BlogProperties;
import com.mybotweb.blog.dto.LoginRequest;
import com.mybotweb.blog.entity.AdminUser;
import com.mybotweb.blog.mapper.AdminUserMapper;
import com.mybotweb.blog.vo.LoginVo;
import com.mybotweb.common.exception.BusinessException;
import com.mybotweb.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 后台登录、登出。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>登录成功的整个过程</b>
 *
 * <pre>
 * 1. 拿用户名去 admin_user 表查           → 查不到就失败
 * 2. BCrypt 校验密码（约 100 毫秒）        → 不对就失败
 * 3. 生成一个随机 tokenId
 * 4. 用它签出一个 JWT（7 天有效）          → 返回给前端保存
 * 5. 把 tokenId 写进 Redis                → 登出时删它
 * </pre>
 *
 * <p>第 4 步和第 5 步各自解决一个问题：
 * JWT 让服务器不用每次查库就知道"你是谁"，Redis 那一条让服务器能"反悔"。
 * 只有前者就只能等它自然过期，只有后者就每次请求都要查库。
 *
 * @author mybotweb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    /** 连续失败多少次之后锁定 */
    private static final int MAX_FAILURES = 5;

    /** 锁定时长（秒）。5 分钟足够让手动试密码的人冷静，又不至于误伤自己 */
    private static final long LOCK_SECONDS = 300;

    /** 登录失败计数的 Redis key 前缀，完整形式 {@code admin:login:fail:{用户名}} */
    private static final String FAIL_KEY_PREFIX = "admin:login:fail:";

    /**
     * 一个用不上的假哈希。
     *
     * <p><b>它的作用只有一个：消耗时间。</b>
     * 用户名不存在时如果直接返回，这次请求只要几毫秒；而密码错时要跑
     * 一百多毫秒的 BCrypt 计算。攻击者量一下响应时间，就能判断出
     * 哪些用户名是真实存在的 —— 这叫时序攻击。
     * 拿这个假哈希跑一次同样的计算，两条路径的耗时就没区别了。
     *
     * <p>它对应什么密码不重要，因为校验结果一定是 false。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AdminUserMapper adminUserMapper;

    private final JwtTokenProvider jwtTokenProvider;

    private final TokenRevokeStore tokenRevokeStore;

    private final PasswordEncoder passwordEncoder;

    private final BlogProperties blogProperties;

    private final StringRedisTemplate redisTemplate;

    /**
     * 登录，成功返回令牌。
     *
     * @param request 登录请求（已通过 {@code @Valid} 校验非空）
     * @return 令牌和显示名
     * @throws BusinessException 用户名密码不对（401）、失败次数过多（429）
     */
    public LoginVo login(LoginRequest request) {
        // 去掉首尾空格：用户从别处复制用户名时经常会多带一个空格，
        // 为这个报"用户名或密码不对"太冤
        String username = request.getUsername().trim();

        checkNotLocked(username);

        AdminUser user = adminUserMapper.selectOne(
                new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getUsername, username));

        boolean passwordOk;
        if (user == null) {
            // 见 DUMMY_HASH 的注释：这里跑一次无用的哈希计算，只为让耗时一致
            passwordEncoder.matches(request.getPassword(), DUMMY_HASH);
            passwordOk = false;
        } else {
            passwordOk = passwordEncoder.matches(request.getPassword(), user.getPasswordHash());
        }

        if (!passwordOk) {
            recordFailure(username);
            // 【日志里不要写密码】只记用户名，够排查了
            log.warn("后台登录失败：username={}", username);
            // 【提示语不区分"用户名不存在"和"密码错"】
            // 分开说等于免费告诉攻击者哪些用户名是有效的
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码不对");
        }

        clearFailures(username);

        String tokenId = UUID.randomUUID().toString();
        String token = jwtTokenProvider.createToken(user, tokenId);
        tokenRevokeStore.register(tokenId, user.getUsername());

        log.info("后台登录成功：{}", user.getUsername());

        LoginVo vo = new LoginVo();
        vo.setToken(token);
        vo.setExpireSeconds(jwtTokenProvider.getExpireSeconds());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        return vo;
    }

    /**
     * 登出 —— 把服务端记的那条会话删掉。
     *
     * <p>这一步做完，那串令牌即使是被人复制走了也立刻失效，
     * 不用等它自然过期。前端同时也要把本地存的令牌删掉，
     * 但那是"清理痕迹"，真正起作用的是这里。
     *
     * @param principal 当前登录管理员（从 ThreadLocal 拿）
     */
    public void logout(AdminPrincipal principal) {
        if (principal == null || principal.getTokenId() == null) {
            return;
        }
        tokenRevokeStore.revoke(principal.getTokenId());
        log.info("后台登出：{}", principal.getUsername());
    }

    // ========================================================
    // 登录失败限流
    //
    // 【为什么需要】后台在公网 IP 的 8080 端口上，没有这层的话
    // 谁都可以慢慢试着猜密码 —— BCrypt 只是让每次尝试变慢，
    // 并不能阻止次数。锁 5 分钟之后，猜一个密码的成本就高到不值得。
    //
    // 【为什么用 Redis 记而不是内存里的 Map】内存里的计数一重启就清零，
    // 而且将来多实例部署时各算各的。放 Redis 两边都解决。
    // ========================================================

    /**
     * 检查这个用户名是不是正被锁着。
     *
     * @param username 登录名
     * @throws BusinessException 锁定期内抛出 429
     */
    private void checkNotLocked(String username) {
        String key = FAIL_KEY_PREFIX + username;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return;
        }

        int failures;
        try {
            failures = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // 值被人手工改坏了，当成没失败过，别把用户锁在外面
            return;
        }

        if (failures < MAX_FAILURES) {
            return;
        }

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        long minutesLeft = (ttl == null || ttl <= 0) ? 1 : Math.max(1, ttl / 60);
        throw new BusinessException(ResultCode.TOO_MANY_REQUESTS,
                "密码连续错了太多次，请 " + minutesLeft + " 分钟后再试");
    }

    /**
     * 记一次失败。
     *
     * <p>只在第一次失败时设置过期时间。每次都重新设置的话，
     * 攻击者只要不停地试，这个 key 就永远不过期 —— 锁上加锁，
     * 反而变成了一种把人永久锁在外面的办法。
     *
     * @param username 登录名
     */
    private void recordFailure(String username) {
        String key = FAIL_KEY_PREFIX + username;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(LOCK_SECONDS));
        }
    }

    /**
     * 登录成功后清掉失败计数。
     *
     * @param username 登录名
     */
    private void clearFailures(String username) {
        redisTemplate.delete(FAIL_KEY_PREFIX + username);
    }
}
