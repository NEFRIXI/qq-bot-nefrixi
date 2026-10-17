package com.mybotweb.command.impl;

import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.cloud.InMemoryLocalNodeManager;
import com.mybotweb.cloud.LocalNodeManager;
import com.mybotweb.command.CommandHandler;
import com.mybotweb.command.CommandNames;
import com.mybotweb.config.AppProperties;
import com.mybotweb.llm.ChatModelClient;
import com.mybotweb.route.ModelRouter;
import com.mybotweb.route.RouteStrategy;
import com.mybotweb.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * {@code /status} —— 查看系统当前状态。仅管理员可用。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>这个命令的输出被精简过一轮，原因记在这里</b>
 *
 * <p>旧版会打印：角色说明、消息通道、路由策略、本次路由、本地节点、
 * 模型名、接口地址、可用性、当前用户，一共十条带分隔线。
 * 实际用下来，其中至少一半是废信息：
 *
 * <ul>
 *   <li><b>角色说明</b>（"本机直连腾讯，本地模型负责闲聊"）—— 固定文案，
 *       第一次看有用，之后每次都占一行</li>
 *   <li><b>消息通道</b>（"qq"）—— 在 QQ 里问这个，答案只可能是 qq</li>
 *   <li><b>本次路由</b> 和 <b>路由策略</b> —— 两行说的是同一件事</li>
 *   <li><b>接口地址</b> —— 内部地址，排查时才需要，不该每次刷出来</li>
 *   <li><b>当前用户</b> —— 自己发的消息，自己知道是谁</li>
 * </ul>
 *
 * <p>砍掉这些之后补上了<b>资源占用</b>：运行时长、堆内存、整机内存。
 * 这几项恰恰是旧版没有、而人真的会想知道的 ——
 * 以前要开网页才能看到，现在在 QQ 里问一句就有。
 *
 * <p>想看点细节的时候仍然去后台 {@code /admin/work/system}，
 * 那里有完整的环形仪表、磁盘和 MySQL/Redis 连通性。
 *
 * <p><b>为什么这条命令只给管理员？</b> 它暴露的是服务端内部结构
 * （用的哪个模型、什么通道、什么角色），对普通用户既没用也没必要知道。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class StatusCommand implements CommandHandler {

    /** 应用配置，用来读角色标记等 */
    private final AppProperties appProperties;

    /**
     * 算力路由器。
     *
     * <p>注入它而不是 {@code ChatModelClient}：现在本地和云端两个客户端
     * 同时装配（路由器需要两个都看到才能选），所以 {@code ChatModelClient}
     * 这个类型有多个候选 Bean，注入单个会直接启动失败。
     */
    private final ModelRouter modelRouter;

    /** 本地节点状态中心，用来报心跳新鲜度 */
    private final LocalNodeManager localNodeManager;

    /**
     * 本地算力客户端，只用于探测可用性。
     *
     * <p>两个客户端现在同时装配，按类型注入会撞上"多个候选 Bean"，
     * 所以必须用 {@code @Qualifier} 指定。
     *
     * <p><b>为什么这里用显式构造器而不是 {@code @RequiredArgsConstructor}</b>：
     * 实测发现 Lombok 不会把字段上的 {@code @Qualifier} 复制到生成构造器的参数上，
     * 于是 Spring 依然看到两个候选而报
     * "expected single matching bean but found 2"。注解必须写在
     * <b>构造器参数</b>上才生效，所以下面手写了构造器。
     */
    private final ChatModelClient ollamaClient;

    /** 云端算力客户端，只用于探测可用性。理由同上 */
    private final ChatModelClient cloudClient;

    public StatusCommand(AppProperties appProperties,
                         ModelRouter modelRouter,
                         LocalNodeManager localNodeManager,
                         @Qualifier("ollamaChatModelClient") ChatModelClient ollamaClient,
                         @Qualifier("cloudChatModelClient") ChatModelClient cloudClient) {
        this.appProperties = appProperties;
        this.modelRouter = modelRouter;
        this.localNodeManager = localNodeManager;
        this.ollamaClient = ollamaClient;
        this.cloudClient = cloudClient;
    }

    @Override
    public String getName() {
        return CommandNames.STATUS;
    }

    @Override
    public String getDescription() {
        return "查看系统状态";
    }

    /** 仅管理员 */
    @Override
    public boolean isAdminOnly() {
        return true;
    }

    /**
     * 汇总当前状态。
     *
     * <p>{@code isAvailable()} 会真去探测一次本地模型是否在线，
     * 所以这个命令可能耗时一两秒（探测有超时）。
     *
     * @param message 入站消息
     * @param user    当前用户（已确认是管理员）
     * @param args    未使用
     * @return 状态文本
     */
    @Override
    public String execute(QqMessage message, UserContext user, String[] args) {
        AppProperties.Llm llmConfig = appProperties.getLlm();
        RouteStrategy strategy = modelRouter.currentStrategy();

        boolean cloud = "cloud".equalsIgnoreCase(appProperties.getRole());
        String modelName = cloud
                ? llmConfig.getCloud().getModel()
                : llmConfig.getOllama().getModel();

        StringBuilder sb = new StringBuilder();
        sb.append("系统状态\n");

        sb.append("模型　　：").append(modelName)
                .append(isActiveProviderAvailable() ? " ✅" : " ❌ 不可用").append('\n');

        sb.append("路由　　：").append(strategy.toDisplayText()).append('\n');

        sb.append("本地节点：").append(describeLocalNode()).append('\n');

        // 放在最后：前面几项都是瞬时查询，这一项要做一次
        // getCpuLoad / 读取 meminfo，万一慢，已经拼好的内容不会白等
        sb.append("资源　　：").append(describeResources());

        return sb.toString();
    }

    /**
     * 资源占用：运行时长、堆内存、整机内存。
     *
     * <p>这里直接读 JVM 的 MXBean，<b>不注入 SystemStatusService</b>。
     * 原因是那个 Service 依赖 MySQL 数据源和 Redis 模板 ——
     * 博客功能关闭时这些 Bean 并不存在，而 {@code /status} 是
     * QQ 侧的命令，跟博客开关没有关系，不该被它连累到启动失败。
     *
     * @return 一行可读文本，如 {@code 2 天 5 小时 · 堆 128/512 MB · 内存 42%}
     */
    private String describeResources() {
        StringBuilder sb = new StringBuilder();

        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
        sb.append(formatUptime(uptimeSeconds));

        Runtime runtime = Runtime.getRuntime();
        long heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long heapMaxMb = runtime.maxMemory() / (1024L * 1024L);
        sb.append(" · 堆 ").append(heapUsedMb).append('/').append(heapMaxMb).append(" MB");

        // 整机内存。取不到就不显示这一截，而不是硬报一个 0%
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
            long total = sunOsBean.getTotalMemorySize();
            long free = sunOsBean.getFreeMemorySize();
            if (total > 0) {
                long percent = Math.round((total - free) * 100.0 / total);
                sb.append(" · 内存 ").append(percent).append('%');
            }
        }

        return sb.toString();
    }

    /**
     * 把秒数写成"几天几小时几分"。
     *
     * @param seconds 秒
     * @return 可读文本
     */
    private static String formatUptime(long seconds) {
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
     * 探测"这次会用到的那个算力"是否真的可用。
     *
     * <p><b>为什么不能只查客户端自己的 isAvailable()</b>：
     * 那样回答的是"这个客户端配置对不对"，而不是"这次聊天能不能成"。
     * 比如本地节点在线、策略是 LOCAL_FIRST，那这次走本地——
     * 结果报的是云端的可用性，就答非所问了。
     *
     * @return 可用返回 {@code true}
     */
    private boolean isActiveProviderAvailable() {
        String active = modelRouter.activeProviderName();
        if ("ollama".equalsIgnoreCase(active)) {
            return ollamaClient.isAvailable();
        }
        if ("cloud".equalsIgnoreCase(active)) {
            return cloudClient.isAvailable();
        }
        // "none" —— 策略是 LOCAL_ONLY 且本地离线，压根没有可用算力
        return false;
    }

    /**
     * 把本地节点的状态描述成一行。
     *
     * <p>三种状态要能区分开，因为它们的排查方向完全不同：
     * <ul>
     *   <li><b>在线</b> —— 正常，报最后心跳距今多久</li>
     *   <li><b>曾经连过但断了</b> —— 网络问题或本地程序崩了，去看本地日志</li>
     *   <li><b>从未连过</b> —— 本地程序根本没启动，或本地节点功能还没接入</li>
     * </ul>
     *
     * @return 描述文本
     */
    private String describeLocalNode() {
        if (localNodeManager.isOnline()) {
            long since = localNodeManager.millisSinceLastHeartbeat();
            String nodeId = (localNodeManager instanceof InMemoryLocalNodeManager mem)
                    ? mem.getLastNodeId() : null;
            return "在线 ✅" + (nodeId == null ? "" : "（" + nodeId + "）")
                    + "，心跳 " + (since / 1000) + " 秒前";
        }

        if (localNodeManager instanceof InMemoryLocalNodeManager mem && mem.hasEverConnected()) {
            long since = localNodeManager.millisSinceLastHeartbeat();
            return "离线 ❌（曾经连上过，最后心跳 " + (since / 1000) + " 秒前）";
        }

        return "离线 ❌（从未上报心跳）";
    }
}
