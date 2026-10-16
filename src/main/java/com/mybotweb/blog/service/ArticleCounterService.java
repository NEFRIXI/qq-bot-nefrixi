package com.mybotweb.blog.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文章的点赞数和浏览量 —— 存在 Redis 里。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>⚠ 本项目不做任何"识别真人和机器人"的处理</b>
 *
 * <p>按用户的要求：<b>点一下就 +1，不做任何去重。</b>
 *
 * <p>所以这里只有两条命令：
 * <pre>
 * article:like_count:{文章id}    INCR
 * article:view_count:{文章id}    INCR
 * </pre>
 * 没有 {@code article:liked:{id}} 这种"记录谁点过"的集合，
 * 也没有按 IP / User-Agent / 浏览器指纹去重。
 *
 * <p>后果要知道：同一篇文章连点十次就是 +10，刷新一次浏览量也 +1。
 * 这是刻意的取舍 —— 精确识别要么需要登录（普通用户不登录），
 * 要么需要一套指纹逻辑（用户明确说以后再做）。
 *
 * <p><b>将来要扩展时不用改现有数据</b>：加一个
 * {@code article:liked:{id}} 的 SET、用 SADD 去重即可，
 * 上面这两个计数器照旧当总数用，一个字节都不用迁。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么计数不写 MySQL</b>
 *
 * <p>这两个数会被反复 +1（每来一个访客就一次）。写 MySQL 意味着
 * 每次都要走一次磁盘事务，而 Redis 是内存里的一次加法，快好几个数量级。
 *
 * <p>代价是 Redis 重启可能丢掉计数（取决于持久化配置）。
 * 对这个场景可以接受 —— 浏览量本来就是"大概有个数就行"。
 * 如果以后需要绝对准确，做法是定时把 Redis 的值回写到 article 表，
 * 但那是以后的事。
 *
 * @author mybotweb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleCounterService {

    /** 点赞数的 key 前缀，完整形式 {@code article:like_count:{文章id}} */
    private static final String LIKE_KEY_PREFIX = "article:like_count:";

    /** 浏览量的 key 前缀，完整形式 {@code article:view_count:{文章id}} */
    private static final String VIEW_KEY_PREFIX = "article:view_count:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 点赞数 +1。
     *
     * <p>{@code increment} 就是 Redis 的 {@code INCR}：
     * key 不存在时先当成 0 再 +1，所以第一次点赞会自动创建这个 key，
     * 不需要提前初始化。这也是不用"先查再写"的原因 ——
     * 那种写法在并发下会丢计数。
     *
     * @param articleId 文章 id
     * @return 加完之后的值
     */
    public long incrementLike(Long articleId) {
        Long value = redisTemplate.opsForValue().increment(LIKE_KEY_PREFIX + articleId);
        long result = value == null ? 0L : value;
        log.debug("文章 {} 点赞 +1，当前 {}", articleId, result);
        return result;
    }

    /**
     * 浏览量 +1。
     *
     * <p><b>每次访问详情都调用，不做去重</b> —— 同一个人刷新十次就是 +10。
     * 这是"普通用户不登录"前提下最简单的做法，也是用户要求的做法。
     *
     * @param articleId 文章 id
     * @return 加完之后的值
     */
    public long incrementView(Long articleId) {
        Long value = redisTemplate.opsForValue().increment(VIEW_KEY_PREFIX + articleId);
        long result = value == null ? 0L : value;
        log.debug("文章 {} 浏览量 +1，当前 {}", articleId, result);
        return result;
    }

    /**
     * 读一篇文章的点赞数。
     *
     * @param articleId 文章 id
     * @return 点赞数，没记录时返回 0
     */
    public long getLikeCount(Long articleId) {
        return readCount(LIKE_KEY_PREFIX + articleId);
    }

    /**
     * 读一篇文章的浏览量。
     *
     * @param articleId 文章 id
     * @return 浏览量，没记录时返回 0
     */
    public long getViewCount(Long articleId) {
        return readCount(VIEW_KEY_PREFIX + articleId);
    }

    /**
     * 批量读点赞数 —— 列表页用。
     *
     * <p><b>为什么要有批量版本</b>：列表页有 10 篇文章，
     * 如果每篇单独读一次，就是 10 次网络往返。
     * {@code multiGet} 一次就取回来，这是列表接口能不能快起来的关键。
     *
     * @param articleIds 文章 id 集合
     * @return id → 点赞数。没有记录的文章也会出现在结果里，值为 0
     */
    public Map<Long, Long> getLikeCounts(Collection<Long> articleIds) {
        return readCounts(LIKE_KEY_PREFIX, articleIds);
    }

    /**
     * 批量读浏览量 —— 列表页用。
     *
     * @param articleIds 文章 id 集合
     * @return id → 浏览量
     */
    public Map<Long, Long> getViewCounts(Collection<Long> articleIds) {
        return readCounts(VIEW_KEY_PREFIX, articleIds);
    }

    /**
     * 清掉一篇文章的两个计数器 —— 删除文章时调用。
     *
     * <p>为什么不留着：文章的 id 是数据库自增的，<b>不会被复用</b>。
     * 留着这两个 key 的结果是它们永远不会再被读到，只是白占内存。
     *
     * @param articleId 文章 id
     */
    public void removeCounters(Long articleId) {
        redisTemplate.delete(List.of(LIKE_KEY_PREFIX + articleId, VIEW_KEY_PREFIX + articleId));
        log.info("已清除文章 {} 的点赞数和浏览量计数", articleId);
    }

    // ========================================================
    // 下面都是内部实现
    // ========================================================

    /**
     * 读一个计数。
     *
     * @param key 完整的 Redis key
     * @return 数值，key 不存在或值不是数字时返回 0
     */
    private long readCount(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // 值被别的东西写坏了（比如手工在 redis-cli 里塞了个字符串）。
            // 返回 0 而不是抛异常 —— 一个坏掉的计数器不该让整个页面打不开
            log.warn("Redis 里的计数值不是数字，按 0 处理：key={}，value={}", key, value);
            return 0L;
        }
    }

    /**
     * 批量读计数。
     *
     * @param keyPrefix key 前缀
     * @param articleIds 文章 id 集合
     * @return id → 计数值，空集合时返回空 Map
     */
    private Map<Long, Long> readCounts(String keyPrefix, Collection<Long> articleIds) {
        Map<Long, Long> result = new HashMap<>();
        if (articleIds == null || articleIds.isEmpty()) {
            return result;
        }

        // 先把 key 的顺序固定下来。multiGet 返回的 List 顺序和传入的 key 顺序一一对应，
        // 所以必须用这个 List 来定位"第几个结果对应哪篇文章"
        List<Long> idList = List.copyOf(articleIds);
        List<String> keys = idList.stream()
                .map(id -> keyPrefix + id)
                .collect(Collectors.toList());

        List<String> values = redisTemplate.opsForValue().multiGet(keys);

        for (int i = 0; i < idList.size(); i++) {
            // values 理论上和 keys 等长，但 multiGet 的返回值允许为 null，
            // 所以这里老老实实判一下，不要假设
            String value = (values == null || i >= values.size()) ? null : values.get(i);

            long count = 0L;
            if (value != null) {
                try {
                    count = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    log.warn("Redis 里的计数值不是数字，按 0 处理：value={}", value);
                }
            }
            // 没有记录的文章也要放进结果里（值为 0）——
            // 这样调用方拿到的 Map 一定是完整的，不用再判空
            result.put(idList.get(i), count);
        }

        return result;
    }
}
