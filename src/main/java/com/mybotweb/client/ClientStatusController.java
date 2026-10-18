package com.mybotweb.client;

import com.mybotweb.blog.service.SystemStatusService;
import com.mybotweb.blog.vo.ClientStatusVo;
import com.mybotweb.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台首页的「轻量状态」接口。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>路径为什么是 {@code /client/api/status}</b>
 *
 * <p>和管理端的 {@code /admin/api/system/status} 区分开：
 * 那边走 {@code AdminAuthInterceptor}，必须登录；这边是公开的，
 * 任何人打开首页都会调它。两个接口返回的字段也刻意不同 ——
 * 这边只有三个数字，见 {@link ClientStatusVo} 的类注释。
 *
 * <p><b>为什么要加缓存</b>
 *
 * <p>这个接口虽然很轻（不连数据库、不连 Redis），但它每次调用都要
 * 读一次 {@code /proc/meminfo}。首页是访客最容易刷的页面，
 * 有人狂按 F5 就是每秒几次的文件读取。加一个 15 秒的缓存，
 * 让刷新变成"读内存里的一个对象"，代价可以忽略。
 *
 * <p>15 秒也是首页能接受的最大陈旧度 —— 内存占用率本来就是个
 * 缓慢变化的量，15 秒前的值和现在的几乎没有区别。
 *
 * @author mybotweb
 */
@Slf4j
@RestController
@RequestMapping("/client/api")
public class ClientStatusController {

    /** 缓存有效期（毫秒） */
    private static final long CACHE_TTL_MS = 15_000L;

    private final SystemStatusService systemStatusService;

    /** 缓存的状态快照。volatile 保证多线程下读到的是完整对象 */
    private volatile ClientStatusVo cached;

    /** 上面那份快照的生成时间 */
    private volatile long cachedAt = 0L;

    public ClientStatusController(SystemStatusService systemStatusService) {
        this.systemStatusService = systemStatusService;
    }

    /**
     * 首页状态：在线与否、运行时长、内存占用。
     *
     * @return 轻量状态
     */
    @GetMapping("/status")
    public Result<ClientStatusVo> status() {
        long now = System.currentTimeMillis();

        // 过期或还没有缓存时才真的去采集一次
        if (cached == null || now - cachedAt > CACHE_TTL_MS) {
            cached = systemStatusService.collectClientStatus();
            cachedAt = now;
        }

        return Result.success(cached);
    }
}
