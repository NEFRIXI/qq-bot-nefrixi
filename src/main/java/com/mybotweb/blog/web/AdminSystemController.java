package com.mybotweb.blog.web;

import com.mybotweb.blog.service.SystemStatusService;
import com.mybotweb.blog.vo.ArticleStatsVo;
import com.mybotweb.blog.vo.SystemStatusVo;
import com.mybotweb.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的服务器状态和访问统计接口。
 *
 * <p>两个都是只读的 {@code GET}，不会改任何数据。
 *
 * @author mybotweb
 */
@Slf4j
@RestController
@RequestMapping("/admin/api/system")
@RequiredArgsConstructor
public class AdminSystemController {

    private final SystemStatusService systemStatusService;

    /**
     * 服务器状态：JVM 内存、机器内存、CPU、磁盘、MySQL/Redis 连通性。
     *
     * <p><b>这个接口可能比较慢</b>：它会真的去连一次 MySQL 和 Redis。
     * 数据库没启动时要等连接超时（最多 10 秒）。
     * 前端加载这个页面时应该先显示"正在读取"，而不是让页面白着。
     *
     * @return 状态快照
     */
    @GetMapping("/status")
    public Result<SystemStatusVo> status() {
        return Result.success(systemStatusService.collectStatus());
    }

    /**
     * 访问统计：文章篇数、总浏览量、总点赞数、两个排行榜。
     *
     * @return 统计结果
     */
    @GetMapping("/stats")
    public Result<ArticleStatsVo> stats() {
        return Result.success(systemStatusService.collectStats());
    }
}
