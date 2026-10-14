package com.mybotweb.llm;

import com.mybotweb.llm.model.ChatMessage;

import java.util.List;

/**
 * 大模型客户端 —— 负责"这次回复交给谁算"。
 *
 * <p><b>这是项目里的第二个核心抽象。</b> 规格里有两套算力：
 * <ul>
 *   <li><b>本地</b>：WSL 里的 Ollama 跑 4B/7B 小模型，免费、不限量，但笨</li>
 *   <li><b>云端</b>：DeepSeek 等大模型，聪明，但按 token 收费</li>
 * </ul>
 * 两者对上层是同一件事："给我一段对话历史，还我一句回复"。
 * 把这句话定义成接口，上层就不必知道背后是谁在算，
 * 也就能实现"本地在线走本地、离线降级云端"以及管理员口令实时切换。
 *
 * <p>现有的实现：
 * <table border="1">
 *   <tr><th>实现类</th><th>后端</th><th>装配方式</th></tr>
 *   <tr>
 *     <td>{@code OllamaChatModelClient}</td>
 *     <td>本机模型服务（Windows 上跑在 WSL 里）</td>
 *     <td>{@code @Component}，无条件</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CloudChatModelClient}</td>
 *     <td>DeepSeek 等 OpenAI 兼容接口</td>
 *     <td>{@code @Component}，无条件</td>
 *   </tr>
 *   <tr>
 *     <td>{@code MockChatModelClient}</td>
 *     <td>不回模型，直接回声（离线开发用）</td>
 *     <td>{@code @ConditionalOnProperty(app.role=mock)}</td>
 *   </tr>
 * </table>
 *
 * <p><b>前两个是同时装配的</b>，谁上场由 {@code route/ModelRouter} 在每次聊天时
 * 按 {@code app.route.strategy} 加上本地算力是否可用现算。
 * 这里已经<b>没有</b>"改一个配置项二选一"这回事了 ——
 * 早期靠 {@code app.llm.provider} 切换的设计早已删除，
 * 类注释里如果还写着它，那是不对的。
 *
 * @author mybotweb
 */
public interface ChatModelClient {

    /**
     * 把一段对话历史交给模型，拿回它的回复。
     *
     * <p><b>实现约定</b>：
     * <ul>
     *   <li>传入的 {@code messages} 已经由调用方拼好——人设在最前，历史在中间，
     *       用户这一句在最后。实现类<b>不需要</b>再加工顺序。</li>
     *   <li>实现要自己处理超时和重试策略。超时时间从配置里读
     *       （本地小模型出字慢，超时要给到分钟级）。</li>
     *   <li>调用失败时抛出运行时异常即可，由上层决定是降级（换另一个模型）
     *       还是给用户一句道歉。实现类不应该自己吞掉异常假装成功——
     *       那样上层就失去了降级的机会。</li>
     * </ul>
     *
     * @param messages 对话历史，至少包含一条消息
     * @return 模型生成的回复文本
     */
    String chat(List<ChatMessage> messages);

    /**
     * 返回提供方标识，用于日志和 {@code /status} 命令展示。
     *
     * <p>取值应当与配置项 {@code app.llm.provider} 对应，
     * 例如 {@code "ollama"}、{@code "cloud"}、{@code "mock"}。
     *
     * @return 提供方标识
     */
    String getProviderName();

    /**
     * 探测这个算力当前是否可用。
     *
     * <p><b>为什么需要它？</b> 路由策略的核心判断就是"本地在线吗"：
     * <ul>
     *   <li>本地实现：去请求 Ollama 的健康检查接口，超时或连不上就返回 {@code false}</li>
     *   <li>云端实现：检查 API Key 是否配置了（没配密钥就等于不可用）</li>
     * </ul>
     * 有了这个方法，上层就能写"本地可用走本地，否则降级云端"，
     * 而不必知道具体怎么探测。
     *
     * <p>注意：这个方法<b>会被频繁调用</b>（比如每次收到消息都判断一次），
     * 所以实现里要做缓存，不能每次都真的发一次 HTTP 请求。
     *
     * @return 可用返回 {@code true}
     */
    boolean isAvailable();

    /**
     * 列出这个后端<b>当前实际可用</b>的模型名。
     *
     * <p><b>为什么需要这个方法？</b> {@code /model} 命令要让用户看到
     * "能切到哪几个模型"。最初的做法是读配置文件里的清单，
     * 但那样有个很烦人的问题：<b>每次 {@code ollama pull} 下载了新模型，
     * 都得回来改配置、重启应用，才能看到它。</b>
     * 而"本机有哪些模型"这件事，Ollama 自己最清楚——直接问它就行。
     * 这样新下载的模型立刻可见，也永远不会出现
     * "配置里写了但本机其实没下载"这种自相矛盾的状态。
     *
     * <p>默认实现返回空列表，含义是"这个后端不支持列举"
     * （比如 {@code MockChatModelClient} 就没有模型的概念）。
     * 调用方拿到空列表时应当回退到别的信息来源，而不是当成"一个模型都没有"。
     *
     * @return 模型名列表（建议排好序）；不支持列举时返回空列表，<b>不会是 null</b>
     */
    default List<String> listAvailableModels() {
        return List.of();
    }
}
