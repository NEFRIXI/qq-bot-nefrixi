package com.mybotweb.cloud;

import com.mybotweb.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地节点状态的内存实现 —— 维护"本地算力节点在不在线"。
 *
 * <p><b>它是整个云端/本地路由机制的状态中心</b>：{@code ModelRouter}
 * 每次要决定"这单交给谁"时，都会问它一次 {@link #isOnline()}。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>本阶段实现的边界（重要）</b>
 *
 * <p>这个类现在是<b>完整可用</b>的——心跳、超时判定、状态查询都真实工作。
 * 缺的不是它，而是<b>"谁来调 {@link #onHeartbeat}</b>：
 * 那需要 {@code LocalNodeEndpoint}（{@code /ws/local-agent} 的 WebSocket 端点）
 * 和本机侧的客户端程序，两者都还没实现。
 *
 * <p>所以在本地节点接入之前的实际效果是：
 * {@link #isOnline()} 永远返回 {@code false} —— 因为从未收到过心跳。
 * 这正是我们要的默认行为：路由会认为"本地不可用"，
 * {@code LOCAL_FIRST} 策略于是全部降级到云端，机器人照常工作。
 *
 * <p><b>怎么接上它</b>（将来实现 {@code LocalNodeEndpoint} 时）：
 * <pre>{@code
 * // 收到心跳消息
 * localNodeManager.onHeartbeat(nodeId);
 *
 * // 连接关闭时。注意 graceful 的取值：
 * //   本地主动说"我要关机了" → true
 * //   连接突然断了            → false（不立刻判离线，等心跳超时）
 * localNodeManager.onDisconnect(nodeId, graceful);
 * }</pre>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么只支持单个节点</b>
 *
 * <p>规格里本地节点是"可选算力节点"，语义上只有一个。支持多节点会立刻带来
 * "派给哪个节点""某个节点卡住怎么办"一堆问题，而收益接近零。
 * 真需要时把字段改成 {@code Map<String, Long>} 并给接口方法加 nodeId 参数即可，
 * 接口已经预留了 nodeId，改起来不会伤到调用方。
 *
 * <p><b>状态为什么放内存</b>：单实例部署够用。将来多实例部署时必须挪到 Redis——
 * 否则"消息落在实例 A、本地节点连的是实例 B"，A 会一直认为本地离线。
 * 这是最难查的一类 bug，因为两边日志看着都正常。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class InMemoryLocalNodeManager implements LocalNodeManager {

    /** 路由配置，提供心跳超时阈值 */
    private final AppProperties.Route routeConfig;

    /**
     * 最后一次心跳的时间戳（毫秒）。{@code 0} 表示从未收到过。
     *
     * <p>用 {@link AtomicLong} 而不是 {@code volatile long}：
     * {@code onHeartbeat} 会被 WebSocket 的 IO 线程调用，而 {@code isOnline}
     * 会被消息处理线程调用，两个线程同时读写。虽然 {@code volatile} 也能保证
     * 可见性，但原子类更不容易在后续改动里被误用（比如有人写成 {@code += }）。
     */
    private final AtomicLong lastHeartbeatMillis = new AtomicLong(0L);

    /**
     * 最后一次心跳来自哪个节点。只用于日志和 {@code /status} 展示，
     * 不参与判定——因为只支持单节点，没有"按节点区分"的需求。
     */
    private volatile String lastNodeId;

    /**
     * 是否曾经连上过。用来区分"从未连过"和"连过但断了"这两种离线，
     * 它们的排查方向完全不同：前者是本地程序没启动，后者是网络或崩溃。
     */
    private final AtomicBoolean everConnected = new AtomicBoolean(false);

    public InMemoryLocalNodeManager(AppProperties appProperties) {
        this.routeConfig = appProperties.getRoute();
        log.info("本地节点管理器已初始化（内存实现）：心跳超时阈值 {} 秒，"
                        + "在收到第一次心跳之前，状态一律为「离线」",
                this.routeConfig.getLocalNodeTimeoutSeconds());
    }

    /**
     * 判断本地节点当前是否可用。
     *
     * <p><b>实现必须廉价</b>——每条消息都会调它一次。所以这里只读两个内存字段、
     * 做一次减法，没有任何 IO。这也是为什么心跳超时判定放在这里做
     * （按需计算），而不是起一个定时器定期翻状态位：后者多一个线程，
     * 而且在"刚好处于边界"时会出现两个线程打架。
     *
     * @return 收到过心跳、且距上次心跳未超过阈值时返回 {@code true}
     */
    @Override
    public boolean isOnline() {
        long last = this.lastHeartbeatMillis.get();
        if (last == 0L) {
            // 从未收到过心跳
            return false;
        }
        long timeoutMillis = this.routeConfig.getLocalNodeTimeoutSeconds() * 1000L;
        return (System.currentTimeMillis() - last) <= timeoutMillis;
    }

    /**
     * 记录一次心跳。
     *
     * @param nodeId 节点标识
     */
    @Override
    public void onHeartbeat(String nodeId) {
        boolean wasOnline = isOnline();
        long now = System.currentTimeMillis();
        this.lastHeartbeatMillis.set(now);
        this.lastNodeId = nodeId;

        if (this.everConnected.compareAndSet(false, true)) {
            log.info("本地节点首次上报心跳：nodeId={}，从现在起状态可能变为「在线」", nodeId);
        } else if (!wasOnline) {
            // 之前是离线，现在心跳回来了 → 节点恢复
            // 注意这个判断要在 set 之前算（wasOnline 是在旧值上算的），
            // 所以上面那行先取 wasOnline 再更新时间戳，顺序不能调换
            log.info("本地节点恢复在线：nodeId={}", nodeId);
        }
    }

    /**
     * 记录节点断开。
     *
     * <p><b>主动断开和异常断开的处理必须分开</b>：
     * <ul>
     *   <li><b>主动断开</b>（{@code graceful = true}）——本地程序明确说"我要下线了"。
     *       立刻把心跳时间戳清零，路由马上切换，不让用户白等。</li>
     *   <li><b>异常断开</b>（{@code graceful = false}）——只是连接断了。
     *       <b>什么都不做</b>，等心跳自然超时。因为网络抖动导致的短暂重连
     *       不该触发状态切换——一断就判离线的话，本地节点每次网络抖动都会
     *       让消息被甩去云端，既多花钱又慢。</li>
     * </ul>
     *
     * @param nodeId   节点标识
     * @param graceful 是否主动断开
     */
    @Override
    public void onDisconnect(String nodeId, boolean graceful) {
        if (graceful) {
            log.info("本地节点主动下线：nodeId={}，立即标记为离线", nodeId);
            this.lastHeartbeatMillis.set(0L);
            this.lastNodeId = null;
        } else {
            log.warn("本地节点连接异常断开：nodeId={}，"
                            + "等待心跳超时（{} 秒）后再判定离线",
                    nodeId, this.routeConfig.getLocalNodeTimeoutSeconds());
        }
    }

    /**
     * 取最后一次心跳距今的毫秒数。
     *
     * @return 毫秒数；从未连过返回 {@code -1}
     */
    @Override
    public long millisSinceLastHeartbeat() {
        long last = this.lastHeartbeatMillis.get();
        if (last == 0L) {
            return -1L;
        }
        return System.currentTimeMillis() - last;
    }

    /**
     * 取最后一次心跳的时间戳。
     *
     * @return 时间戳（毫秒）；从未连过返回 {@code 0}
     */
    @Override
    public long lastHeartbeatAt() {
        return this.lastHeartbeatMillis.get();
    }

    /**
     * 取最后一次心跳来自哪个节点，供 {@code /status} 展示。
     *
     * @return 节点标识；从未连过返回 {@code null}
     */
    public String getLastNodeId() {
        return this.lastNodeId;
    }

    /**
     * 是否曾经连上过。用于让 {@code /status} 区分"从未连过"和"连过又断了"。
     *
     * @return 曾经连上过返回 {@code true}
     */
    public boolean hasEverConnected() {
        return this.everConnected.get();
    }
}
