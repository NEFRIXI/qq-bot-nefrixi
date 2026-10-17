package com.mybotweb.blog.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务器状态 —— 后台"服务器状态"页显示的内容。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>这里的数据分三类，来源完全不同</b>
 *
 * <table border="1">
 *   <tr><th>类别</th><th>来源</th><th>典型问题</th></tr>
 *   <tr>
 *     <td>JVM（进程）</td>
 *     <td>{@code Runtime} / {@code ManagementFactory}</td>
 *     <td>Java 进程自己占了多少内存</td>
 *   </tr>
 *   <tr>
 *     <td>系统（整台机器）</td>
 *     <td>{@code OperatingSystemMXBean}</td>
 *     <td>整台机器的内存和 CPU，包含别的程序</td>
 *   </tr>
 *   <tr>
 *     <td>中间件连通性</td>
 *     <td>真的去连一次 MySQL 和 Redis</td>
 *     <td>"服务在跑但连不上"这类问题</td>
 *   </tr>
 * </table>
 *
 * <p><b>JVM 内存和系统内存为什么必须分开看</b>：
 * 云服务器总共 3.6G，JVM 默认最多能用到其中的四分之一左右。
 * 只看系统内存，会以为"还剩 2G 很宽裕"，实际上 JVM 已经顶到上限、
 * 开始频繁 GC 了 —— 表现是接口变慢，而内存数字看着很正常。
 *
 * @author mybotweb
 */
@Data
public class SystemStatusVo {

    // ========================================================
    // 应用自身
    // ========================================================

    /** 应用名（spring.application.name） */
    private String applicationName;

    /** 博客总开关是否打开。关着的时候这个页面本来也进不来，留着是为了排查 */
    private boolean blogEnabled;

    /** 当前有没有效登录会话（几个地方登录着），来自 Redis */
    private long activeSessions;

    /** 服务器当前时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime serverTime;

    /**
     * 本次启动到现在运行了多久（秒）。
     * "服务器是不是刚重启过"看这个最直接。
     */
    private long uptimeSeconds;

    /** 上面那个秒数的可读版本，如 {@code 2 天 5 小时 12 分} */
    private String uptimeText;

    // ========================================================
    // JVM（Java 进程）
    // ========================================================

    /** Java 版本 */
    private String javaVersion;

    /** JVM 当前已用的堆内存（MB） */
    private long jvmUsedMb;

    /** JVM 当前已申请的堆内存（MB）。用满会向系统再要，不会超过 max */
    private long jvmCommittedMb;

    /** JVM 堆内存上限（MB）。默认是物理内存的 1/4 */
    private long jvmMaxMb;

    /** 堆内存使用率（%），按"已用 / 上限"算 */
    private double jvmUsagePercent;

    /**
     * 当前存活线程数。
     *
     * <p>线程数不像内存那样有明确上限、也没有告警阈值，所以平时没人盯。
     * 但它恰恰是<b>线程泄漏</b>最早的信号：某个地方每次请求都新建线程
     * 却没回收，或者线程池没有正确关闭 —— 内存还看不出来，线程数已经
     * 一路往上涨了。放一个在页面上，成本很低，出事时是关键线索。
     */
    private int threadCount;

    /** 启动以来的线程数峰值。当前值一直贴着峰值走，说明线程只增不减 */
    private int threadPeakCount;

    // ========================================================
    // 系统（整台机器）
    // ========================================================

    /** 操作系统名称，如 {@code Linux 5.4.0-xx} */
    private String osName;

    /** CPU 核心数 */
    private int cpuCores;

    /**
     * 系统 CPU 使用率（%）。
     *
     * <p>取不到时是 {@code -1} —— 某些虚拟机/容器环境下这个值拿不到，
     * 那时前端应该显示"--"而不是"0%"（后者会让人以为机器完全空闲）。
     */
    private double cpuLoadPercent;

    /** 机器总内存（MB） */
    private long systemTotalMemoryMb;

    /**
     * 机器已用内存（MB）。
     *
     * <p>算的是"总量 - <b>可用量</b>"，不是"总量 - 空闲量"。这两个差值能差出一倍以上，
     * 原因见 {@link #systemFreeMemoryMb}。
     */
    private long systemUsedMemoryMb;

    /**
     * 机器可用内存（MB）。
     *
     * <p>注意这里是 <b>available</b>，不是 free。Linux 上取的是
     * {@code /proc/meminfo} 的 {@code MemAvailable}，也就是"空闲 + 可以立刻回收的缓存"。
     *
     * <p>用纯 free（{@code MemFree}）会把占用率算得虚高：一台 3.7G 的机器上，
     * 真实的 43% 会显示成 95%，因为 buff/cache 那 2.2G 被当成了"已用"，
     * 而它其实随时可以被回收。详见 {@code SystemStatusService.readAvailableMemory()}。
     *
     * <p>字段名保留 {@code Free} 是为了不破坏已经发出去的 JSON 契约。
     */
    private long systemFreeMemoryMb;

    /** 机器内存使用率（%），按"已用 / 总量"算，其中"已用" = 总量 - 可用量 */
    private double systemMemoryUsagePercent;

    // ========================================================
    // 磁盘
    // ========================================================

    /** 磁盘总容量（GB） */
    private long diskTotalGb;

    /** 磁盘已用（GB） */
    private long diskUsedGb;

    /** 磁盘剩余（GB） */
    private long diskUsableGb;

    /** 磁盘使用率（%） */
    private double diskUsagePercent;

    /** 查的是哪个路径所在的磁盘，如 {@code D:\ds-work\my_web_project} */
    private String diskPath;

    // ========================================================
    // 中间件连通性
    // ========================================================

    /** MySQL 通不通 */
    private boolean mysqlOk;

    /** MySQL 的说明：通了给版本号，不通给失败原因 */
    private String mysqlMessage;

    /** Redis 通不通 */
    private boolean redisOk;

    /** Redis 的说明：通了给版本号，不通给失败原因 */
    private String redisMessage;
}
