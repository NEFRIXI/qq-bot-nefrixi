package com.mybotweb.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.channel.model.QqScene;
import com.mybotweb.common.util.ProjectPaths;
import com.mybotweb.config.AppProperties;
import com.mybotweb.dispatcher.MessageDispatcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QQ WebSocket 网关客户端 —— 接收 QQ 消息的入口。
 *
 * <p><b>这是"把对话拉起来"的那根线。</b> 它做四件事：
 * <ol>
 *   <li>向腾讯要一个网关地址（{@code GET /gateway}）</li>
 *   <li>主动连上去（{@code wss://api.bot.qq.com/websocket/}）并鉴权</li>
 *   <li>定期发心跳保活，收到事件就交给 {@code MessageDispatcher}</li>
 *   <li>断线自动重连</li>
 * </ol>
 *
 * <p><b>为什么是"我们连它"而不是"它连我们"？</b> 因为本地电脑没有公网 IP，
 * 腾讯 POST 不进来。而"我们连出去"这一下就绕开了这个问题——
 * WebSocket 是全双工的，连上之后腾讯照样能把消息推给我们。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>协议要点（都是官方文档里的，不是猜的）</b>
 *
 * <p>每个上下行消息都是这个结构：
 * <pre>{@code { "id": "...", "op": 0, "d": {...}, "s": 42, "t": "事件名" } }</pre>
 *
 * <table border="1">
 *   <tr><th>op</th><th>名称</th><th>方向</th><th>含义</th></tr>
 *   <tr><td>0</td><td>Dispatch</td><td>收</td><td>服务端推送事件</td></tr>
 *   <tr><td>1</td><td>Heartbeat</td><td>发</td><td>心跳，{@code d} 是收到的最新 {@code s}</td></tr>
 *   <tr><td>2</td><td>Identify</td><td>发</td><td>鉴权</td></tr>
 *   <tr><td>6</td><td>Resume</td><td>发</td><td>恢复会话（补发断线期间漏掉的事件）</td></tr>
 *   <tr><td>7</td><td>Reconnect</td><td>收</td><td>服务端要求重连</td></tr>
 *   <tr><td>9</td><td>Invalid Session</td><td>收</td><td>鉴权或恢复失败</td></tr>
 *   <tr><td>10</td><td>Hello</td><td>收</td><td>连接后第一条，给出心跳周期</td></tr>
 *   <tr><td>11</td><td>Heartbeat ACK</td><td>收</td><td>心跳被确认</td></tr>
 * </table>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>两个必须这么写的地方（否则会出很难查的问题）</b>
 *
 * <p><b>1. 事件处理必须异步。</b> WebSocket 的 {@code onText} 回调运行在
 * JDK 的网络线程上。而处理一条消息要调大模型，本地 4B 模型可能要跑几十秒。
 * 如果在回调里直接处理，那个线程会被占死，<b>期间所有后续消息都收不到</b>，
 * 表现为"机器人在忙的时候完全装死"。所以这里收到事件后立刻丢给线程池。
 *
 * <p><b>2. 必须调用 {@code request(1)}。</b> JDK 的 WebSocket 用拉模式控制流量，
 * 处理完一条要显式告诉它"可以再来一条"，否则只会收到第一条，
 * 之后永远安静。这是用 JDK 原生 WebSocket 最容易踩的坑。
 *
 * <p>装配条件：{@code app.channel.type: qq}。用 mock 通道时本类不生效。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.channel.type", havingValue = "qq")
public class QqGatewayClient {

    /** Hello 里没给心跳周期时的兜底值（毫秒），取自官方文档示例 45000 */
    private static final int DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 45_000;

    /** 首次连接失败后的重试间隔（秒） */
    private static final int INITIAL_RETRY_SECONDS = 5;

    /** 重试间隔上限（秒），避免无限增长 */
    private static final int MAX_RETRY_SECONDS = 60;

    /**
     * 连续失败多少次后进入冷却。
     *
     * <p>5 次是有意的：偶发的网络抖动一般一两次就恢复了，
     * 连着 5 次说明基本可以排除"偶然"，是配置或权限问题。
     */
    private static final int FAILURE_THRESHOLD = 5;

    /**
     * 冷却期的重连间隔（秒）。
     *
     * <p>拉长到 5 分钟是<b>刻意保留自愈能力</b>而不是直接放弃重连：
     * 万一真的是腾讯侧短时故障，过几分钟自己就好了；
     * 但也不能像之前那样每 5 秒一次——那会把日志刷爆，
     * 让人根本看不到真正有用的那几行。
     */
    private static final int COOLDOWN_SECONDS = 300;

    /** 处理消息的线程数。消息处理要调模型，给几个线程避免完全串行 */
    private static final int MESSAGE_THREAD_COUNT = 4;

    /** QQ 配置 */
    private final AppProperties.Qq config;

    /** OpenAPI 客户端，用来取网关地址 */
    private final QqApiClient apiClient;

    /** 凭证管理器，Identify 时要用 */
    private final QqAccessTokenManager tokenManager;

    /** 消息分发器，收到事件后交给它 */
    private final MessageDispatcher messageDispatcher;

    /** JSON 处理 */
    private final ObjectMapper objectMapper;

    /** 当前 WebSocket 连接。用 volatile 是因为会被重连线程替换 */
    private volatile WebSocket webSocket;

    /** 收到的最新事件序列号 {@code s}。心跳和 Resume 都要带它 */
    private volatile int lastSequence = -1;

    /** 会话 ID，来自 READY 事件。有了它才能用 Resume 优雅重连 */
    private volatile String sessionId;

    /** 是否正在运行。{@code @PreDestroy} 时置为 false 让重连循环退出 */
    private volatile boolean running = true;

    /**
     * 是否已经有重连被安排了。
     *
     * <p>用途：{@code onClose} 和 {@code onError} 可能先后触发，
     * 不加这个标记会安排两次重连，导致两个连接互相挤掉。
     */
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    /** 连续重试次数，用于计算退避间隔；连上之后归零 */
    private final AtomicInteger retryCount = new AtomicInteger(0);

    /**
     * 连续失败次数 —— 连接建立后很快又被关掉、或者鉴权被拒，都算一次。
     *
     * <p>用途是做<b>熔断</b>：连续失败到一定次数，说明多半不是网络抖动，
     * 而是配置或权限问题。这时不该继续每 5 秒撞一次，
     * 而应该放慢节奏、并把排查方向说清楚。
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /**
     * 最近一次发出的鉴权动作，取值 {@code IDENTIFY} 或 {@code RESUME}。
     *
     * <p>用途是解读服务端回的 {@code op=9 Invalid Session}。官方文档对这个错误
     * 的说明是"当 identify 或 resume 的时候，如果参数有错，服务端会返回该消息"，
     * 也就是说它有两种完全不同的含义：
     * <ul>
     *   <li>{@code RESUME} 之后收到 —— 正常情况，会话过期了，重新 Identify 即可</li>
     *   <li>{@code IDENTIFY} 之后收到 —— <b>参数真的有问题</b>（最典型就是
     *       intents 权限不足），盲目重试一万次也不会成功</li>
     * </ul>
     * 不区分这两种情况的话，真正的配置错误会被淹没在"会话无效，将重新鉴权"
     * 这种听起来很正常的日志里。
     */
    private volatile String lastAuthAction;

    /** 排查提示是否已经打印过，避免日志被同一段话反复刷屏 */
    private final AtomicBoolean troubleshootingPrinted = new AtomicBoolean(false);

    /** 心跳定时器 */
    private ScheduledExecutorService heartbeatScheduler;

    /** 重连定时器 */
    private ScheduledExecutorService reconnectScheduler;

    /** 消息处理线程池 */
    private ExecutorService messageExecutor;

    public QqGatewayClient(AppProperties appProperties,
                           QqApiClient apiClient,
                           QqAccessTokenManager tokenManager,
                           MessageDispatcher messageDispatcher,
                           ObjectMapper objectMapper) {
        this.config = appProperties.getQq();
        this.apiClient = apiClient;
        this.tokenManager = tokenManager;
        this.messageDispatcher = messageDispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * 应用启动后开始连接。
     *
     * <p>刻意<b>在独立线程里连</b>，而不是在 {@code @PostConstruct} 里同步连完：
     * 如果腾讯网络不通（或者 AppSecret 没配），同步连接会让整个应用启动卡住甚至失败。
     * 异步连接的话，应用照常起来，Web 接口照常可用，
     * 只是日志里会持续提示"连不上 QQ"，问题一目了然。
     *
     * <p>本方法由 Spring 自动调用。
     */
    @PostConstruct
    public void start() {
        // 没有 AppSecret 就连不了，明确提示一次，不要反复重试刷屏
        if (config.getAppSecret() == null || config.getAppSecret().isBlank()) {
            log.error("""
                    
                    ================================================================
                    QQ 通道未启动：AppSecret 还没配置
                    ================================================================
                    1. 去 QQ 开放平台 → 你的机器人 → 开发管理，复制 AppSecret
                    
                    2. 打开这个文件（用记事本或 IDEA 都行）：
                       {}
                    
                    3. 找到 app.qq.app-secret，把复制的值粘到两个引号中间：
                           app-secret: "这里"
                    
                    4. 重启应用
                    ================================================================""",
                    ProjectPaths.localConfigFile());
            return;
        }

        initExecutors();

        Thread starter = new Thread(this::connectWithRetry, "qq-gateway-starter");
        starter.setDaemon(true);
        starter.start();

        log.info("QQ 网关客户端已启动，正在连接...（AppID={}，intents={}）",
                config.getAppId(), config.getIntents());
    }

    /**
     * 应用关闭时清理资源。
     *
     * <p>这几步都不能省，否则进程可能因为非守护线程还活着而无法退出。
     */
    @PreDestroy
    public void stop() {
        log.info("正在关闭 QQ 网关客户端...");
        this.running = false;

        shutdownScheduler(this.heartbeatScheduler, "心跳");
        shutdownScheduler(this.reconnectScheduler, "重连");

        WebSocket ws = this.webSocket;
        if (ws != null) {
            try {
                // 主动关闭时发正常关闭码，让腾讯知道是我们自己断的，
                // 而不是网络故障——这样它不会把这次断开当成异常
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "应用关闭").join();
            } catch (Exception e) {
                log.debug("关闭 WebSocket 时出错（可忽略）：{}", e.getMessage());
            }
            this.webSocket = null;
        }

        if (this.messageExecutor != null) {
            this.messageExecutor.shutdownNow();
        }
        log.info("QQ 网关客户端已关闭");
    }

    /**
     * 创建三个线程池。
     *
     * <p>分开建是因为它们的用途和生命周期完全不同：
     * <ul>
     *   <li>心跳：单线程定时，跟随连接生命周期</li>
     *   <li>重连：单线程定时，一次只安排一个重连</li>
     *   <li>消息处理：多线程，每个任务可能跑几十秒（调模型）</li>
     * </ul>
     */
    private void initExecutors() {
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                daemonThreadFactory("qq-heartbeat"));
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(
                daemonThreadFactory("qq-reconnect"));
        this.messageExecutor = Executors.newFixedThreadPool(
                MESSAGE_THREAD_COUNT, daemonThreadFactory("qq-message"));
    }

    /**
     * 创建一个命名清晰的守护线程工厂。
     *
     * <p>线程名很重要：出问题时看线程转储（jstack），
     * 一眼就能看出是哪个组件卡住了。默认的 {@code pool-1-thread-3} 毫无信息量。
     *
     * <p>用守护线程是为了不让这些线程阻止 JVM 退出。
     *
     * @param namePrefix 线程名前缀
     * @return 线程工厂
     */
    private ThreadFactory daemonThreadFactory(String namePrefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ========================================================
    // 连接与重连
    // ========================================================

    /**
     * 反复尝试建立连接，直到成功。
     *
     * <p>退避策略：5 秒、10 秒、20 秒……最多 60 秒。
     * 用退避而不是固定间隔，是为了在腾讯侧故障时不至于每 5 秒去撞一次。
     *
     * <p>连上之后本方法就返回了，后续的连接维护交给 Listener 的回调。
     */
    private void connectWithRetry() {
        while (this.running) {
            try {
                doConnect();
                return;

            } catch (Exception e) {
                int attempt = this.retryCount.incrementAndGet();
                // 退避间隔翻倍增长，但不超过上限
                int delaySeconds = Math.min(
                        INITIAL_RETRY_SECONDS * (1 << Math.min(attempt - 1, 4)),
                        MAX_RETRY_SECONDS);

                log.error("连接 QQ 网关失败（第 {} 次尝试）：{}。{} 秒后重试",
                        attempt, e.getMessage(), delaySeconds);

                try {
                    TimeUnit.SECONDS.sleep(delaySeconds);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 真正建立一次连接。
     *
     * @throws Exception 取地址或连接失败时抛出
     */
    private void doConnect() throws Exception {
        String gatewayUrl = apiClient.getGatewayUrl();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        // buildAsync 返回 CompletableFuture，join 等它连上。
        // 连不上会抛 CompletionException，由上面的重试循环接住。
        //
        // 连接对象不在这里接：GatewayListener.onOpen 已经把它存进字段了，
        // 而且必须由它来存（原因见 onOpen 里的注释 —— join() 返回时
        // 服务端的 Hello 可能已经在处理了）。
        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(gatewayUrl), new GatewayListener())
                .join();

        // 这里不能再写一次 this.webSocket = ws：万一连接刚建好就被服务端
        // 关掉，scheduleReconnect() 已经把字段置成 null，而本方法紧接着
        // 返回、又把那个已失效的连接赋回去 —— 之后 sendText 就会拿着一个
        // 关掉的连接去发消息，报出一堆看不懂的错。
        this.retryCount.set(0);
        log.info("WebSocket 连接已建立：{}", gatewayUrl);
    }

    /**
     * 安排一次重连。
     *
     * <p>{@code onClose} 和 {@code onError} 都可能触发本方法，
     * 用 {@link #reconnectScheduled} 保证只安排一次。
     */
    private void scheduleReconnect(String reason) {
        if (!this.running) {
            return;
        }
        if (!this.reconnectScheduled.compareAndSet(false, true)) {
            log.debug("已有重连在安排中，跳过（原因：{}）", reason);
            return;
        }

        stopHeartbeat();
        this.webSocket = null;

        // "连接被关闭"本身也算一次失败。
        // 这一点很关键：intents 没有权限时，腾讯的表现恰恰是
        // "接受连接、然后立刻关掉"，而不是在取网关地址那步报错。
        // 如果不把它计入失败，这个死循环就永远不会触发熔断。
        int failures = this.consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD
                && this.troubleshootingPrinted.compareAndSet(false, true)) {
            printTroubleshooting("连续 " + failures + " 次连接失败或被关闭");
        }

        // 重连要重新走一遍取地址流程，因为地址可能变了；
        // 同时用新的 Listener 实例，避免复用带脏状态的对象
        int delaySeconds = Math.min(INITIAL_RETRY_SECONDS * (1 + retryCount.get()),
                MAX_RETRY_SECONDS);

        // 熔断后把间隔拉长，不再每几秒撞一次
        if (failures >= FAILURE_THRESHOLD) {
            delaySeconds = COOLDOWN_SECONDS;
        }

        log.warn("{}，{} 秒后重连（连续失败 {} 次）", reason, delaySeconds, failures);

        this.reconnectScheduler.schedule(() -> {
            this.reconnectScheduled.set(false);
            try {
                doConnect();
            } catch (Exception e) {
                log.error("重连失败：{}", e.getMessage());
                // 失败就再来一次，交给退避逻辑
                this.retryCount.incrementAndGet();
                scheduleReconnect("重连失败");
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    // ========================================================
    // WebSocket 回调：每次连接一个实例
    // ========================================================

    /**
     * WebSocket 事件监听器。
     *
     * <p>为什么做成<b>内部类而不是让 {@code QqGatewayClient} 直接实现</b>？
     * 因为它带状态（{@code textBuffer} 用于拼接分片消息），
     * 而每次重连都会建一个新连接。如果共用同一个实例，
     * 上一次连接残留的半个消息会污染下一次——这种 bug 极难查。
     * 每个连接一个全新实例就没有这个问题。
     */
    private class GatewayListener implements WebSocket.Listener {

        /**
         * 分片消息的缓冲。
         *
         * <p>WebSocket 协议允许把一条大消息拆成多帧发。
         * {@code onText} 的 {@code last} 参数为 {@code false} 时表示还有后续帧，
         * 这时必须把内容攒起来，等 {@code last=true} 才是一个完整的 JSON。
         * 不处理分片的话，遇到长消息会解析失败。
         */
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            // ★ 连接必须在这里就存下来，不能只靠 doConnect() 里那行赋值。
            //
            // 【不这么做会怎样】buildAsync().join() 要等本方法跑完才返回，
            // 所以 doConnect() 里的 this.webSocket = ws 一定发生在本方法之后。
            // 而 join() 一返回，同一个 IO 线程马上就会送达服务端的 Hello
            // （op=10），并在线程池里回调：
            //     onText → handlePayload → handleHello → sendIdentify → sendText
            // 这一串全在 IO 线程上同步跑完，主线程此时还没走出 join()，
            // 于是 sendText 读到的 webSocket 仍是 null，抛
            // "WebSocket 还没连接，无法发送"。
            //
            // 外在表现：首次连接必定失败一次，要等 5 秒后重连才连得上。
            // 因为是纯时序，偶尔看着又是好的 —— 这种 bug 最难查。
            // 放在这里赋值就没有窗口期了：协议保证 onOpen 一定早于 onText。
            QqGatewayClient.this.webSocket = ws;
            log.info("WebSocket 已连接，等待 Hello（op=10）");
            // 告诉底层"我准备好收一条消息了"。这个 request 机制是必须的，
            // 忘了它就会只收到第一条然后永远安静
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            this.textBuffer.append(data);

            if (last) {
                String payload = this.textBuffer.toString();
                this.textBuffer.setLength(0);
                handlePayload(payload);
            }

            // 处理完这一条，再要下一条
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.warn("WebSocket 连接被关闭：code={}，reason={}", statusCode, reason);
            scheduleReconnect("连接被关闭（code=" + statusCode + "）");
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("WebSocket 出错：{}", error.getMessage());
            scheduleReconnect("连接出错");
        }
    }

    // ========================================================
    // 协议处理
    // ========================================================

    /**
     * 处理一条完整的网关消息。
     *
     * <p>按 {@code op} 分派。注意本方法运行在网络线程上，
     * 所以<b>每一件事都必须很快</b>——耗时的消息处理会转交线程池。
     *
     * @param payload 原始 JSON
     */
    private void handlePayload(String payload) {
        try {
            JsonNode root = this.objectMapper.readTree(payload);
            int op = root.path("op").asInt(-1);

            switch (op) {
                case 10 -> handleHello(root);
                case 0 -> handleDispatch(root);
                case 11 -> log.debug("收到心跳确认（op=11）");
                case 7 -> {
                    log.info("服务端要求重连（op=7）");
                    scheduleReconnect("服务端要求重连");
                }
                case 9 -> {
                    // 官方文档对这个错误的说明是"当 identify 或 resume 的时候，
                    // 如果参数有错，服务端会返回该消息"。所以它有两种含义，
                    // 必须结合"上一次发的是什么"才能判断
                    boolean identifyRejected = "IDENTIFY".equals(this.lastAuthAction);
                    log.warn("收到 Invalid Session（op=9），上一次动作是 {}",
                            this.lastAuthAction == null ? "未知" : this.lastAuthAction);

                    // 无论哪种情况，session 都已经无效，必须清掉；
                    // 否则下次又会拿着它去 Resume，然后再次失败，形成死循环
                    this.sessionId = null;
                    this.lastSequence = -1;

                    if (identifyRejected) {
                        // Identify 就被拒 = 参数真的有问题，最典型的原因
                        // 就是 intents 里的位没有订阅权限
                        this.consecutiveFailures.incrementAndGet();
                        printTroubleshooting("鉴权请求被服务端拒绝（op=9）");
                    }
                    scheduleReconnect("会话无效（op=9）");
                }
                default -> log.debug("未处理的 op={}，原始内容：{}", op, truncate(payload));
            }
        } catch (Exception e) {
            log.error("解析网关消息失败：{}，原始内容：{}", e.getMessage(), truncate(payload), e);
        }
    }

    /**
     * 处理 Hello（op=10）：拿到心跳周期，然后发鉴权。
     *
     * @param root 消息根节点
     */
    private void handleHello(JsonNode root) {
        int heartbeatInterval = root.path("d").path("heartbeat_interval")
                .asInt(DEFAULT_HEARTBEAT_INTERVAL_MILLIS);

        log.info("收到 Hello，心跳周期 {} 毫秒", heartbeatInterval);

        startHeartbeat(heartbeatInterval);
        sendIdentifyOrResume();
    }

    /**
     * 处理 Dispatch（op=0）：真正的业务事件都在这里。
     *
     * @param root 消息根节点
     */
    private void handleDispatch(JsonNode root) {
        // 记录序列号：心跳要带它，Resume 也要用它来补发
        int sequence = root.path("s").asInt(-1);
        if (sequence > 0) {
            this.lastSequence = sequence;
        }

        String eventType = root.path("t").asText("");
        JsonNode data = root.path("d");

        switch (eventType) {
            case "READY" -> {
                this.sessionId = data.path("session_id").asText(null);
                // 鉴权成功，说明之前的失败是暂时性的，计数归零
                this.consecutiveFailures.set(0);
                this.troubleshootingPrinted.set(false);
                String botName = data.path("user").path("username").asText("未知");
                log.info("""
                        
                        ╔════════════════════════════════════════════════════════════╗
                        ║  鉴权成功，机器人在线！                                     ║
                        ║  机器人昵称：{}
                        ║  session_id：{}
                        ║  现在可以在 QQ 里给它发消息了。                             ║
                        ╚════════════════════════════════════════════════════════════╝""",
                        botName, this.sessionId);
            }

            case "RESUMED" -> log.info("会话已恢复（断线期间的事件已补发）");

            case "C2C_MESSAGE_CREATE" -> {
                log.debug("收到单聊消息事件");
                submitMessage(parseC2cMessage(data));
            }

            case "GROUP_AT_MESSAGE_CREATE" -> {
                log.debug("收到群聊@消息事件");
                // mentionedBot = true：人家点名了，必须回
                submitMessage(parseGroupMessage(data, true));
            }

            case "GROUP_MESSAGE_CREATE" -> {
                // 全量模式：群里<b>每一条</b>消息（不限于 @ 机器人）。
                //
                // 这个事件只有在 QQ 开放平台开启了「接收所有消息」之后才会推过来。
                // 没开的话这一支永远不会执行 —— 不是代码错，是平台开关没开。
                //
                // 注意 mentionedBot = false：这条消息<b>不该被直接回复</b>。
                // 它只会进热聊的上下文缓冲，由热聊模式判断要不要插话。
                // 如果这里传了 true，开了全量模式之后机器人会把整个群刷爆
                log.debug("收到群聊消息（全量模式，未@机器人）");
                submitMessage(parseGroupMessage(data, false));
            }

            // 下面这些是"用户关系"类事件，先记日志，将来可以做成"欢迎语"
            case "FRIEND_ADD" -> log.info("有用户添加了机器人");
            case "FRIEND_DEL" -> log.info("有用户删除了机器人");
            case "GROUP_ADD_ROBOT" -> log.info("机器人被拉进了一个群");
            case "GROUP_DEL_ROBOT" -> log.info("机器人被移出了一个群");
            case "C2C_MSG_REJECT" -> log.info("有用户关闭了主动消息接收");
            case "C2C_MSG_RECEIVE" -> log.info("有用户开启了主动消息接收");

            default -> log.debug("未处理的事件类型：{}", eventType);
        }
    }

    /**
     * 把单聊事件的 {@code d} 字段解析成内部消息模型。
     *
     * @param data 事件的 d 字段
     * @return 内部消息模型
     */
    private QqMessage parseC2cMessage(JsonNode data) {
        QqMessage message = new QqMessage();
        message.setScene(QqScene.C2C);
        message.setMsgId(data.path("id").asText(null));
        message.setContent(data.path("content").asText(""));
        // 单聊的发送者标识在 author.user_openid，
        // 注意它不是 QQ 号，是腾讯给这个机器人专属的匿名 ID
        message.setUserId(data.path("author").path("user_openid").asText(null));
        // 单聊本来就是直接对机器人说的，恒为 true
        message.setMentionedBot(true);
        message.setMsgSeq(1);
        message.setReceivedAtMillis(System.currentTimeMillis());
        message.setRawJson(data.toString());
        return message;
    }

    /**
     * 把群聊事件的 {@code d} 字段解析成内部消息模型。
     *
     * @param data         事件的 d 字段
     * @param mentionedBot 是否 @ 了机器人。
     *                     {@code GROUP_AT_MESSAGE_CREATE} 传 {@code true}，
     *                     {@code GROUP_MESSAGE_CREATE}（全量模式）传 {@code false}。
     *                     这个值决定了这条消息会不会被直接回复，见
     *                     {@link QqMessage#isMentionedBot()}
     * @return 内部消息模型
     */
    private QqMessage parseGroupMessage(JsonNode data, boolean mentionedBot) {
        QqMessage message = new QqMessage();
        message.setScene(QqScene.GROUP);
        message.setMsgId(data.path("id").asText(null));
        message.setContent(data.path("content").asText(""));
        // 群聊的发送者在 author.member_openid，群标识在 group_openid
        message.setUserId(data.path("author").path("member_openid").asText(null));
        message.setGroupId(data.path("group_openid").asText(null));
        message.setMentionedBot(mentionedBot);
        message.setMsgSeq(1);
        message.setReceivedAtMillis(System.currentTimeMillis());
        message.setRawJson(data.toString());
        return message;
    }

    /**
     * 把消息提交给线程池处理。
     *
     * <p>这是"不阻塞网络线程"的关键一步。处理一条消息要调大模型，
     * 可能几十秒，绝不能占着网络线程。
     *
     * @param message 待处理的消息
     */
    private void submitMessage(QqMessage message) {
        if (message.getUserId() == null || message.getUserId().isBlank()) {
            log.warn("事件里没有解析出用户标识，已忽略。原始内容：{}",
                    truncate(message.getRawJson()));
            return;
        }

        this.messageExecutor.submit(() -> {
            try {
                // 交给统一入口，走的是和调试接口完全一样的逻辑
                messageDispatcher.handle(message);
            } catch (Exception e) {
                // 兜底：线程池里的异常如果不接住，任务会静默死掉
                log.error("处理 QQ 消息时发生未预期的异常", e);
            }
        });
    }

    // ========================================================
    // 鉴权、心跳、发送
    // ========================================================

    /**
     * 发送鉴权或恢复会话。
     *
     * <p>优先用 Resume：如果之前拿到过 {@code session_id} 和 {@code seq}，
     * 用 Resume 可以让腾讯<b>补发断线期间漏掉的事件</b>。
     * 没有这两个信息（首次连接、或者会话已失效）就只能重新 Identify，
     * 代价是断线那段时间的消息会丢。
     *
     * <p>对应规格第 6.5 节"按用户/场景存上下文"的设计无关，但和
     * "消息不丢"这个目标直接相关——所以能用 Resume 就用。
     */
    private void sendIdentifyOrResume() {
        if (this.sessionId != null && this.lastSequence >= 0) {
            sendResume();
        } else {
            sendIdentify();
        }
    }

    /**
     * 发送 Identify（op=2）——首次鉴权。
     */
    private void sendIdentify() {
        try {
            ObjectNode data = this.objectMapper.createObjectNode();
            // token 的格式官方有明确规定："QQBot {AccessToken}"，中间一个空格
            data.put("token", "QQBot " + tokenManager.getToken());
            data.put("intents", config.getIntents());

            // 分片参数 [shard_id, shard_count]。
            // [0, 1] 表示"不分片，这一条连接收全部事件"，个人机器人就该这么写
            data.putArray("shard").add(0).add(1);

            // properties 官方说"目前无实际作用，可以留空"，填上只是为了日志好认
            ObjectNode properties = data.putObject("properties");
            properties.put("$os", System.getProperty("os.name", "unknown"));
            properties.put("$browser", "mybotweb");
            properties.put("$device", "mybotweb");

            ObjectNode payload = this.objectMapper.createObjectNode();
            payload.put("op", 2);
            payload.set("d", data);

            this.lastAuthAction = "IDENTIFY";
            sendText(payload.toString());
            log.info("已发送鉴权请求（op=2，intents={}）", config.getIntents());

        } catch (Exception e) {
            log.error("发送鉴权请求失败：{}", e.getMessage(), e);
            scheduleReconnect("鉴权发送失败");
        }
    }

    /**
     * 发送 Resume（op=6）——断线重连时恢复会话。
     */
    private void sendResume() {
        try {
            ObjectNode data = this.objectMapper.createObjectNode();
            data.put("token", "QQBot " + tokenManager.getToken());
            data.put("session_id", this.sessionId);
            data.put("seq", this.lastSequence);

            ObjectNode payload = this.objectMapper.createObjectNode();
            payload.put("op", 6);
            payload.set("d", data);

            this.lastAuthAction = "RESUME";
            sendText(payload.toString());
            log.info("已发送会话恢复请求（op=6，seq={}）", this.lastSequence);

        } catch (Exception e) {
            log.error("发送会话恢复请求失败：{}", e.getMessage(), e);
            scheduleReconnect("恢复发送失败");
        }
    }

    /**
     * 启动心跳。
     *
     * <p>心跳必须按 Hello 给出的周期发，发晚了腾讯会断开连接。
     * 心跳包里的 {@code d} 是"收到的最新 {@code s}"，
     * 首次还没有收到任何事件时是 {@code null}（官方文档明确写了这一点）。
     *
     * @param intervalMillis 心跳周期（毫秒）
     */
    private void startHeartbeat(int intervalMillis) {
        stopHeartbeat();

        this.heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                ObjectNode payload = this.objectMapper.createObjectNode();
                payload.put("op", 1);

                if (this.lastSequence >= 0) {
                    payload.put("d", this.lastSequence);
                } else {
                    // 首次心跳：文档要求传 null，不能传 0 或 -1
                    payload.putNull("d");
                }

                sendText(payload.toString());
                log.debug("已发送心跳，seq={}", this.lastSequence);

            } catch (Exception e) {
                log.warn("发送心跳失败：{}", e.getMessage());
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);

        log.debug("心跳定时器已启动，周期 {} 毫秒", intervalMillis);
    }

    /**
     * 停止心跳。
     */
    private void stopHeartbeat() {
        if (this.heartbeatScheduler != null && !this.heartbeatScheduler.isShutdown()) {
            // 用 shutdownNow 并重建，比维护一个 ScheduledFuture 简单。
            // 重建的动作放在 startHeartbeat 里（它开头就调 stopHeartbeat）
            this.heartbeatScheduler.shutdownNow();
            this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                    daemonThreadFactory("qq-heartbeat"));
        }
    }

    /**
     * 把一段 JSON 文本发到网关。
     *
     * @param json 要发送的 JSON
     */
    private void sendText(String json) {
        WebSocket ws = this.webSocket;
        if (ws == null) {
            throw new IllegalStateException("WebSocket 还没连接，无法发送");
        }
        // sendText 是异步的，返回 CompletableFuture。
        // 这里不等结果：失败了 onError 回调会被触发，统一在那里处理重连
        ws.sendText(json, true);
    }

    /**
     * 打印一份排查清单。
     *
     * <p>连着失败到阈值时调用一次（用 {@code troubleshootingPrinted} 保证不重复）。
     *
     * <p><b>为什么值得写这么长的一段日志？</b> 因为"QQ 连不上"这个现象背后
     * 可能的原因太分散——权限、凭证、机器人状态、网络，每一个都得去不同的
     * 地方查。而最有价值的排查线索（官方文档里那句"其他特殊事件需要申请"）
     * 藏在文档深处，不查文档根本想不到。
     * 与其让人对着"连接被关闭"这五个字猜，不如把方向直接写出来。
     *
     * @param reason 触发原因，打印在开头
     */
    private void printTroubleshooting(String reason) {
        log.error("""
                
                ================================================================
                QQ 网关连接持续失败：{}
                ================================================================
                按下面顺序排查（按踩坑概率从高到低）：
                
                1. intents 权限 —— 最常见的原因
                   官方文档写明：除了 GUILDS、PUBLIC_GUILD_MESSAGES、GUILD_MEMBERS
                   三个基础事件默认有权限外，"其他的特殊事件，都需要经过申请
                   才能够使用"，且"如果在鉴权的时候传递了无权限的 intents，
                   websocket 会报错，并直接关闭连接"。
                
                   单聊和群聊@事件属于 GROUP_AND_C2C_EVENT (1 << 25) = {}，
                   正属于需要权限的那一类。
                   → 去 QQ 开放平台检查机器人的功能配置里，
                     "单聊消息""群聊消息"是否已开通。
                
                2. AppSecret 是否正确
                   → 重新从机器人的"开发管理"页复制一次，粘贴时注意别带多余空格。
                
                3. 机器人状态是否正常
                   换取凭证时的错误码 100007 = AppID 无效或机器人被下架/封禁，
                   100016 = AppID 或 ClientSecret 不正确。
                   → 看上面几行日志里有没有这两种错误码。
                
                4. 网络能否访问 api.bot.qq.com
                   → 本机若开着代理软件，确认它没把该域名的流量拦掉。
                
                重连节奏已放慢到 {} 秒一次，不会再刷屏。
                修好配置后重启应用即可恢复。
                ================================================================""",
                reason, config.getIntents(), COOLDOWN_SECONDS);
    }

    /**
     * 关闭一个定时器。
     *
     * @param scheduler 要关闭的定时器
     * @param name      名称，仅用于日志
     */
    private void shutdownScheduler(ScheduledExecutorService scheduler, String name) {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        log.debug("{}定时器已关闭", name);
    }

    /**
     * 截断过长文本，避免日志被一条超长 JSON 刷屏。
     *
     * @param text 原始文本
     * @return 截断后的文本
     */
    private String truncate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...(已截断)";
    }
}
