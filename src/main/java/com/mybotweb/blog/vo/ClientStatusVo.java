package com.mybotweb.blog.vo;

import lombok.Data;

/**
 * 前台首页用的「轻量状态」。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么不直接复用 {@link SystemStatusVo}</b>
 *
 * <p>那份 VO 是给后台诊断用的，里面有 MySQL/Redis 的连通性、
 * 失败原因、磁盘路径、Java 版本、登录会话数。这些信息放在
 * <b>任何人不用登录都能打开的首页</b>上，等于把服务器内部结构
 * 和故障细节公开了 —— 该藏的东西一样都没藏住。
 *
 * <p>而且 {@code collectStatus()} 会真的去连一次 MySQL 和 Redis，
 * 数据库没启动时要等连接超时（最长 10 秒）。首页每次刷新都背着
 * 这个代价也不合理。
 *
 * <p>所以这里只保留三样：<b>活着没有、活了多久、内存吃了多少</b>。
 * 访客能看出"这站是活的"，但看不出任何内部细节。
 * ============================================================
 *
 * @author mybotweb
 */
@Data
public class ClientStatusVo {

    /**
     * 服务是否在线。
     *
     * <p>能拿到这个对象本身就说明在线了，这个字段更多是给前端
     * 一个明确的判断点，省得靠"请求成功"这种隐式约定。
     */
    private boolean online;

    /** 本次启动至今的秒数 */
    private long uptimeSeconds;

    /** 上面那个秒数的可读版本，如 {@code 2 天 5 小时 12 分} */
    private String uptimeText;

    /**
     * 整机内存占用率（%）。
     *
     * <p>取不到时是 {@code -1}，前端显示 "--"。
     * 给 0 会被误读成"完全空闲"。
     */
    private double memoryPercent;

    /** JVM 堆占用率（%）；-1 表示取不到 */
    private double jvmPercent;

    /** 整机已用内存（MB），配合 total 让前端能显示 "3.1 / 8.0 GB" */
    private long memoryUsedMb;

    /** 整机总内存（MB） */
    private long memoryTotalMb;
}
