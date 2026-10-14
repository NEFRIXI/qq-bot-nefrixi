package com.mybotweb.command.impl;

import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.command.CommandHandler;
import com.mybotweb.command.CommandNames;
import com.mybotweb.config.AppProperties;
import com.mybotweb.route.ModelRouter;
import com.mybotweb.route.RouteStrategy;
import com.mybotweb.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code /setlocal} —— 把本地模型服务（WSL 里的 Ollama）拉起来。仅管理员可用。
 *
 * <p><b>它解决什么问题</b>：本地 Ollama 有时根本没启动，有时跑着跑着自己挂了。
 * 表现是聊天时收到一句"我这边的本地模型好像没跑起来"。
 * 这时候人得切到 WSL 终端去敲 {@code export OLLAMA_HOST=0.0.0.0:11434 && ollama serve}，
 * 而这条命令让这件事变成在 QQ 里发一句话。
 *
 * <p><b>它和 {@code /model} 的分工</b>：两条命令容易混，但层次完全不同——
 * <ul>
 *   <li>{@code /model} —— 进程活着，<b>换一个模型</b>来用（切 qwen2.5:7b → qwen2.5:3b）</li>
 *   <li>{@code /setlocal} —— 进程可能压根不在，<b>把它拉起来</b>（重启服务本身）</li>
 * </ul>
 * 所以正确用法是：先 {@code /setlocal} 让服务活着，再用 {@code /model} 挑模型。
 *
 * <p><b>为什么用 {@code wsl.exe} 而不是直接调 {@code ollama.exe}</b>：
 * 这个项目的 Ollama 装在 WSL 里，不在 Windows 的 PATH 上。
 * {@code wsl} 命令是 Windows 自带的桥，能把命令送进 WSL 执行。
 *
 * <p><b>为什么重启命令里必须显式设置 {@code OLLAMA_HOST=0.0.0.0:11434}</b>：
 * Ollama 默认只绑 {@code 127.0.0.1}，而 WSL2 的端口转发只对非回环绑定生效。
 * 绑了回环，Windows 侧 {@code localhost:11434} 就访问不到——
 * 这正是"服务看着起来了但连不上"的经典原因。
 *
 * <p><b>它为什么在本机之外的机器上没意义</b>：命令操作的是"运行这个 Java 进程的
 * 那台机器的 WSL"。部署到云服务器后，那里既没有 WSL 也没有 Ollama，
 * 所以命令会明确回一句"当前环境不支持"，而不是假装成功。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SetLocalCommand implements CommandHandler {

    /** 查看 Ollama 当前模型列表的接口（顺带当健康检查用，免费） */
    private static final String TAGS_PATH = "/api/tags";

    /** Ollama 探测的超时。本机通信，给 3 秒足够 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    /** 整个重启流程最多等多久。防止 wsl 命令卡住把消息处理线程占死 */
    private static final int RESTART_TIMEOUT_SECONDS = 30;

    /** 应用配置，读角色与本地 Ollama 管理配置 */
    private final AppProperties appProperties;

    /**
     * 算力路由器。
     *
     * <p>本命令不只是"重启一个进程"，它真正的意图是"这次用本地的算力"。
     * 所以拉起服务之后要把路由策略也切到 LOCAL_FIRST ——
     * 否则会出现一种很别扭的状态：本地模型明明活着，消息却还是走云端花钱，
     * 而用户以为自己刚把本地的拉起来了。
     *
     * <p>注意这里切的是运行期策略（内存），重启应用后回到配置文件的值。
     */
    private final ModelRouter modelRouter;

    @Override
    public String getName() {
        return CommandNames.SET_LOCAL;
    }

    @Override
    public List<String> getAliases() {
        // 中文别名，方便手机上输入。/拉起本地 这种带中文的名字在 QQ 里比英文好打
        return CommandNames.SET_LOCAL_ALIASES;
    }

    @Override
    public String getDescription() {
        return "重启本地模型服务（WSL 里的 Ollama）";
    }

    @Override
    public String getUsage() {
        return CommandNames.withSlash(CommandNames.SET_LOCAL);
    }

    /**
     * 仅管理员。
     *
     * <p>它会在宿主机上执行进程管理命令（杀进程、起进程），
     * 这属于系统级操作。普通用户能触发的话，等于给了所有人一个
     * "反复重启你电脑上的服务"的开关。
     */
    @Override
    public boolean isAdminOnly() {
        return true;
    }

    @Override
    public String execute(QqMessage message, UserContext user, String[] args) {
        AppProperties.Llm.Ollama ollamaConfig = appProperties.getLlm().getOllama();
        AppProperties.LocalOllama manageConfig = appProperties.getLocalOllama();

        // ---- 先看现在是什么状态 ----
        OllamaProbe before = probe(ollamaConfig.getBaseUrl());

        StringBuilder sb = new StringBuilder();
        sb.append("本地模型服务\n");
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("地址　　：").append(ollamaConfig.getBaseUrl()).append('\n');
        sb.append("当前状态：").append(before.online() ? "在线 ✅" : "离线 ❌").append('\n');

        if (before.online()) {
            sb.append("已载模型：").append(before.describeModels(ollamaConfig.getModel())).append('\n');
            sb.append("━━━━━━━━━━━━━━━━\n");
            sb.append("服务本来就在跑，不需要重启。\n");
            sb.append("想换模型用 /model。");
            return sb.toString();
        }

        // ---- 离线：先确认当前环境支不支持管理 ----
        if (!isWindows()) {
            sb.append("━━━━━━━━━━━━━━━━\n");
            sb.append("当前系统是 ").append(System.getProperty("os.name")).append("，不是本地 Windows 环境。\n");
            sb.append("这条命令只能重启「跑这个程序的机器上的 WSL Ollama」，\n");
            sb.append("云服务器上没有 WSL 也没有 Ollama，所以无法执行。");
            return sb.toString();
        }

        if (!manageConfig.isManageEnabled()) {
            sb.append("━━━━━━━━━━━━━━━━\n");
            sb.append("重启功能已被配置关闭（app.local-ollama.manage-enabled = false）。\n");
            sb.append("请手动在 WSL 里启动：\n");
            sb.append("  export OLLAMA_HOST=0.0.0.0:11434\n");
            sb.append("  ollama serve");
            return sb.toString();
        }

        // ---- 真的去重启 ----
        sb.append("正在重启…\n");
        log.warn("管理员 {} 触发 /setlocal，开始重启本地 Ollama", user.toLogTag());

        CommandResult result = restart(manageConfig);
        if (!result.success()) {
            sb.append("━━━━━━━━━━━━━━━━\n");
            sb.append("❌ 重启失败\n");
            sb.append("原因：").append(result.message()).append('\n');
            sb.append("\n如果提示找不到 ollama 命令，说明它在 WSL 里不在 PATH 上。\n");
            sb.append("改 application.yml 的 app.local-ollama.restart-command，\n");
            sb.append("把 ollama serve 换成完整路径（先用 which ollama 查）。");
            return sb.toString();
        }

        // ---- 等待后验证 ----
        OllamaProbe after = probe(ollamaConfig.getBaseUrl());

        sb.append("━━━━━━━━━━━━━━━━\n");
        if (after.online()) {
            sb.append("✅ 已拉起，现在在线\n");
            sb.append("已载模型：").append(after.describeModels(ollamaConfig.getModel())).append('\n');
            log.info("本地 Ollama 重启成功，可用模型 {} 个", after.models().size());
        } else {
            sb.append("⚠️ 命令执行成功，但端口还没通\n");
            sb.append("可能还在启动（大模型加载索引要几秒），稍等再发一次 /setlocal 看状态。\n");
            sb.append("若一直不通，在 WSL 里看日志：tail -30 /tmp/ollama.log");
            log.warn("本地 Ollama 重启命令已执行，但 {} 仍不可达", ollamaConfig.getBaseUrl());
            return sb.toString();
        }

        // ---- 再把路由指向本地 ----
        // 这一步是本命令的另一半语义：服务活着不等于"在用本地"。
        // 如果策略还是 CLOUD_ONLY 之类，消息照样走云端花钱，
        // 而用户以为自己刚把本地切过来了 —— 那种别扭状态比命令失败更难查。
        sb.append("━━━━━━━━━━━━━━━━\n");
        if (modelRouter.currentStrategy() == RouteStrategy.LOCAL_FIRST) {
            sb.append("路由策略：本来就是 LOCAL_FIRST，无需改动\n");
        } else {
            RouteStrategy previous = modelRouter.currentStrategy();
            modelRouter.setStrategy(RouteStrategy.LOCAL_FIRST);
            sb.append("路由策略：").append(previous.name())
                    .append(" → LOCAL_FIRST（已切换）\n");
            log.info("管理员 {} 通过 /setlocal 把路由策略从 {} 切到 LOCAL_FIRST",
                    user.toLogTag(), previous.name());
        }
        sb.append("本次路由：").append(modelRouter.describeDecision()).append('\n');
        sb.append("\n可以正常聊天了。想换模型用 /model。");

        return sb.toString();
    }

    /**
     * 执行重启命令。
     *
     * <p><b>为什么要把 stdout 和 stderr 都读掉？</b> 这是个很实际的坑：
     * 子进程的输出缓冲区满了而没人读，子进程会阻塞在写操作上，
     * 于是 {@code waitFor} 一直等不到结束——表现为"命令卡死"。
     * 这里用后台线程把两个流都读走，避免这个死锁。
     *
     * @param config 本地 Ollama 管理配置
     * @return 执行结果
     */
    private CommandResult restart(AppProperties.LocalOllama config) {
        List<String> command = List.of(
                "wsl", "-d", config.getWslDistro(), "--",
                "bash", "-lc", config.getRestartCommand());

        log.info("执行重启命令：wsl -d {} -- bash -lc \"{}\"",
                config.getWslDistro(), config.getRestartCommand());

        try {
            Process process = new ProcessBuilder(command).start();

            // 两个流必须都读，否则缓冲区满了会卡住子进程
            StringBuilder output = new StringBuilder();
            Thread outReader = drain(process.getInputStream(), output);
            Thread errReader = drain(process.getErrorStream(), output);

            boolean finished = process.waitFor(RESTART_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false,
                        "命令执行超时（超过 " + RESTART_TIMEOUT_SECONDS + " 秒）。"
                                + "可能是 WSL 需要唤醒，或 ollama serve 卡住了。");
            }

            outReader.join(2000);
            errReader.join(2000);

            int exitCode = process.exitValue();
            String text = output.toString().strip();

            if (exitCode != 0) {
                return new CommandResult(false, String.format(
                        "命令退出码 %d。输出：%s", exitCode,
                        text.isEmpty() ? "（无输出）" : text));
            }

            // 命令成功后再等一会，让 Ollama 有时间加载
            int waitSeconds = Math.max(0, config.getVerifyWaitSeconds());
            if (waitSeconds > 0) {
                Thread.sleep(waitSeconds * 1000L);
            }
            return new CommandResult(true, text.isEmpty() ? "命令执行完成" : text);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, "执行被中断");
        } catch (Exception e) {
            log.error("执行重启命令失败", e);
            return new CommandResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 起一个后台线程把输入流读干净。
     *
     * @param stream 子进程的输出流
     * @param sink   读到的内容追加到这里
     * @return 已启动的线程，调用方可以 join 等它读完
     */
    private Thread drain(java.io.InputStream stream, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
                // 读流出错不影响主流程判断，忽略
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 探测 Ollama 是否在线，并顺带取出模型列表。
     *
     * <p>用 {@code /api/tags} 而不是 {@code /api/version}，因为前者一次
     * 就能同时回答"活着吗"和"有哪些模型"两个问题，省一次往返。
     *
     * @param baseUrl Ollama 接口地址
     * @return 探测结果，失败时 {@code online = false}
     */
    private OllamaProbe probe(String baseUrl) {
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(PROBE_TIMEOUT);
            factory.setReadTimeout(PROBE_TIMEOUT);

            TagsResponse response = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(factory)
                    .build()
                    .get()
                    .uri(TAGS_PATH)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(TagsResponse.class);

            if (response == null || response.getModels() == null) {
                return new OllamaProbe(false, List.of());
            }

            List<String> names = new ArrayList<>();
            for (TagsResponse.ModelEntry entry : response.getModels()) {
                if (entry != null && entry.getName() != null) {
                    names.add(entry.getName());
                }
            }
            return new OllamaProbe(true, names);

        } catch (Exception e) {
            // 连不上就是离线。这里刻意不打堆栈——"Ollama 没开"是预期内的
            // 常见情况，不是异常事件，打堆栈只会把日志刷乱
            log.debug("探测 Ollama 失败（视为离线）：{}", e.getMessage());
            return new OllamaProbe(false, List.of());
        }
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
     * Ollama 探测结果。
     *
     * @param online 是否在线
     * @param models 已下载的模型名列表
     */
    private record OllamaProbe(boolean online, List<String> models) {

        /**
         * 把模型列表渲染成一行给人看的文本。
         *
         * @param currentModel 配置里指定的默认模型，会被标注出来
         * @return 描述文本
         */
        String describeModels(String currentModel) {
            if (models.isEmpty()) {
                return "（一个都没有，需要在 WSL 里 ollama pull 拉模型）";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < models.size(); i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(models.get(i));
                if (models.get(i).equals(currentModel)) {
                    sb.append("（当前）");
                }
            }
            return sb.toString();
        }
    }

    /**
     * 重启命令的执行结果。
     *
     * @param success 是否成功
     * @param message 说明文本，失败时是原因
     */
    private record CommandResult(boolean success, String message) {
    }

    /**
     * {@code /api/tags} 的响应结构。
     *
     * <p>只映射用到的字段。Jackson 默认忽略未知字段的配置在项目里已经开了，
     * 所以这里的 {@code models[].name} 之外的东西不会被解析。
     */
    @lombok.Data
    public static class TagsResponse {

        /** 模型列表 */
        private List<ModelEntry> models;

        /**
         * 单个模型条目。
         */
        @lombok.Data
        public static class ModelEntry {

            /** 模型名，例如 {@code qwen2.5:7b} */
            private String name;
        }
    }
}
