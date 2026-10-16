package com.mybotweb.blog.service;

import com.mybotweb.blog.auth.TokenRevokeStore;
import com.mybotweb.blog.config.BlogProperties;
import com.mybotweb.blog.entity.Article;
import com.mybotweb.blog.entity.ArticleStatus;
import com.mybotweb.blog.vo.ArticleRankItemVo;
import com.mybotweb.blog.vo.ArticleStatsVo;
import com.mybotweb.blog.vo.SystemStatusVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 服务器状态和访问统计 —— 后台那两个页面的数据来源。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>⚠ 这里所有的探测都必须"失败也不抛异常"</b>
 *
 * <p>这个类的职责恰好是"告诉你哪个东西坏了"。如果 MySQL 挂了、
 * 它自己也跟着抛异常返回 500，那你就永远看不到"MySQL 挂了"这句话 ——
 * 最需要它工作的时候它偏不工作。
 *
 * <p>所以每个探测都包在自己的 try/catch 里，失败就把结果记到
 * {@code ok=false} 和 {@code message} 上，照常返回。
 *
 * @author mybotweb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemStatusService {

    /** 排行榜取前几名 */
    private static final int TOP_N = 5;

    /** 把字节换算成 MB */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /** 把字节换算成 GB */
    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

    /** 异常信息最长留多少个字，防止一整段堆栈被塞进页面 */
    private static final int MAX_MESSAGE_LENGTH = 200;

    private final DataSource dataSource;

    private final StringRedisTemplate redisTemplate;

    private final ArticleService articleService;

    private final ArticleCounterService counterService;

    private final TokenRevokeStore tokenRevokeStore;

    private final BlogProperties blogProperties;

    /** 应用名，直接用配置里的值 */
    @Value("${spring.application.name:mybotweb}")
    private String applicationName;

    // ========================================================
    // 服务器状态
    // ========================================================

    /**
     * 汇总一次服务器状态。
     *
     * <p>这个方法会真的去连一次 MySQL 和 Redis，所以它<b>不是瞬时的</b> ——
     * MySQL 没启动时可能等上几秒（等连接超时）。
     * 后台页面加载这个数据时要有"正在读取"的提示。
     *
     * @return 状态快照
     */
    public SystemStatusVo collectStatus() {
        SystemStatusVo vo = new SystemStatusVo();

        fillApplicationInfo(vo);
        fillJvmInfo(vo);
        fillSystemInfo(vo);
        fillDiskInfo(vo);
        fillMysqlInfo(vo);
        fillRedisInfo(vo);

        return vo;
    }

    /**
     * 应用自身的信息。
     */
    private void fillApplicationInfo(SystemStatusVo vo) {
        vo.setApplicationName(applicationName);
        vo.setBlogEnabled(blogProperties.isEnabled());
        vo.setServerTime(LocalDateTime.now());

        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
        vo.setUptimeSeconds(uptimeSeconds);
        vo.setUptimeText(formatUptime(uptimeSeconds));

        // 会话数从 Redis 读。这里不 try/catch —— TokenRevokeStore 内部
        // 已经处理过了（读不到就返回 0）
        vo.setActiveSessions(tokenRevokeStore.countActiveSessions());
    }

    /**
     * JVM 内存信息。
     */
    private void fillJvmInfo(SystemStatusVo vo) {
        Runtime runtime = Runtime.getRuntime();

        long used = runtime.totalMemory() - runtime.freeMemory();
        long committed = runtime.totalMemory();
        long max = runtime.maxMemory();

        vo.setJvmUsedMb(used / BYTES_PER_MB);
        vo.setJvmCommittedMb(committed / BYTES_PER_MB);
        vo.setJvmMaxMb(max / BYTES_PER_MB);
        vo.setJvmUsagePercent(percent(used, max));
        vo.setJavaVersion(System.getProperty("java.version"));
    }

    /**
     * 整台机器的 CPU 和内存。
     *
     * <p>用的是 {@code com.sun.management.OperatingSystemMXBean} ——
     * 标准的 {@code java.lang.management} 接口里没有"物理内存"和
     * "CPU 负载"这两项。它在 HotSpot 上都拿得到，
     * 但为了保险，这里做了类型判断：拿不到就把 CPU 置成 -1，
     * 前端显示"--"。
     */
    private void fillSystemInfo(SystemStatusVo vo) {
        vo.setOsName(System.getProperty("os.name") + " " + System.getProperty("os.version"));
        vo.setCpuCores(Runtime.getRuntime().availableProcessors());

        var osBean = ManagementFactory.getOperatingSystemMXBean();
        if (!(osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean)) {
            // 拿不到就用明确的值表示"不知道"。给 0 会被误读成"完全空闲"
            vo.setCpuLoadPercent(-1);
            return;
        }

        long total = sunOsBean.getTotalMemorySize();
        long free = sunOsBean.getFreeMemorySize();

        vo.setSystemTotalMemoryMb(total / BYTES_PER_MB);
        vo.setSystemFreeMemoryMb(free / BYTES_PER_MB);
        vo.setSystemUsedMemoryMb((total - free) / BYTES_PER_MB);
        vo.setSystemMemoryUsagePercent(percent(total - free, total));

        // getCpuLoad() 返回 0.0~1.0，取不到时返回 -1.0（不是 0）
        double cpuLoad = sunOsBean.getCpuLoad();
        vo.setCpuLoadPercent(cpuLoad < 0 ? -1 : Math.round(cpuLoad * 10000.0) / 100.0);
    }

    /**
     * 磁盘占用。
     *
     * <p>查的是<b>应用工作目录所在的那个盘</b>，不是"所有盘"——
     * 云服务器只有一块盘，足够。本机有多块时，这个数字对应的是
     * 项目所在盘（{@code diskPath} 字段会把具体路径带出去，
     * 免得看的人猜是哪一个）。
     */
    private void fillDiskInfo(SystemStatusVo vo) {
        try {
            File root = new File(".").getAbsoluteFile();
            vo.setDiskPath(root.getCanonicalPath());

            long total = root.getTotalSpace();
            long usable = root.getUsableSpace();
            long used = total - usable;

            vo.setDiskTotalGb(total / BYTES_PER_GB);
            vo.setDiskUsableGb(usable / BYTES_PER_GB);
            vo.setDiskUsedGb(used / BYTES_PER_GB);
            vo.setDiskUsagePercent(percent(used, total));
        } catch (Exception e) {
            log.warn("读取磁盘信息失败：{}", e.getMessage());
            vo.setDiskPath("（读取失败）");
        }
    }

    /**
     * 真的去连一次 MySQL。
     */
    private void fillMysqlInfo(SystemStatusVo vo) {
        try (Connection connection = dataSource.getConnection()) {
            String version = connection.getMetaData().getDatabaseProductVersion();
            vo.setMysqlOk(true);
            vo.setMysqlMessage("MySQL " + version + "，连接正常");
        } catch (Exception e) {
            vo.setMysqlOk(false);
            vo.setMysqlMessage("连不上：" + trimMessage(e.getMessage()));
        }
    }

    /**
     * 真的去 ping 一次 Redis。
     */
    private void fillRedisInfo(SystemStatusVo vo) {
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            vo.setRedisOk(false);
            vo.setRedisMessage("没有配置 Redis 连接");
            return;
        }

        try (RedisConnection connection = factory.getConnection()) {
            connection.ping();

            String version = "未知";
            Properties info = connection.serverCommands().info("server");
            if (info != null) {
                version = info.getProperty("redis_version", "未知");
            }

            vo.setRedisOk(true);
            vo.setRedisMessage("Redis " + version + "，连接正常");
        } catch (Exception e) {
            vo.setRedisOk(false);
            vo.setRedisMessage("连不上：" + trimMessage(e.getMessage()));
        }
    }

    // ========================================================
    // 访问统计
    // ========================================================

    /**
     * 汇总访问统计。
     *
     * <p><b>为什么用"查库拿所有文章 + 批量读计数"而不是
     * {@code keys article:view_count:*}</b>：
     * Redis 的 {@code keys} 命令会<b>阻塞整个实例</b>直到扫完所有 key，
     * 在 key 多的时候是生产事故的常见来源。
     * 走数据库拿文章清单再批量读，行为可预期，也不会阻塞 Redis。
     *
     * @return 统计结果
     */
    public ArticleStatsVo collectStats() {
        List<Article> articles = articleService.listForStats();
        List<Long> ids = articles.stream().map(Article::getId).toList();

        Map<Long, Long> viewCounts = counterService.getViewCounts(ids);
        Map<Long, Long> likeCounts = counterService.getLikeCounts(ids);

        List<ArticleRankItemVo> items = articles.stream().map(article -> {
            ArticleRankItemVo item = new ArticleRankItemVo();
            item.setId(article.getId());
            item.setSlug(article.getSlug());
            item.setTitle(article.getTitle());
            item.setViewCount(viewCounts.getOrDefault(article.getId(), 0L));
            item.setLikeCount(likeCounts.getOrDefault(article.getId(), 0L));
            return item;
        }).toList();

        ArticleStatsVo vo = new ArticleStatsVo();
        vo.setTotalArticles(articleService.countByStatus(null));
        vo.setPublishedArticles(articleService.countByStatus(ArticleStatus.PUBLISHED));
        vo.setDraftArticles(articleService.countByStatus(ArticleStatus.DRAFT));
        vo.setTotalViews(items.stream().mapToLong(ArticleRankItemVo::getViewCount).sum());
        vo.setTotalLikes(items.stream().mapToLong(ArticleRankItemVo::getLikeCount).sum());

        List<ArticleRankItemVo> topViewed = items.stream()
                .sorted(Comparator.comparingLong(ArticleRankItemVo::getViewCount).reversed())
                .limit(TOP_N)
                .toList();
        List<ArticleRankItemVo> topLiked = items.stream()
                .sorted(Comparator.comparingLong(ArticleRankItemVo::getLikeCount).reversed())
                .limit(TOP_N)
                .toList();

        vo.setTopViewed(topViewed);
        vo.setTopLiked(topLiked);

        // 只有真的有人看过/赞过才给标题，否则留着 null 让前端显示"暂无数据"。
        // 给一个"0 次浏览的冠军"看起来像 bug
        vo.setMostViewedTitle(topViewed.isEmpty() || topViewed.get(0).getViewCount() == 0
                ? null : topViewed.get(0).getTitle());
        vo.setMostLikedTitle(topLiked.isEmpty() || topLiked.get(0).getLikeCount() == 0
                ? null : topLiked.get(0).getTitle());

        return vo;
    }

    // ========================================================
    // 内部小工具
    // ========================================================

    /**
     * 算百分比。
     *
     * @param part  部分
     * @param total 总量
     * @return 百分比，保留两位小数；总量为 0 时返回 0
     */
    private double percent(long part, long total) {
        if (total <= 0) {
            return 0;
        }
        // 先乘 10000 再除、最后除 100，是为了保留两位小数又不引入浮点误差
        return Math.round(part * 10000.0 / total) / 100.0;
    }

    /**
     * 把秒数写成"几天几小时几分"。
     *
     * @param seconds 秒
     * @return 可读文本，如 {@code 2 天 5 小时 12 分}
     */
    private String formatUptime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;

        StringBuilder text = new StringBuilder();
        if (days > 0) {
            text.append(days).append(" 天 ");
        }
        if (days > 0 || hours > 0) {
            text.append(hours).append(" 小时 ");
        }
        text.append(minutes).append(" 分");
        return text.toString();
    }

    /**
     * 截短异常信息，并去掉换行。
     *
     * <p>异常消息有时会带一整段 SQL 或堆栈，原样塞进响应体
     * 既难看也可能泄露内部结构。
     *
     * @param message 原始消息
     * @return 截短后的一行文本
     */
    private String trimMessage(String message) {
        if (message == null || message.isBlank()) {
            return "原因未知（异常没有携带描述信息）";
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_MESSAGE_LENGTH
                ? oneLine
                : oneLine.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }
}
