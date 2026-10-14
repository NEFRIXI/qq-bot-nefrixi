package com.mybotweb.security;

import com.mybotweb.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简易用户限流器 —— 防止单个用户高频发消息把模型刷爆。
 *
 * <p>对应规格第十三节"每个 QQ 用户限流，防止刷爆大模型"。
 *
 * <p><b>为什么这件事不能省？</b> 本地 4B 模型跑一次要几秒到几十秒，
 * 如果有人连续发 20 条消息，本地模型会排队排到几分钟之后，
 * 表现就是"机器人在所有人眼里都卡死了"。云端大模型则是直接烧钱。
 *
 * <p><b>本实现的局限（要诚实说明）</b>：
 * <ul>
 *   <li>只在<b>单机内存</b>里记时间戳。将来部署成多实例后，
 *       每个实例各记一份，实际允许的频率会变成"配置值 × 实例数"。
 *       届时改用 Redis 的 {@code SETNX + EXPIRE} 即可，接口不用变。</li>
 *   <li>用"上次发消息时间"做简单间隔判断，不是令牌桶。
 *       表现是"必须间隔 N 毫秒才能发下一条"，而不是"每秒允许 N 条"。
 *       对聊天场景这已经够用。</li>
 *   <li>被限流时丢弃请求并提示用户，不做排队。
 *       排队会让用户等很久才收到一句过期的话，体验更差。</li>
 * </ul>
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class RateLimiter {

    /**
     * 记录数上限。超过就触发一次清理，防止长期运行把内存吃光。
     *
     * <p>10000 个用户的时间戳（每个约 50 字节）总共不到 1MB，是安全的量级。
     */
    private static final int MAX_ENTRIES = 10_000;

    /** 应用配置，读取最小间隔 */
    private final AppProperties.Chat chatConfig;

    /** 用户 ID → 上次通过限流的时间戳（毫秒） */
    private final Map<String, Long> lastAccessMillis = new ConcurrentHashMap<>();

    public RateLimiter(AppProperties appProperties) {
        this.chatConfig = appProperties.getChat();
    }

    /**
     * 判断这次请求是否应当被限流。
     *
     * <p>注意它有<b>副作用</b>：判定通过时会顺手记录本次时间戳。
     * 所以同一个请求不要调用两次，否则第二次必然被限。
     *
     * @param userId 用户标识
     * @return 应当限流返回 {@code true}
     */
    public boolean shouldLimit(String userId) {
        long minInterval = chatConfig.getMinIntervalMillis();

        // 配置成 0 或负数表示关闭限流（调试时方便，生产别这么配）
        if (minInterval <= 0) {
            return false;
        }
        if (userId == null || userId.isBlank()) {
            return false;
        }

        long now = System.currentTimeMillis();
        Long lastMillis = lastAccessMillis.get(userId);

        if (lastMillis != null && now - lastMillis < minInterval) {
            long waitMillis = minInterval - (now - lastMillis);
            log.debug("用户 {} 触发限流，还需等待 {} ms",
                    userId.length() > 6 ? userId.substring(0, 6) + "…" : userId, waitMillis);
            return true;
        }

        lastAccessMillis.put(userId, now);

        // 简单的容量保护：超过上限就清掉已经过期的条目
        if (lastAccessMillis.size() > MAX_ENTRIES) {
            evictExpired(now, minInterval);
        }
        return false;
    }

    /**
     * 清理已经"过期"的记录。
     *
     * <p>判断过期的标准：上次访问时间已经超过最小间隔的条目，
     * 对限流判断不再有任何影响，可以直接删掉。
     *
     * <p>这个方法在字典超大时被调用，本身是 O(n) 的遍历，
     * 但因为触发频率极低（要到 10000 个不同用户），可以接受。
     *
     * @param now         当前时间戳
     * @param minInterval 最小间隔（毫秒）
     */
    private void evictExpired(long now, long minInterval) {
        int before = lastAccessMillis.size();
        // 留存时间放宽到间隔的 10 倍，避免把"刚访问过还在窗口内"的条目删掉
        long keepMillis = minInterval * 10;
        lastAccessMillis.entrySet().removeIf(entry -> now - entry.getValue() > keepMillis);
        log.debug("限流器清理：{} 条 → {} 条", before, lastAccessMillis.size());
    }

    /**
     * 当前记录的用户数，供 {@code /status} 展示。
     *
     * @return 记录数
     */
    public int trackedUserCount() {
        return lastAccessMillis.size();
    }
}
