package com.mybotweb.llm.ollama;

import com.mybotweb.config.AppProperties;
import com.mybotweb.llm.ChatModelClient;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地模型守护者 —— 发现本地 Ollama 不在就跑起来。<b>由路由层自动调用。</b>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>它解决什么问题</b>
 *
 * <p>本地 Ollama 跑在 WSL 里，而 WSL <b>不会一直活着</b>：
 * <ul>
 *   <li>电脑重启 → WSL 关了，需要重新进终端敲命令</li>
 *   <li>睡眠唤醒 → 有时 WSL 的端口转发会失效</li>
 *   <li>手动关掉 → 忘了再开</li>
 * </ul>
 *
 * <p>这些情况下原来的表现是：机器人悄悄开始调云端 API（因为路由判定
 * "本地不可用"就降级了），而<b>你并不会立刻发现</b>——直到看见账单，
 * 或者看到回复风格变了。
 *
 * <p>本类的存在让这件事变成自动的：<b>发现不在 → 后台拉起来 →
 * 下一次请求就自动走本地了。</b>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>它是怎么被触发的</b>
 *
 * <p>由 {@code ModelRouterImpl} 在每条消息判断路由时调用
 * {@link #ensureStarted()}。所以它天然是"随用随检查"的，
 * 不需要额外的定时任务。
 *
 * <p><b>为什么是异步的</b>：启动是个耗时动作（起进程、等模型加载，几秒到几十秒）。
 * 如果在消息处理线程里同步等，那一条消息就会卡住那么久——用户会以为机器人死了。
 * 所以这里是<b>发起启动就返回</b>，当前这条消息照常走云端兜底，
 * 等下次请求时本地就好了。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>三道防线，防止它变成负担</b>
 *
 * <ol>
 *   <li><b>冷却时间</b>（{@code auto-start-cooldown-seconds}）——
 *       两次尝试之间至少隔这么久。否则本地一旦拉不起来，
 *       每条消息都会试着起一个进程，把线程池占满</li>
 *   <li><b>"启动中"互斥标记</b> —— 上一个启动动作还没结束时，不再发起新的。
 *       这条防的是"消息瞬时涌入时并发起好几个进程"</li>
 *   <li><b>只在 Windows 生效</b> —— 云服务器（Linux）上既没有 WSL
 *       也没有 Ollama，在那边这个类是彻底的空操作</li>
 * </ol>
 *
 * <p>另外它还尊重 {@code app.local-ollama.manage-enabled}：
 * 那个开关关掉时（表示"不许程序碰我的进程"），本类也不会动。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>和 {@code /setlocal} 的分工</b>
 *
 * <table border="1">
 *   <tr><th></th><th>{@code /setlocal}</th><th>本类</th></tr>
 *   <tr><td>触发</td><td>管理员手动发命令</td><td>路由判断时自动</td></tr>
 *   <tr><td>执行方式</td><td><b>同步</b>，等结果给用户回执</td><td><b>异步</b>，不等</td></tr>
 *   <tr><td>用途</td><td>"我知道它挂了，帮我起一下并告诉我结果"</td><td>"别让我操心，能用本地就用本地"</td></tr>
 * </table>
 *
 * <p>两者读的是同一份配置（{@code app.local-ollama.*}），所以改了命令
 * 两边同时生效。命令里那段启动逻辑比本类完整（它还带探测和结果校验），
 * 但两者的核心命令是同一个字符串——这里刻意没有抽公共类，
 * 因为它们的<b>错误处理方式完全不同</b>（一个要给人看，一个只记日志），
 * 硬抽会让两边都变别扭。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class LocalModelSupervisor {

    /** 本地 Ollama 配置（重启命令、发行版名、开关等） */
    private final AppProperties.LocalOllama config;

    /**
     * 本地算力客户端，用来判断"现在通不通"。
     *
     * <p>注入具体的 ollama 实现（而不是 {@code ChatModelClient} 接口），
     * 是因为有两个实现同时装配着，用 {@code @Qualifier} 指名才不会有歧义。
     */
    private final ChatModelClient localClient;

    /**
     * "正在启动"标记。
     *
     * <p>防的是"消息瞬时涌入时并发起好几个 ollama 进程"。
     * 用 CAS 而不是普通布尔量，保证只有一个线程能把它从 false 翻成 true。
     */
    private final AtomicBoolean starting = new AtomicBoolean(false);

    /** 上次尝试启动的时间戳，用于冷却判断 */
    private final AtomicLong lastAttemptMillis = new AtomicLong(0L);

    /**
     * 执行启动的线程池。
     *
     * <p>单线程就够，因为同一时刻只允许一个启动动作（{@link #starting} 保证）。
     * 用守护线程，这样它不会阻止 JVM 退出。
     */
    private final ExecutorService starter;

    /** 启动命令的超时（秒）。WSL 唤醒可能慢，给宽一些但不无限等 */
    private static final int START_TIMEOUT_SECONDS = 40;

    public LocalModelSupervisor(AppProperties appProperties,
                                @Qualifier("ollamaChatModelClient") ChatModelClient localClient) {
        this.config = appProperties.getLocalOllama();
        this.localClient = localClient;

        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "local-model-supervisor");
            thread.setDaemon(true);
            return thread;
        };
        this.starter = Executors.newSingleThreadExecutor(factory);

        log.info("本地模型守护已就绪：自动拉起={}，冷却 {} 秒，WSL 发行版={}",
                this.config.isAutoStart() ? "开启" : "关闭",
                this.config.getAutoStartCooldownSeconds(),
                this.config.getWslDistro());
    }

    /**
     * 确保本地模型服务在跑。不在就异步拉起来。
     *
     * <p>这个方法会被<b>每条消息</b>调用一次，所以前面几个判断必须廉价——
     * 全是内存操作和一次带缓存的探测（{@code isAvailable()} 内部有 10 秒缓存），
     * 没有任何真正的阻塞 IO。
     *
     * <p>注意它<b>不返回"有没有拉起来"</b>。因为启动是异步的，
     * 此刻的答案必然是"还不知道"。调用方（路由）该做的就是照常降级到云端，
     * 下次请求时本地自然可用了。
     */
    public void ensureStarted() {
        // ---- 前置检查：都很快，且大多会在这里就返回 ----

        if (!this.config.isAutoStart()) {
            return;
        }
        if (!this.config.isManageEnabled()) {
            // 这个开关的含义是"不许程序碰我的进程"，自动拉起也算碰
            return;
        }
        if (!isWindows()) {
            // 云服务器上没有 WSL，这个能力在那里没有意义
            return;
        }
        if (this.localClient.isAvailable()) {
            // 本来就活着，什么都不用做（这是最常见的情况）
            return;
        }
        if (!isCooledDown()) {
            log.debug("本地 Ollama 不可用，但还在冷却期内（{} 秒），本次不尝试拉起",
                    this.config.getAutoStartCooldownSeconds());
            return;
        }
        if (!this.starting.compareAndSet(false, true)) {
            log.debug("本地 Ollama 的启动动作正在进行中，本次不重复发起");
            return;
        }

        // ---- 通过所有检查，异步启动 ----

        // 冷却时间戳在这里就更新，而不是等启动结束——
        // 因为启动本身可能失败或超时，我们关心的是"多久发起过一次尝试"
        this.lastAttemptMillis.set(System.currentTimeMillis());

        log.warn("检测到本地 Ollama 不可用，正在后台尝试拉起（WSL 发行版 {}）",
                this.config.getWslDistro());

        this.starter.submit(() -> {
            try {
                doStart();
            } catch (Exception e) {
                log.error("自动拉起本地 Ollama 失败：{}", e.getMessage());
            } finally {
                this.starting.set(false);
            }
        });
    }

    /**
     * 真正执行启动命令。
     *
     * <p>命令来自配置 {@code app.local-ollama.restart-command}，
     * 和 {@code /setlocal} 用的是同一个。它自带 {@code OLLAMA_HOST=0.0.0.0:11434}——
     * 那一段不能省，否则 Ollama 只绑回环地址，Windows 侧访问不到。
     */
    private void doStart() {
        List<String> command = List.of(
                "wsl", "-d", this.config.getWslDistro(), "--",
                "bash", "-lc", this.config.getRestartCommand());

        long startedAt = System.currentTimeMillis();

        try {
            Process process = new ProcessBuilder(command).start();

            // 必须把两个流读干净 —— 子进程的输出缓冲区满了而没人读，
            // 它会阻塞在写操作上，于是 waitFor 永远等不到结束。
            // 这个坑在 /setlocal 那边也踩过，处理方式一样
            Thread outReader = drain(process.getInputStream());
            Thread errReader = drain(process.getErrorStream());

            boolean finished = process.waitFor(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                log.error("自动拉起本地 Ollama 超时（{} 秒）。可能是 WSL 需要唤醒，"
                                + "或 ollama serve 卡住了。下次冷却结束后会重试",
                        START_TIMEOUT_SECONDS);
                return;
            }

            // 等读流线程收尾，但不无限等
            outReader.join(2000);
            errReader.join(2000);

            int exitCode = process.exitValue();
            long elapsed = System.currentTimeMillis() - startedAt;

            if (exitCode != 0) {
                log.error("自动拉起本地 Ollama 的命令退出码 {}（耗时 {} ms）。"
                                + "常见原因：ollama 不在 WSL 的 PATH 上，或发行版名写错了"
                                + "（当前配置：{}）。命令输出见上面的 WARN/DEBUG 行",
                        exitCode, elapsed, this.config.getWslDistro());
                return;
            }

            log.info("拉起命令执行完成（耗时 {} ms），正在等待服务就绪…", elapsed);

            // 命令成功不代表服务已经能提供服务 —— Ollama 要加载模型索引。
            // 所以这里等一会再探测，把"启动了但还没就绪"和"根本没起来"区分开
            int waitSeconds = Math.max(0, this.config.getVerifyWaitSeconds());
            if (waitSeconds > 0) {
                Thread.sleep(waitSeconds * 1000L);
            }

            // 探测一次，把结果记下来。这里不复用 isAvailable()，
            // 因为它的缓存可能在这次启动之前就已经填了"不可用"，
            // 那会让我们误以为启动失败（实际只是读到了旧缓存）
            probeAndReport();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("自动拉起本地 Ollama 的过程被中断");
        } catch (Exception e) {
            log.error("执行自动拉起命令时出错", e);
        }
    }

    /**
     * 启动后探测一次，把结果写进日志。
     *
     * <p>注意这里用的是<b>直接调用</b> {@code isAvailable()}——
     * 它内部有 10 秒缓存，可能返回启动前的旧结果。所以本方法的结论
     * 只当参考，真正的"本地是否可用"由下一次路由判断来决定
     * （那时缓存早已过期，会重新探测）。
     */
    private void probeAndReport() {
        boolean available = this.localClient.isAvailable();
        if (available) {
            log.info("""
                    
                    ================================================================
                    本地 Ollama 已自动拉起 ✅
                    ----------------------------------------------------------------
                    下一次聊天请求就会走本地算力，不再调用云端 API。
                    不需要任何手动操作。
                    
                    本次共花费 {} ms（含等待服务就绪的时间）。
                    ================================================================""",
                    System.currentTimeMillis() - this.lastAttemptMillis.get());
        } else {
            log.warn("""
                    
                    ================================================================
                    拉起命令执行成功，但本地 Ollama 仍然探测不到
                    ----------------------------------------------------------------
                    可能原因：
                      · 服务还在启动中（首次加载大模型要十几秒）——下次请求会再探测
                      · ollama 不在 WSL 的 PATH 上 —— 在 WSL 里执行 which ollama 确认
                      · WSL 发行版名不对 —— 当前配置的是 {}，用 wsl -l -v 核对
                      · OLLAMA_HOST 没设为 0.0.0.0 —— 那样 Windows 侧连不上
                    
                    排查命令（在 WSL 里执行）：
                      tail -30 /tmp/ollama.log
                    
                    {} 秒冷却结束后会自动重试。
                    ================================================================""",
                    this.config.getWslDistro(), this.config.getAutoStartCooldownSeconds());
        }
    }

    /**
     * 判断冷却期是否已过。
     *
     * @return 可以再次尝试启动返回 {@code true}
     */
    private boolean isCooledDown() {
        long last = this.lastAttemptMillis.get();
        if (last == 0L) {
            // 从未尝试过，立刻可以试
            return true;
        }
        long cooldownMillis = Math.max(0, this.config.getAutoStartCooldownSeconds()) * 1000L;
        return System.currentTimeMillis() - last >= cooldownMillis;
    }

    /**
     * 当前是否运行在 Windows 上。
     *
     * @return Windows 返回 {@code true}
     */
    private boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }

    /**
     * 起一个后台线程把子进程的输出读干净并写进日志。
     *
     * @param stream 子进程的输出流
     * @return 已启动的线程
     */
    private Thread drain(InputStream stream) {
        Thread thread = new Thread(() -> {
            // 显式指定 UTF-8：WSL 里的 Linux 程序（ollama、bash）输出的都是 UTF-8，
            // 而 Windows 上 InputStreamReader 默认用平台编码（GBK），
            // 那样中文日志会变成一串乱码，排查时完全没法读
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 用 DEBUG 级别：ollama serve 的输出可能很啰嗦，
                    // 而这里的日志只在排查启动问题时才需要看
                    log.debug("[wsl] {}", line);
                }
            } catch (Exception ignored) {
                // 读流出错不影响主流程判断
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 关闭线程池。
     *
     * <p>不关的话，虽然线程是守护线程不会阻止 JVM 退出，
     * 但在测试环境里反复启动应用时会积累一堆线程。
     */
    @PreDestroy
    public void shutdown() {
        this.starter.shutdownNow();
    }
}
