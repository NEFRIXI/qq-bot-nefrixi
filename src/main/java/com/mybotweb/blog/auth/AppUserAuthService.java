package com.mybotweb.blog.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mybotweb.blog.dto.LoginRequest;
import com.mybotweb.blog.dto.UserRegisterRequest;
import com.mybotweb.blog.entity.AppUser;
import com.mybotweb.blog.entity.AppUserStatus;
import com.mybotweb.blog.mapper.AppUserMapper;
import com.mybotweb.blog.vo.LoginVo;
import com.mybotweb.blog.vo.UserInfoVo;
import com.mybotweb.common.exception.BusinessException;
import com.mybotweb.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 普通用户的注册、登录、登出。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>它和 {@link AdminAuthService} 结构相似，但刻意没有复用</b>
 *
 * <p>两套流程都走"查库 → BCrypt → 签 JWT → 登记 Redis"，看起来能抽成
 * 一个公共方法。但它们有三处实质差异，强行合并会让每一处都变成
 * 一个 if 分支：
 * <ul>
 *   <li><b>注册</b>：用户有自助注册，管理员没有（管理员由站长配置）</li>
 *   <li><b>封禁检查</b>：用户登录前要查 status，管理员没有封禁概念</li>
 *   <li><b>会话前缀</b>：{@code user:token:} 与 {@code admin:token:} 分开</li>
 * </ul>
 * 分开写之后，两边各自的规则都是平的，读的人不用在脑子里过滤分支。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>注册为什么直接返回令牌</b>
 *
 * <p>注册成功却不登录，用户还得再输一遍账号密码 —— 纯属添堵。
 * 这里直接把令牌一起签好，前端拿到就处于已登录状态。
 *
 * @author mybotweb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppUserAuthService {

    /** 连续登录失败多少次之后锁定 */
    private static final int MAX_FAILURES = 5;

    /** 锁定时长（秒） */
    private static final long LOCK_SECONDS = 300;

    /** 登录失败计数的 key 前缀，完整形式 {@code user:login:fail:{用户名}} */
    private static final String FAIL_KEY_PREFIX = "user:login:fail:";

    /**
     * 用户不存在时用来"陪跑"的假哈希。
     *
     * <p>作用和 {@code AdminAuthService} 里那个完全一样：
     * 让"用户不存在"和"密码错"两条路径的耗时一致，
     * 否则攻击者量一下响应时间就能枚举出哪些用户名真实存在。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AppUserMapper appUserMapper;

    private final JwtTokenProvider jwtTokenProvider;

    private final TokenRevokeStore tokenRevokeStore;

    private final PasswordEncoder passwordEncoder;

    private final StringRedisTemplate redisTemplate;

    /**
     * 注册并直接登录。
     *
     * @param request 注册请求（字段已通过 {@code @Valid} 校验）
     * @return 令牌和显示名，前端拿到即处于登录态
     * @throws BusinessException 用户名已存在（400）
     */
    @Transactional
    public LoginVo register(UserRegisterRequest request) {
        String username = request.getUsername().trim();

        // 先查一次给友好提示。唯一索引是最后的保险 ——
        // 两个请求同时注册同一个名字时，先查后插是拦不住的，
        // 那时会由数据库抛异常（见下面的 catch）
        boolean exists = appUserMapper.exists(
                new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username));
        if (exists) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "这个用户名已经被注册了");
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        // BCrypt 加密，每 100 毫秒左右一次。数据库里永远不出现明文
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(normalizeNickname(request.getNickname()));
        user.setStatus(AppUserStatus.NORMAL);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        try {
            appUserMapper.insert(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发注册撞在唯一索引上。对用户来说和"已注册"是同一件事
            log.warn("注册时撞上唯一索引：{}", username);
            throw new BusinessException(ResultCode.PARAM_ERROR, "这个用户名已经被注册了");
        }

        log.info("新用户注册：{}（id={}）", username, user.getId());

        // 注册成功直接签发令牌，省掉一次多余的登录
        return issueToken(user);
    }

    /**
     * 登录。
     *
     * @param request 登录请求
     * @return 令牌和显示名
     * @throws BusinessException 用户名密码不对（401）、被锁定（429）、已封禁（403）
     */
    public LoginVo login(LoginRequest request) {
        String username = request.getUsername().trim();

        checkNotLocked(username);

        AppUser user = appUserMapper.selectOne(
                new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username));

        boolean passwordOk;
        if (user == null) {
            passwordEncoder.matches(request.getPassword(), DUMMY_HASH);
            passwordOk = false;
        } else {
            passwordOk = passwordEncoder.matches(request.getPassword(), user.getPasswordHash());
        }

        if (!passwordOk) {
            recordFailure(username);
            log.warn("用户登录失败：username={}", username);
            // 不区分"用户不存在"和"密码错"，理由同管理端
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码不对");
        }

        // 【封禁 / 删除检查放在密码校验之后】
        // 反过来做的话，"这个账号被禁用了"这句话本身就成了一个用户名探测器。
        // 密码都对了才告诉他被封了，信息量才不会被白嫖。
        //
        // 被软删除的账号同样不让进：deleted_at 有值 = 这个人已经不存在了。
        // 提示语和封禁共用一句，不透露"是被删了还是被封了"。
        // 禁言（MUTED）不拦 —— 他能登录、能看，只是发不了东西。
        if (user.getDeletedAt() != null
                || (user.getStatus() != null && user.getStatus() == AppUserStatus.BANNED)) {
            log.warn("已封禁或已删除用户尝试登录：{}", username);
            throw new BusinessException(ResultCode.FORBIDDEN, "这个账号已被停用，请联系站长");
        }

        clearFailures(username);
        log.info("用户登录成功：{}", username);

        return issueToken(user);
    }

    /**
     * 登出 —— 删掉服务端那条会话记录。
     *
     * @param principal 当前登录用户
     */
    public void logout(UserPrincipal principal) {
        if (principal == null || principal.getTokenId() == null) {
            return;
        }
        tokenRevokeStore.revokeUser(principal.getTokenId());
        log.info("用户登出：{}", principal.getUsername());
    }

    /**
     * 取当前用户的完整信息（给 /me 用）。
     *
     * @param principal 当前登录用户
     * @return 不含密码字段的信息
     * @throws BusinessException 用户已被删除时
     */
    public UserInfoVo currentUser(UserPrincipal principal) {
        AppUser user = appUserMapper.selectById(principal.getId());
        if (user == null) {
            // 令牌还没过期，但账号没了。当成登录失效处理，前端会重新登录
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号不存在，请重新登录");
        }
        return UserInfoVo.from(user);
    }

    // ========================================================
    // 内部
    // ========================================================

    /**
     * 签发令牌 + 登记 Redis 会话。
     *
     * <p>注册和登录最后都走到这里 —— 这是两套流程唯一的共同点，
     * 所以抽出来没有歧义。
     *
     * @param user 用户
     * @return 给前端的登录结果
     */
    private LoginVo issueToken(AppUser user) {
        String tokenId = UUID.randomUUID().toString();
        String token = jwtTokenProvider.createUserToken(user, tokenId);
        tokenRevokeStore.registerUser(tokenId, user.getUsername());

        LoginVo vo = new LoginVo();
        vo.setToken(token);
        vo.setExpireSeconds(jwtTokenProvider.getExpireSeconds());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        return vo;
    }

    /**
     * 昵称归一化：去空格，空串当成 null。
     *
     * <p>把 {@code ""} 存进库和存 {@code null} 在查询上没区别，
     * 但展示时要判断两种空值。统一成 null，后面只判一种。
     *
     * @param nickname 原始昵称
     * @return 处理后的昵称，可能是 {@code null}
     */
    private String normalizeNickname(String nickname) {
        if (nickname == null) {
            return null;
        }
        String trimmed = nickname.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 检查是否处于登录失败锁定中。
     *
     * @param username 登录名
     * @throws BusinessException 锁定中抛 429
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
            // 值被改坏了，当成没失败过，别把用户锁在外面
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
     * 记一次登录失败。
     *
     * <p>只在第一次失败时设过期时间 —— 每次都重设的话，
     * 攻击者不停重试会让 key 永不过期，反而把正常用户永久锁死。
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
     * 登录成功后清空失败计数。
     *
     * @param username 登录名
     */
    private void clearFailures(String username) {
        redisTemplate.delete(FAIL_KEY_PREFIX + username);
    }
}
