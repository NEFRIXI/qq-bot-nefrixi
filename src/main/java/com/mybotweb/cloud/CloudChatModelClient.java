package com.mybotweb.cloud;

import com.mybotweb.cloud.dto.ChatCompletionRequest;
import com.mybotweb.cloud.dto.ChatCompletionResponse;
import com.mybotweb.config.AppProperties;
import com.mybotweb.llm.ChatModelClient;
import com.mybotweb.llm.ModelCallException;
import com.mybotweb.llm.model.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;

/**
 * 云端大模型客户端 —— 调 DeepSeek（OpenAI 兼容接口）。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>★ 它和 {@code OllamaChatModelClient} 是"同时装配、运行期二选一"</b>
 *
 * <p><b>注意：这里曾经写的是"按 {@code app.role} 互斥装配"，那个设计已经取消了。</b>
 * 现在两个客户端都带 {@code @Component}，都无条件装配，谁上场由
 * {@code route/ModelRouterImpl} 在<b>每次聊天时</b>按策略现算：
 *
 * <table border="1">
 *   <tr><th>{@code app.route.strategy}</th><th>本地可用时</th><th>本地不可用时</th></tr>
 *   <tr>
 *     <td>{@code LOCAL_FIRST}（默认）</td>
 *     <td>本地</td>
 *     <td><b>本类</b>（降级）</td>
 *   </tr>
 *   <tr><td>{@code CLOUD_FIRST}</td><td colspan="2"><b>本类</b></td></tr>
 *   <tr><td>{@code CLOUD_ONLY}</td><td colspan="2"><b>本类</b></td></tr>
 *   <tr>
 *     <td>{@code LOCAL_ONLY}</td>
 *     <td>本地</td>
 *     <td>抛 {@code NoAvailableModelException}，不降级</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么改成"同时装配"？</b> 因为"这段代码跑在哪台机器上"并不能决定
 * "这一次该用谁"：同一台机器可能既有本机模型服务又配了 API Key，
 * 平时想省钱用本地、本地忙不过来时想临时切云端。互斥装配把这些可能性
 * 全都锁死了，所以改成运行期判断。
 *
 * <p><b>{@code app.role} 现在还剩什么用？</b> 只剩两处：
 * <ul>
 *   <li>{@code /status} 命令用它显示"这台机器的角色"</li>
 *   <li>{@code MockChatModelClient} 用它做装配判断（{@code app.role=mock}）</li>
 * </ul>
 * 它<b>不再影响算力选择</b>。改它不会让聊天从云端切到本地 ——
 * 想切本地请改 {@code app.route.strategy}，或者让本机模型服务跑起来。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>怎么配置</b>
 *
 * <p>密钥写在 {@code application-local.yml} 里（已被 .gitignore 忽略），
 * 或者用环境变量 {@code LLM_API_KEY}：
 * <pre>{@code
 * app:
 *   llm:
 *     cloud:
 *       api-key: ${LLM_API_KEY:}
 * }</pre>
 *
 * <p><b>绝对不要把密钥明文写进 application.yml</b> —— 那个文件是要提交的。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>失败时会说什么</b>
 *
 * <p>调用失败一律抛 {@link com.mybotweb.llm.ModelCallException}，
 * 其中的 {@code getUserMessage()} 是<b>按 HTTP 状态码分过类</b>的，
 * 会被直接回给用户：
 *
 * <pre>
 *   401  → 密钥不对
 *   402  → 余额不足（不是"连不上"！）
 *   404  → 模型名写错了
 *   429  → 被限流了
 *   连接超时 → 这才是真正的"连不上云端服务"
 * </pre>
 *
 * <p>分出这些类别的理由见 {@code ModelCallException} 的类注释。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class CloudChatModelClient implements ChatModelClient {

    /** 调用超时（秒）。云端服务稳定，60 秒足够 */
    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    /** 云端配置 */
    private final AppProperties.Llm.Cloud config;

    /** HTTP 客户端 */
    private final RestClient restClient;

    public CloudChatModelClient(AppProperties appProperties) {
        this.config = appProperties.getLlm().getCloud();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
        factory.setReadTimeout(Duration.ofSeconds(this.config.getTimeoutSeconds()));

        this.restClient = RestClient.builder()
                .baseUrl(this.config.getBaseUrl())
                .requestFactory(factory)
                .build();

        log.info("""
                
                ================================================================
                云端大模型客户端已就绪（"装上了"不等于"这次会用到"）
                ----------------------------------------------------------------
                接口地址：{}
                使用模型：{}
                密钥状态：{}
                
                说明：这个客户端是无条件装配的 —— 它总会被创建出来，
                但"这条消息用不用它"由 ModelRouter 在每次聊天时现算：
                  · strategy = LOCAL_FIRST（默认）→ 本地可用就用本地，本地不可用才降级到这里
                  · strategy = CLOUD_FIRST / CLOUD_ONLY → 直接用这里
                  · strategy = LOCAL_ONLY → 本地不可用时拒绝服务，不会偷偷降级
                
                当前 app.role = {}。它只用于 /status 显示和 mock 模式的装配判断，
                不影响算力选择。早期版本是用 role 二选一装配的，那个设计已经取消。
                ================================================================""",
                this.config.getBaseUrl(),
                this.config.getModel(),
                hasApiKey() ? "已配置 ✅" : "❌ 未配置！聊天会失败",
                appProperties.getRole());
    }

    /**
     * 调用云端大模型生成回复。
     *
     * @param messages 对话历史（人设在最前，用户最新一句在最后）
     * @return 模型生成的文本
     * @throws IllegalStateException 未配置密钥、调用失败或响应异常时抛出
     */
    @Override
    public String chat(List<ChatMessage> messages) {
        if (!hasApiKey()) {
            // 这一步单独拦一下，因为"没配密钥"和"调用失败"要给的提示完全不同。
            // 前者是配置问题（去填密钥），后者是运行时问题（看日志）
            throw new ModelCallException(
                    "云端的密钥还没配置，这条回不了，麻烦管理员看一下配置。",
                    "云端大模型的 API Key 未配置：请填写 application-local.yml 里的 "
                            + "app.llm.cloud.api-key，或者设置环境变量 LLM_API_KEY。");
        }

        ChatCompletionRequest request = ChatCompletionRequest.of(this.config.getModel(), messages);

        long startedAt = System.currentTimeMillis();
        log.debug("调用云端模型 {}，上下文 {} 条消息（估算 {} tokens）",
                this.config.getModel(),
                messages.size(),
                messages.stream().mapToInt(ChatMessage::estimateTokens).sum());

        ChatCompletionResponse response;
        try {
            response = this.restClient.post()
                    // OpenAI 兼容接口的固定路径。注意不是 Ollama 的 /api/chat
                    .uri("/chat/completions")
                    // 鉴权走请求头，格式是 "Bearer {密钥}"，注意中间那个空格
                    .header("Authorization", "Bearer " + this.config.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ChatCompletionResponse.class);

        } catch (RestClientResponseException e) {
            // ★ HTTP 层错误：服务端回了 4xx/5xx，能拿到状态码 —— 于是能说清到底是什么问题。
            // 这一段是"让错误信息说真话"的核心：以前 401/402/404/429 统统报
            // "我这边连不上云端服务"，用户照着"连不上"去查网络和防火墙，方向从头就是错的。
            // 真实案例：账户欠费（402），却被报成"连不上"，排查了很久
            long elapsed = System.currentTimeMillis() - startedAt;
            int status = e.getStatusCode().value();

            String userMessage = switch (status) {
                case 401 -> "云端的密钥好像不对（401），这条回不了，麻烦管理员看一下配置。";
                case 402 -> "云端账户余额不足了（402）。可以让管理员充值，或者改用本地模型。";
                case 403 -> "云端拒绝了这次请求（403），可能是这个密钥没有调用该模型的权限。";
                case 404 -> "云端说找不到这个模型（404）：" + this.config.getModel()
                        + "。麻烦管理员核对一下模型名。";
                case 429 -> "云端请求太频繁被限流了（429），等一会再试试。";
                default -> status >= 500
                        ? "云端服务自己出错了（" + status + "），稍后再试试。"
                        : "云端拒绝了这次请求（" + status + "），稍后再试试。";
            };

            throw new ModelCallException(userMessage, String.format(
                    "调用云端大模型失败（HTTP %d，耗时 %d ms，地址 %s，模型 %s）：%s",
                    status, elapsed, this.config.getBaseUrl(), this.config.getModel(),
                    e.getResponseBodyAsString()), e);

        } catch (ResourceAccessException e) {
            // 连接层错误：连不上、连接超时、读超时、DNS 解析失败。
            // 这才是真正意义上的"连不上云端服务"—— 只有这一类才该这么报
            long elapsed = System.currentTimeMillis() - startedAt;
            throw new ModelCallException(
                    "我这边连不上云端服务（网络不通或超时），稍后再试试。",
                    String.format("连接云端大模型失败（耗时 %d ms，地址 %s）：%s",
                            elapsed, this.config.getBaseUrl(), e.getMessage()), e);

        } catch (Exception e) {
            // 剩下的归到这里（响应解析失败、返回结构不符等）。
            // 留一条通用分支兜底，避免将来 RestClient 换了异常类型就把堆栈漏给用户
            long elapsed = System.currentTimeMillis() - startedAt;
            throw new ModelCallException(
                    "调用云端模型时出了点意外，稍后再试试。",
                    String.format("调用云端大模型失败（耗时 %d ms，地址 %s，模型 %s）：%s",
                            elapsed, this.config.getBaseUrl(), this.config.getModel(), e.getMessage()), e);
        }

        if (response == null) {
            throw new IllegalStateException("云端大模型返回了空响应");
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        String content = response.extractContent();

        // 云端按 token 收费，所以每次调用都把用量记进日志。
        // 出账单时对着这个能看出是哪类请求在花钱
        log.info("云端模型生成完成：耗时 {} ms，回复 {} 字，{}",
                elapsed, content.length(), response.describeUsage());

        return content;
    }

    @Override
    public String getProviderName() {
        return "cloud";
    }

    /**
     * 判断云端大模型是否可用。
     *
     * <p><b>只检查密钥配没配，绝不发真实请求。</b>
     * 这一点很重要：探测会花钱。本地 Ollama 那边我们可以随便探测
     * （{@code /api/tags} 是免费接口，还能顺便查出本机有哪些模型），
     * 但云端没有任何"免费的健康检查接口"——
     * 最接近的做法是发一次极短的对话请求，那也要计费。
     *
     * <p>所以退而求其次：<b>配了密钥就当它可用</b>。
     * 密钥无效或余额不足这类问题，会在真正聊天时暴露出来，
     * 那时错误信息会说明具体原因（401 / 402 等）。
     *
     * @return 配置了密钥返回 {@code true}
     */
    @Override
    public boolean isAvailable() {
        return hasApiKey();
    }

    /**
     * 返回当前配置的模型名。
     *
     * <p>云端没有"列出所有模型"的接口（各家支持哪些模型要看文档，
     * 而且是跟着账号权限走的），所以这里只能返回配置里指定的那一个。
     *
     * <p>这会影响 {@code /model} 命令的表现：在云端模式下，
     * 那条命令只能显示当前模型，没法像本地那样列出可选清单。
     *
     * @return 只含当前配置模型的列表
     */
    @Override
    public List<String> listAvailableModels() {
        String model = this.config.getModel();
        return (model == null || model.isBlank()) ? List.of() : List.of(model);
    }

    /**
     * 密钥是否已配置。
     *
     * @return 配了非空白值返回 {@code true}
     */
    private boolean hasApiKey() {
        return this.config.getApiKey() != null && !this.config.getApiKey().isBlank();
    }
}
