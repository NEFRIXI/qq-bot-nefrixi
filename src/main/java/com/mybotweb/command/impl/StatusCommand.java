package com.mybotweb.command.impl;

import com.mybotweb.channel.MessageChannel;
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

/**
 * {@code /status} —— 查看系统当前状态。仅管理员可用。
 *
 * <p>对应规格第 7.3 节：{@code /status} 返回当前策略、本地在线状态、
 * 当前模型、今日调用统计。
 *
 * <p>本阶段先实现能实现的部分（角色、通道、模型、可用性），
 * 路由策略和调用统计要等后续阶段，届时在这里补几行即可。
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

    /** 当前生效的消息通道。注入的是接口，具体是哪个实现由配置决定 */
    private final MessageChannel messageChannel;

    /**
     * 算力路由器。
     *
     * <p>注入它而不是 {@code ChatModelClient}：现在本地和云端两个客户端
     * 同时装配（路由器需要两个都看到才能选），所以 {@code ChatModelClient}
     * 这个类型有多个候选 Bean，注入单个会直接启动失败。
     * 而本命令真正想知道的本来也不是"有哪些客户端"，而是
     * "当前策略是什么、这次会走谁"——那正是路由器的职责。
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
                         MessageChannel messageChannel,
                         ModelRouter modelRouter,
                         LocalNodeManager localNodeManager,
                         @Qualifier("ollamaChatModelClient") ChatModelClient ollamaClient,
                         @Qualifier("cloudChatModelClient") ChatModelClient cloudClient) {
        this.appProperties = appProperties;
        this.messageChannel = messageChannel;
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

        StringBuilder sb = new StringBuilder();
        sb.append("系统状态\n");
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("角色　　　：").append(describeRole(appProperties.getRole())).append('\n');
        sb.append("消息通道　：").append(messageChannel.getType()).append('\n');
        sb.append("路由策略　：").append(strategy.toDisplayText()).append('\n');
        sb.append("本次路由　：").append(modelRouter.describeDecision()).append('\n');
        sb.append("本地节点　：").append(describeLocalNode()).append('\n');

        // 按角色显示对应的模型信息。
        // 之前是判断 llm.provider，改成 role 之后语义更清楚：
        // 读到的是"这台机器用哪套算力"，而不是"配置里填了哪个字符串"
        String role = appProperties.getRole();
        if ("cloud".equalsIgnoreCase(role)) {
            sb.append("模型名　　：").append(llmConfig.getCloud().getModel()).append('\n');
            sb.append("接口　　　：").append(llmConfig.getCloud().getBaseUrl()).append('\n');
        } else {
            sb.append("模型名　　：").append(llmConfig.getOllama().getModel()).append('\n');
            sb.append("Ollama　　：").append(llmConfig.getOllama().getBaseUrl()).append('\n');
        }

        // 可用性探测会真的发一次请求（本地探测有 10 秒缓存，云端只查密钥），
        // 所以放在最后算 —— 万一它慢，前面已经拼好的内容不会白等
        sb.append("可用性　　：")
                .append(isActiveProviderAvailable() ? "可用 ✅" : "不可用 ❌")
                .append("（提供方 ").append(modelRouter.activeProviderName()).append("）").append('\n');

        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("当前用户　：").append(user.toLogTag());

        return sb.toString();
    }

    /**
     * 探测"这次会用到的那个算力"是否真的可用。
     *
     * <p><b>为什么不能只查客户端自己的 isAvailable()</b>：
     * 那样回答的是"这个客户端配置对不对"，而不是"这次聊天能不能成"。
     * 比如本地节点在线、策略是 LOCAL_FIRST，那这次走本地——
     * 结果报的是云端的可用性，就答非所问了。
     *
     * <p>所以这里按 {@code activeProviderName()} 的结果去查对应的那个客户端。
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
                    + "，最后心跳 " + (since / 1000) + " 秒前";
        }

        if (localNodeManager instanceof InMemoryLocalNodeManager mem && mem.hasEverConnected()) {
            long last = localNodeManager.lastHeartbeatAt();
            long since = localNodeManager.millisSinceLastHeartbeat();
            return "离线 ❌（曾经连上过，最后心跳 " + (last > 0 ? (since / 1000) + " 秒前" : "未知") + "）";
        }

        return "离线 ❌（从未上报过心跳 —— 本地节点程序还没启动或尚未接入）";
    }

    /**
     * 把配置里的角色值翻译成人能看懂的一句话。
     *
     * @param role 配置值 {@code local} 或 {@code cloud}
     * @return 中文说明
     */
    private String describeRole(String role) {
        if ("cloud".equalsIgnoreCase(role)) {
            return "云端（cloud）— 唯一入口，负责接收全部 QQ 消息";
        }
        return "本地（local）— 本机直连腾讯，本地模型负责闲聊";
    }
}
