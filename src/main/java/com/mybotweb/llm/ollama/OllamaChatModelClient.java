package com.mybotweb.llm.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mybotweb.config.AppProperties;
import com.mybotweb.llm.ChatModelClient;
import com.mybotweb.llm.ModelCallException;
import com.mybotweb.llm.model.ChatMessage;
import com.mybotweb.llm.ollama.dto.OllamaChatRequest;
import com.mybotweb.llm.ollama.dto.OllamaChatResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;

/**
 * 本地 Ollama 大模型客户端 —— 调用跑在 WSL 里的 4B 小模型。
 *
 * <p>这是"直接路由到本地"这条路径上的核心类：QQ 消息进来后，
 * 普通闲聊由它调本地模型算，不经过任何云端服务、不产生 API 费用。
 *
 * <p><b>网络拓扑（重要）</b>：
 * <pre>
 *   Windows 上的本程序  ──HTTP──▶  localhost:11434  ──WSL2 端口转发──▶  WSL 里的 Ollama
 * </pre>
 * WSL2 会把 WSL 内监听的端口转发到 Windows 的 {@code localhost}，
 * 但有个前提：<b>Ollama 必须绑定到 {@code 0.0.0.0} 而不是 {@code 127.0.0.1}</b>。
 * Ollama 默认只绑回环地址，那样 Windows 侧是连不上的。
 * 在 WSL 里启动 Ollama 前需要先设置环境变量：
 * <pre>
 *   export OLLAMA_HOST=0.0.0.0:11434
 *   ollama serve
 * </pre>
 * 如果之后 {@code /status} 显示本地模型不可用，第一件事就是查这个环境变量。
 *
 * <p><b>为什么用 {@code RestClient} 而不是规格里写的 {@code WebClient}？</b>
 * {@code WebClient} 属于 Spring 的响应式栈，方法返回的是
 * {@code Mono<String>} 而不是 {@code String}，还要额外引入
 * {@code spring-boot-starter-webflux} 一整条 reactor/netty 依赖。
 * 我们这里是"发一次请求、等一个结果"的同步场景，
 * Spring Boot 3.2 起官方提供了同步版的 {@code RestClient}，
 * API 直白、不引入额外依赖、不影响项目结构清晰度。
 * 如果以后确实需要高并发流式调用，再换成 {@code WebClient} 也只是改这一个类。
 *
 * <p>装配：{@code @Component}，<b>无条件</b> —— 和 {@code CloudChatModelClient} 一样。
 *
 * <p><b>它和 {@code CloudChatModelClient} 现在【同时装配】</b>。
 *
 * <p>改造前这里是"由 {@code app.role} 二选一"的互斥装配，理由是
 * "用哪个模型取决于这段代码跑在哪台机器上"。但"云端当路由器"这个目标
 * 打破了这个前提：云服务器既要能调 API，又要能接受本地节点连入并派任务过去，
 * 所以启动时不能把本地客户端排除掉——否则路由根本没有第二个选项可挑。
 *
 * <p>现在两个都装配，由 {@code ModelRouterImpl} 在运行期按策略选。
 * 本类的构造器只创建 HTTP 客户端、不发任何请求（真正的探测在
 * {@link #isAvailable()} 里且带缓存），所以在没有 Ollama 的云服务器上
 * 装配本类也不会出错。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class OllamaChatModelClient implements ChatModelClient {

    /**
     * 可用性探测结果的缓存时长（毫秒）。
     *
     * <p>为什么要缓存：{@code isAvailable()} 每收到一条消息都会被调用一次。
     * 如果每次都真的发一次 HTTP 请求去探测，消息一多就会把 Ollama 打满。
     * 10 秒的缓存意味着"本地模型刚起来"最多 10 秒后就能被识别到，
     * 而"本地模型刚挂掉"也最多 10 秒后才开始降级——这个延迟完全可以接受。
     */
    private static final long AVAILABILITY_CACHE_MILLIS = 10_000L;

    /** 探测连接的超时时间（秒）。本地服务，连不上应当立刻返回 */
    private static final int PROBE_CONNECT_TIMEOUT_SECONDS = 3;

    /** Ollama 相关配置 */
    private final AppProperties.Llm.Ollama config;

    /**
     * 模型选择器 —— 提供"当前该用哪个模型"。
     *
     * <p>注意本类<b>不把模型名缓存成字段</b>，每次调用都问选择器要一次。
     * 这是"运行中切换模型"能真正生效的关键：如果在构造时把模型名存下来，
     * 那么 {@code /model} 命令切换之后这里还在用老名字，
     * 表现就是"命令说切了、实际没变"——这种 bug 很隐蔽，
     * 因为两边看起来都没错。
     */
    private final OllamaModelSelector modelSelector;

    /** HTTP 客户端，构造时创建一次复用 */
    private final RestClient restClient;

    /** 上次探测得到的可用性结果 */
    private volatile boolean cachedAvailable = false;

    /** 上次探测的时间戳（毫秒） */
    private volatile long lastProbeMillis = 0L;

    /**
     * 上次探测时用的模型名，用于让缓存在"模型被切换"时自动失效。
     *
     * <p>不加这个字段会出这样的问题：用 7B 探测成功、缓存记为"可用"；
     * 管理员切到 4B（而那个模型其实没下载），缓存仍然说可用，
     * 于是每次聊天都失败，但 {@code /status} 显示"可用"——
     * 排查时会被这个自相矛盾的现象带偏很久。
     */
    private volatile String probedModel;

    /**
     * 构造器注入配置，并在此创建 HTTP 客户端。
     *
     * <p>注意超时设置分两种：
     * <ul>
     *   <li><b>连接超时</b>很短（3 秒）——Ollama 没在跑时应当快速失败，
     *       而不是让用户等两分钟才知道"本地模型没开"</li>
     *   <li><b>读取超时</b>很长（配置里的 120 秒）——本地小模型生成
     *       一段回复可能要几十秒，这个时间是正常的，不能算超时</li>
     * </ul>
     * 这两个值分开设，是本地模型场景下最容易踩的坑之一：
     * 如果读取超时也设成 3 秒，会表现为"每次都超时，但其实模型好好的"。
     *
     * @param appProperties 应用配置
     * @param modelSelector 模型选择器，负责回答"现在该用哪个模型"
     */
    public OllamaChatModelClient(AppProperties appProperties, OllamaModelSelector modelSelector) {
        this.config = appProperties.getLlm().getOllama();
        this.modelSelector = modelSelector;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(PROBE_CONNECT_TIMEOUT_SECONDS));
        factory.setReadTimeout(Duration.ofSeconds(this.config.getTimeoutSeconds()));

        this.restClient = RestClient.builder()
                .baseUrl(this.config.getBaseUrl())
                .requestFactory((ClientHttpRequestFactory) factory)
                .build();

        log.info("本地 Ollama 客户端已初始化：{}（用哪个模型由 OllamaModelSelector 决定，"
                + "可用 /model 命令切换）", this.config.getBaseUrl());
    }

    /**
     * 调用本地模型生成回复。
     *
     * @param messages 对话历史（人设在最前，用户最新一句在最后）
     * @return 模型生成的文本
     * @throws ModelCallException 调用失败、超时或响应异常时抛出；
     *                            异常里的 {@code getUserMessage()} 已按原因分好类
     */
    @Override
    public String chat(List<ChatMessage> messages) {
        // 每次调用都向选择器要一次当前模型名 —— 这是"运行中切换"生效的关键
        String model = this.modelSelector.getCurrentModel();
        OllamaChatRequest request = OllamaChatRequest.of(model, messages);

        long startedAt = System.currentTimeMillis();
        log.debug("调用本地模型 {}，上下文 {} 条消息（估算 {} tokens）",
                model,
                messages.size(),
                messages.stream().mapToInt(ChatMessage::estimateTokens).sum());

        OllamaChatResponse response;
        try {
            response = this.restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(OllamaChatResponse.class);
        } catch (RestClientResponseException e) {
            // 本地服务回了 4xx/5xx。最常见的是 404：请求的模型还没下载
            // （ollama list 里没有它），这时"Ollama 没启动"是完全错误的猜测
            long elapsed = System.currentTimeMillis() - startedAt;
            int status = e.getStatusCode().value();
            String userMessage = (status == 404)
                    ? "我这边的本地模型（" + model + "）好像还没下载，稍后再试试。"
                    : "本地模型服务报错了（" + status + "），稍后再试试。";
            throw new ModelCallException(userMessage, String.format(
                    "调用本地 Ollama 失败（HTTP %d，耗时 %d ms，地址 %s，模型 %s）：%s",
                    status, elapsed, this.config.getBaseUrl(), model,
                    e.getResponseBodyAsString()), e);

        } catch (ResourceAccessException e) {
            // 连不上本地服务：Ollama 没启动、端口不对，或者模型正在加载导致读超时。
            // 这里不吞异常：上层需要知道"本地算不了"，才能决定降级到云端。
            // 如果这里假装成功返回一句道歉，上层就永远不知道本地挂了
            long elapsed = System.currentTimeMillis() - startedAt;
            throw new ModelCallException(
                    "我这边的本地模型好像没跑起来，稍后再试试。",
                    String.format("连接本地 Ollama 失败（耗时 %d ms，地址 %s，模型 %s）：%s",
                            elapsed, this.config.getBaseUrl(), model, e.getMessage()), e);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startedAt;
            throw new ModelCallException(
                    "本地模型这边出了点意外，稍后再试试。",
                    String.format("调用本地 Ollama 失败（耗时 %d ms，地址 %s，模型 %s）：%s",
                            elapsed, this.config.getBaseUrl(), model, e.getMessage()), e);
        }

        if (response == null) {
            throw new ModelCallException(
                    "本地模型返回了空回复，稍后再试试。",
                    "本地 Ollama 返回了空响应（地址 " + this.config.getBaseUrl()
                            + "，模型 " + model + "）");
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        String content = response.extractContent();

        log.info("本地模型生成完成：耗时 {} ms，回复 {} 字", elapsed, content.length());
        return content;
    }

    @Override
    public String getProviderName() {
        return "ollama";
    }

    /**
     * 探测本地 Ollama 是否可用，并且<b>指定的模型是否已下载</b>。
     *
     * <p>探测方式：请求 {@code GET /api/tags}，它会返回本机已下载的模型清单。
     * 一次请求同时回答两个问题：
     * <ol>
     *   <li>Ollama 服务在不在（请求成功说明在）</li>
     *   <li>配置里写的模型名对不对（在清单里说明对）</li>
     * </ol>
     * 第二个问题很值得查——模型名写错是极常见的配置错误，
     * 而它的表现是"服务可用但每次调用都报模型不存在"，
     * 在这里提前发现比等到用户发消息才报错要好。
     *
     * @return 服务可用且模型存在时返回 {@code true}
     */
    @Override
    public boolean isAvailable() {
        String model = this.modelSelector.getCurrentModel();
        long now = System.currentTimeMillis();

        // 如果模型刚被切换过，之前的探测结果不适用于新模型，缓存必须作废
        boolean modelChanged = !java.util.Objects.equals(model, this.probedModel);

        // 缓存命中，直接返回上次结果
        if (!modelChanged && now - this.lastProbeMillis < AVAILABILITY_CACHE_MILLIS) {
            return this.cachedAvailable;
        }

        // 双重检查加锁：避免多条消息同时到达时并发去探测
        synchronized (this) {
            // 等锁期间模型可能又被切了一次，所以这里重新取一遍
            String currentModel = this.modelSelector.getCurrentModel();
            boolean stillSameModel = java.util.Objects.equals(currentModel, this.probedModel);
            if (stillSameModel
                    && System.currentTimeMillis() - this.lastProbeMillis < AVAILABILITY_CACHE_MILLIS) {
                return this.cachedAvailable;
            }

            boolean result = probe(currentModel);
            this.cachedAvailable = result;
            this.lastProbeMillis = System.currentTimeMillis();
            this.probedModel = currentModel;
            return result;
        }
    }

    /**
     * 问 Ollama 本机下载了哪些模型。
     *
     * <p>用途是给 {@code /model} 命令展示真实的可选列表。
     * 这样你刚 {@code ollama pull} 下来的模型立刻就能看到，
     * 不用改配置文件、也不用重启应用。
     *
     * <p>排序是为了让输出稳定：Ollama 返回的顺序不保证固定，
     * 不排序的话同一个命令两次执行可能列出不同顺序，看着像出了什么问题。
     *
     * @return 模型名列表；Ollama 不可达时返回空列表
     */
    @Override
    public List<String> listAvailableModels() {
        try {
            OllamaTagsResponse tags = this.restClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(OllamaTagsResponse.class);

            if (tags == null || tags.getModels() == null) {
                return List.of();
            }
            return tags.getModels().stream()
                    .map(OllamaTagsResponse.ModelInfo::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .sorted()
                    .toList();

        } catch (Exception e) {
            // 拿不到列表不是致命问题——调用方会回退到配置里的清单。
            // 所以这里只记 DEBUG，不刷 ERROR
            log.debug("获取本机模型列表失败（Ollama 可能没启动）：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 真正去探测一次：服务在不在，并且指定的模型下载了没有。
     *
     * <p>任何异常都视为不可用——探测本身不该抛异常给上层，
     * 因为"本地不可用"是一个正常的业务状态（会导致降级到云端），
     * 不是程序错误。
     *
     * @param model 要检查的模型名。<b>由调用方传入，而不是从这里读配置</b>——
     *              因为配置里的默认值可能已经被 {@code /model} 命令覆盖了，
     *              读配置会探测到错误的模型
     * @return 可用返回 {@code true}
     */
    private boolean probe(String model) {
        try {
            OllamaTagsResponse tags = this.restClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(OllamaTagsResponse.class);

            if (tags == null || tags.getModels() == null) {
                log.warn("本地 Ollama 探测异常：返回内容为空");
                return false;
            }

            // 检查指定的模型是否已下载
            boolean modelExists = tags.getModels().stream()
                    .map(OllamaTagsResponse.ModelInfo::getName)
                    .anyMatch(name -> matchesModelName(name, model));

            if (!modelExists) {
                // 这个警告很有价值：服务在跑但模型没下，是最容易搞混的一种"不可用"。
                // 把本机实际有哪些模型直接列出来，省得再去 WSL 里敲一次 ollama list
                List<String> installed = tags.getModels().stream()
                        .map(OllamaTagsResponse.ModelInfo::getName)
                        .toList();
                log.warn("本地 Ollama 在运行，但没有模型 '{}'。本机已下载的模型：{}。"
                                + "可以执行 ollama pull {} 下载它，"
                                + "或者用 /model 命令切到上面列表里已有的模型",
                        model, installed, model);
            }
            return modelExists;

        } catch (Exception e) {
            // 服务没启动、端口不通、WSL 没开——都走这里。降级到 DEBUG 级别避免刷屏，
            // 因为本地离线是预期内的正常状态
            log.debug("本地 Ollama 不可用（{}）：{}", this.config.getBaseUrl(), e.getMessage());
            return false;
        }
    }

    /**
     * 比较模型名是否匹配。
     *
     * <p>Ollama 的模型名可能带或不带 {@code :latest} 后缀。
     * 配置里写 {@code qwen3:4b}、清单里报 {@code qwen3:4b} 是精确匹配；
     * 但如果用户只写了 {@code qwen3}，而实际模型叫 {@code qwen3:latest}，
     * 精确比较就会误判为"不存在"。这里做一次宽松匹配。
     *
     * @param actual   清单里的模型名
     * @param expected 配置里写的模型名
     * @return 视为同一个模型返回 {@code true}
     */
    private boolean matchesModelName(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (actual.equalsIgnoreCase(expected)) {
            return true;
        }
        // 去掉 ":latest" 再比一次
        String actualBase = actual.replace(":latest", "");
        String expectedBase = expected.replace(":latest", "");
        return actualBase.equalsIgnoreCase(expectedBase);
    }

    /**
     * {@code GET /api/tags} 的响应体：本机已下载的模型清单。
     *
     * <p>写成静态内部类而不是单独一个文件，是因为它只服务于探测这一个用途，
     * 没必要占一个顶层文件。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OllamaTagsResponse {

        /** 已下载的模型列表 */
        private List<ModelInfo> models;

        /**
         * 单个模型的信息。这里只取 {@code name}，其余字段（大小、修改时间等）忽略。
         */
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ModelInfo {

            /** 模型名，形如 {@code qwen3:4b} */
            private String name;
        }
    }
}
