package com.mybotweb.security;

import com.mybotweb.common.exception.BusinessException;
import com.mybotweb.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的限流器 —— <b>滑动窗口</b>算法。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>常见的四种限流算法，以及为什么选滑动窗口</b>
 *
 * <table border="1">
 *   <tr><th>算法</th><th>怎么做的</th><th>缺点</th></tr>
 *   <tr>
 *     <td>固定窗口</td>
 *     <td>给每个时间窗（如每分钟）一个计数，超了拒绝</td>
 *     <td><b>临界突变</b>：00:59 打满 100 次、01:00 又打满 100 次 ——
 *         两秒内实际放过了 200 次，是配置值的两倍</td>
 *   </tr>
 *   <tr>
 *     <td>滑动窗口</td>
 *     <td>记下每次请求的时间戳，"最近 60 秒内有多少次"</td>
 *     <td>要存时间戳，内存比计数器大；清理靠有序集合的 ZREMRANGEBYSCORE</td>
 *   </tr>
 *   <tr>
 *     <td>令牌桶</td>
 *     <td>桶里按固定速率加令牌，请求消耗令牌</td>
 *     <td>允许突发（桶满时可瞬间放行一批），要额外算"上次补充到现在"</td>
 *   </tr>
 *   <tr>
 *     <td>漏桶</td>
 *     <td>请求进桶、按固定速率流出，桶满就拒</td>
 *     <td>强制匀速，正常用户的突发点击也会被削平，体验偏硬</td>
 *   </tr>
 * </table>
 *
 * <p><b>这里选滑动窗口，理由是这个场景的需求很具体：</b>
 * 要挡的是注册、登录、发评论这类<b>低频但危险</b>的操作 ——
 * "同一 IP 一分钟最多注册 5 个号"。这类操作本来就不会有突发，
 * 令牌桶的"允许突发"反而成了漏洞：攻击者攒满桶就能一次冲 5 个。
 * 而固定窗口的临界突变在这个量级下就足够致命了。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么必须用 Lua 脚本</b>
 *
 * <p>"清过期 → 计数 → 判断 → 写入"是四步。如果分成四次 Redis 调用，
 * 两个并发请求可能同时读到 count=4（上限 5），然后都写入 ——
 * 实际放过了 6 个。Redis 单线程执行 Lua 脚本，
 * 整段逻辑原子的，不会交错。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么 key 一定要带过期时间</b>
 *
 * <p>每次请求都 PEXPIRE 一次。如果漏掉这一行，一个只用过一次的 IP
 * 会在 Redis 里留下一个永不过期的空集合 —— 扫描器换 IP 扫一遍，
 * 就是几十万个垃圾 key。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    /**
     * 滑动窗口的 Lua 实现。
     *
     * <p>KEYS[1] = 限流 key
     * <p>ARGV[1] = 当前时间戳（毫秒）
     * <p>ARGV[2] = 窗口长度（毫秒）
     * <p>ARGV[3] = 窗口内允许的最大次数
     * <p>ARGV[4] = 本次请求的唯一成员名
     *
     * @return 1 = 放行，0 = 拒绝
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT;

    static {
        SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();
        SLIDING_WINDOW_SCRIPT.setResultType(Long.class);
        SLIDING_WINDOW_SCRIPT.setScriptText("""
                local key    = KEYS[1]
                local now    = tonumber(ARGV[1])
                local window = tonumber(ARGV[2])
                local limit  = tonumber(ARGV[3])
                local member = ARGV[4]

                -- 1) 把窗口外的老记录删掉。0 到 (now - window) 这一段已经不算数了
                redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

                -- 2) 数一下窗口内还剩多少次
                local count = redis.call('ZCARD', key)

                -- 3) 已经到上限就拒绝，注意这里【不写入】
                --    否则被拒绝的请求也会占一个名额，把用户越锁越死
                if count >= limit then
                    return 0
                end

                -- 4) 放行，记下这一次
                redis.call('ZADD', key, now, member)
                -- 刷新过期时间，防止空集合长期驻留 Redis
                redis.call('PEXPIRE', key, window)
                return 1
                """);
    }

    private final StringRedisTemplate redisTemplate;

    /**
     * 尝试占用一次配额。
     *
     * <p>注意它有<b>副作用</b>：放行时会真的写入一条记录。
     * 所以同一个动作不要调两次，否则第二次会白白消耗配额。
     *
     * @param key          限流 key，如 {@code rl:register:1.2.3.4}
     * @param limit        窗口内允许的最大次数；{@code <= 0} 表示关闭限流
     * @param windowMillis 窗口长度（毫秒）
     * @return 允许这次请求返回 {@code true}
     */
    public boolean tryAcquire(String key, int limit, long windowMillis) {
        // 配成 0 或负数表示关闭 —— 调试时方便，生产别这么配
        if (limit <= 0 || windowMillis <= 0) {
            return true;
        }
        if (key == null || key.isBlank()) {
            return true;
        }

        try {
            Long result = redisTemplate.execute(
                    SLIDING_WINDOW_SCRIPT,
                    List.of(key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(windowMillis),
                    String.valueOf(limit),
                    // 成员名必须唯一，否则同一毫秒内的两次请求会覆盖彼此
                    UUID.randomUUID().toString());

            return result != null && result == 1L;

        } catch (Exception e) {
            // 【Redis 挂掉时选择放行还是拒绝？】
            // 这里选择放行（fail-open）。理由：限流是"防滥用"，
            // 不是"鉴权"。Redis 出故障时把整个站点的注册登录都锁死，
            // 造成的伤害远大于被刷几次。这个取舍要说清楚，
            // 因为它在安全上是"降级"而不是"正常"。
            log.warn("限流器执行失败，本次放行（fail-open）：{}", e.getMessage());
            return true;
        }
    }

    /**
     * 占用一次配额，超限直接抛 429。
     *
     * <p>业务代码里用这个，省得每处都写一遍 if。
     *
     * @param key          限流 key
     * @param limit        窗口内允许的最大次数
     * @param windowMillis 窗口长度（毫秒）
     * @param message      给用户看的提示
     * @throws BusinessException 超限时抛 429
     */
    public void checkOrThrow(String key, int limit, long windowMillis, String message) {
        if (!tryAcquire(key, limit, windowMillis)) {
            log.warn("触发限流：{}", key);
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, message);
        }
    }
}
