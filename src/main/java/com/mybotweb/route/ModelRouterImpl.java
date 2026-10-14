package com.mybotweb.route;

import com.mybotweb.cloud.LocalNodeManager;
import com.mybotweb.command.CommandNames;
import com.mybotweb.config.AppProperties;
import com.mybotweb.llm.ChatModelClient;
import com.mybotweb.llm.ollama.LocalModelSupervisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 算力路由器实现 —— 按策略和本地节点状态挑客户端。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么两个客户端都要注入，而不是按 {@code app.role} 二选一</b>
 *
 * <p>改造前，{@code OllamaChatModelClient} 和 {@code CloudChatModelClient}
 * 上都标着 {@code @ConditionalOnProperty(app.role)}，属于<b>互斥装配</b>——
 * 启动时只存在一个。那个设计的前提是"用哪个模型由跑在哪台机器决定"，
 * 在当时是对的。
 *
 * <p>但"云端当路由器"这个目标打破了那个前提：<b>云服务器既要能调 API，
 * 又要能接受本地节点连入并把任务派过去</b>。如果启动时就把本地客户端排除掉了，
 * 路由根本没有第二个选项可挑。所以现在两个都装配，由本类在运行期选。
 *
 * <p>这么改不会让没装 Ollama 的机器启动失败——{@code OllamaChatModelClient}
 * 的构造器只创建 HTTP 客户端、不发任何请求，是纯粹的懒探测
 * （真正的探测在 {@code isAvailable()} 里，且带 10 秒缓存）。
 * 这一点我确认过才敢改。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>降级规则（对应四种策略）</b>
 * <table border="1">
 *   <tr><th>策略</th><th>本地在线</th><th>本地离线</th></tr>
 *   <tr><td>{@code LOCAL_FIRST}</td><td>本地</td><td>云端（降级并记日志）</td></tr>
 *   <tr><td>{@code LOCAL_ONLY}</td><td>本地</td><td><b>抛异常</b>，不降级</td></tr>
 *   <tr><td>{@code CLOUD_FIRST}</td><td>云端</td><td>云端</td></tr>
 *   <tr><td>{@code CLOUD_ONLY}</td><td>云端</td><td>云端</td></tr>
 * </table>
 *
 * <p><b>为什么 CLOUD_FIRST 不写成"本地在线就用本地"</b>：那样它和
 * {@code LOCAL_FIRST} 就没区别了。这两个策略的区别正在于
 * <b>本地在线时优先谁</b>——{@code CLOUD_FIRST} 的用途是"本地模型效果不好时
 * 临时切过来"，此时本地明明在线也要走云端。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class ModelRouterImpl implements ModelRouter {

    /** 本地算力客户端（连 WSL 里的 Ollama） */
    private final ChatModelClient localClient;

    /** 云端算力客户端（调 DeepSeek 等 OpenAI 兼容接口） */
    private final ChatModelClient cloudClient;

    /** 本地节点状态中心 */
    private final LocalNodeManager localNodeManager;

    /**
     * 本地模型守护 —— 发现本地 Ollama 不在时负责把它拉起来。
     *
     * <p>它让"WSL 没开导致机器人偷偷用 API"这件事变成自动恢复的：
     * 路由每次判断出本地不可用，就会顺手触发一次拉起尝试（有冷却保护）。
     */
    private final LocalModelSupervisor localModelSupervisor;

    /**
     * 当前策略。用 {@code volatile} 是因为它会被
     * QQ 消息处理线程（{@code /strategy} 命令）写、被其他消息线程读。
     *
     * <p>刻意用"当前值 + 配置默认值"两个来源：启动时从配置读，
     * 运行期可以被命令改。重启后回到配置值——这是有意的，
     * 避免一次临时切换在重启后变成"永久生效但没人记得"。
     */
    private volatile RouteStrategy strategy;

    public ModelRouterImpl(
            @Qualifier("ollamaChatModelClient") ChatModelClient localClient,
            @Qualifier("cloudChatModelClient") ChatModelClient cloudClient,
            LocalNodeManager localNodeManager,
            LocalModelSupervisor localModelSupervisor,
            AppProperties appProperties) {

        this.localClient = localClient;
        this.cloudClient = cloudClient;
        this.localNodeManager = localNodeManager;
        this.localModelSupervisor = localModelSupervisor;

        // 解析配置里的策略字符串。配错了直接启动失败，而不是运行期悄悄用默认值——
        // 后者会让"我明明配了 CLOUD_ONLY 怎么还在花钱"变成一场排查噩梦
        String configured = appProperties.getRoute().getStrategy();
        RouteStrategy parsed = RouteStrategy.fromName(configured);
        if (parsed == null) {
            throw new IllegalStateException(String.format(
                    "app.route.strategy 配置值无法识别：'%s'。可选值：LOCAL_FIRST / CLOUD_FIRST / "
                            + "LOCAL_ONLY / CLOUD_ONLY（大小写和短横线不敏感）", configured));
        }
        this.strategy = parsed;

        // 地址从配置直接读，而不是从客户端要 —— 因为客户端是"算力"的抽象，
        // 它不该负责回答"我连的是哪个地址"这种部署细节。
        // 启动日志里带上地址很值：同时有本地和云端时，只打 ollama / cloud
        // 说明不了问题，而"它到底连哪里"恰恰是出故障时最常要确认的
        String localBaseUrl = appProperties.getLlm().getOllama().getBaseUrl();
        String cloudBaseUrl = appProperties.getLlm().getCloud().getBaseUrl();

        log.info("""

                ================================================================
                算力路由器已就绪
                ----------------------------------------------------------------
                当前策略：{}
                本地算力：{}（{}）
                云端算力：{}（{}）
                本地节点：{}
                ================================================================""",
                parsed.toDisplayText(),
                localClient.getProviderName(), localBaseUrl,
                cloudClient.getProviderName(), cloudBaseUrl,
                localNodeManager.isOnline() ? "在线" : "离线（未接入或未上报心跳）");
    }

    /**
     * 按策略与本地节点状态挑客户端。
     *
     * @param userId 请求者标识，仅用于日志
     * @return 选中的客户端
     * @throws NoAvailableModelException 策略为 {@code LOCAL_ONLY} 且本地离线时
     */
    @Override
    public ChatModelClient chatModelClient(String userId) {
        boolean localOnline = isLocalAvailable();

        switch (this.strategy) {
            case LOCAL_ONLY:
                if (!localOnline) {
                    // 这个策略的语义就是"坚决不花钱"，所以绝不能偷偷降级到云端。
                    // 提示要把两条出路都说清楚，否则用户会以为机器人坏了
                    throw new NoAvailableModelException(
                            "当前路由策略是「仅本地」，但本地节点不在线，且该策略不允许降级到云端。\n"
                                    + "两个办法：\n"
                                    + "  1. 本机执行 " + CommandNames.withSlash(CommandNames.SET_LOCAL)
                                    + " 拉起本地模型服务\n"
                                    // 注意：/strategy 这条命令目前还不存在（见 开发进度.md 的待办清单），
                                    // 所以这里先不引用常量 —— 等命令真做出来再换，免得常量指向一条空命令
                                    + "  2. 管理员执行 /strategy local_first 允许降级");
                }
                log.debug("路由决定：本地（策略 LOCAL_ONLY）");
                return this.localClient;

            case LOCAL_FIRST:
                if (localOnline) {
                    log.debug("路由决定：本地（策略 LOCAL_FIRST，本地在线）");
                    return this.localClient;
                }
                // 降级是预期内的常态（本地电脑关机、WSL 没开），所以用 INFO 而不是 WARN。
                // 用 WARN 的话，长期不在家的人会看到满屏警告
                log.info("路由决定：云端（策略 LOCAL_FIRST，但本地算力不可用，已降级）");
                return this.cloudClient;

            case CLOUD_FIRST:
                // 本地在线也走云端 —— 这正是它和 LOCAL_FIRST 的区别所在
                log.debug("路由决定：云端（策略 CLOUD_FIRST，本地{}）",
                        localOnline ? "在线但按策略仍走云端" : "离线");
                return this.cloudClient;

            case CLOUD_ONLY:
                log.debug("路由决定：云端（策略 CLOUD_ONLY）");
                return this.cloudClient;

            default:
                // 枚举加了新值但这里忘了处理时，宁可走云端也不要静默返回 null
                log.warn("未处理的路由策略 {}，本次按云端处理", this.strategy);
                return this.cloudClient;
        }
    }

    @Override
    public RouteStrategy currentStrategy() {
        return this.strategy;
    }

    @Override
    public void setStrategy(RouteStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("路由策略不能为空");
        }
        RouteStrategy previous = this.strategy;
        this.strategy = strategy;
        log.info("路由策略已修改：{} → {}（发起人通过 /strategy 命令）",
                previous.name(), strategy.name());
    }

    @Override
    public String activeProviderName() {
        boolean localOnline = isLocalAvailable();
        switch (this.strategy) {
            case LOCAL_ONLY:
                return localOnline ? "ollama" : "none";
            case LOCAL_FIRST:
                return localOnline ? "ollama" : "cloud";
            default:
                return "cloud";
        }
    }

    @Override
    public String describeDecision() {
        boolean localOnline = isLocalAvailable();
        // 用"可用"而不是"在线"——这个判断现在有两个来源（本机 Ollama 探测
        // 和本地节点心跳），"在线"只描述了后者，会让人误以为必须接入本地节点
        String localState = localOnline ? "本地可用" : "本地不可用";

        switch (this.strategy) {
            case LOCAL_ONLY:
                return localOnline
                        ? "LOCAL_ONLY → 本地（" + localState + "）"
                        : "LOCAL_ONLY → 无可用算力（" + localState + "，该策略不降级）";
            case LOCAL_FIRST:
                return localOnline
                        ? "LOCAL_FIRST → 本地（" + localState + "）"
                        : "LOCAL_FIRST → 云端（" + localState + "，已降级）";
            case CLOUD_FIRST:
                return "CLOUD_FIRST → 云端（" + localState + "，按策略固定走云端）";
            case CLOUD_ONLY:
                return "CLOUD_ONLY → 云端";
            default:
                return this.strategy.name() + " → 云端";
        }
    }

    @Override
    public boolean isLocalNodeOnline() {
        return isLocalAvailable();
    }

    /**
     * 本地算力当前是否可用 —— <b>这是整个路由判断的核心，改动前它是有 bug 的</b>。
     *
     * <p>═══════════════════════════════════════════════════════════
     * <p><b>★ 原来的写法错在哪</b>
     *
     * <p>改造前这里判断的是 {@code localNodeManager.isOnline()}，也就是
     * "本地节点有没有上报心跳"。但心跳机制是给<b>云端 + 本地节点</b>
     * 那套架构设计的 —— 只有本地程序连到云端、定时发心跳，云端才知道它活着。
     *
     * <p>而项目还有另一种运行方式：<b>本机单机直接跑</b>（{@code app.role: local}），
     * 这时压根没有"节点"这个概念，自然也不会有心跳。
     * 于是 {@code isOnline()} 永远返回 {@code false}，导致：
     *
     * <pre>
     *   你在本地跑，策略是 LOCAL_FIRST，本机 Ollama 明明活着
     *   → 但心跳为 0 → 判定"本地离线" → 降级到云端 API
     *   → 结果是"本地跑却在花 API 的钱"
     * </pre>
     *
     * <p><b>修法：把"本机 Ollama 能通"也算作本地可用。</b>
     * 两个来源任一成立就算可用：
     * <ol>
     *   <li><b>本机直连</b> —— 探测本机 Ollama（{@code /api/tags}），
     *       这是单机模式的判据</li>
     *   <li><b>本地节点心跳</b> —— 云端模式下本地程序连回来上报的心跳</li>
     * </ol>
     * 两者互不冲突：单机模式下第 2 条永远不成立，云端模式下第 1 条不成立
     * （云服务器上没有 Ollama），所以合并判断不会产生歧义。
     *
     * <p><b>代价</b>：{@code localClient.isAvailable()} 会去探测 Ollama。
     * 但它内部有 10 秒缓存（见 {@code OllamaChatModelClient}），
     * 所以不会每条消息都发一次 HTTP 请求。
     *
     * @return 本地算力可用返回 {@code true}
     */
    private boolean isLocalAvailable() {
        boolean localOk = this.localClient.isAvailable()
                || this.localNodeManager.isOnline();

        if (!localOk) {
            // 本地不可用 —— 顺手触发一次自动拉起尝试。
            //
            // 【为什么放在这里而不是起个定时任务】
            // 这条路劲天然就是"随用随检查"：只有在真的要用本地算力时
            // 才会发现它不在，也才需要拉起来。定时任务反而会在
            // "根本没人在用机器人"的时候也去折腾 WSL。
            //
            // ensureStarted() 内部有三道防线（冷却时间、启动中互斥、
            // 平台与开关检查），所以在每条消息上调用它是安全的，
            // 不会变成"每条消息起一个进程"。
            //
            // 注意它是异步的：本次判断的结论仍然是"本地不可用"，
            // 消息照常降级到云端；等它拉起来之后，下一次请求就会走本地。
            this.localModelSupervisor.ensureStarted();
        }

        return localOk;
    }
}
