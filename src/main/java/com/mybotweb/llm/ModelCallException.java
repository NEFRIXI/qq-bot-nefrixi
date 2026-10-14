package com.mybotweb.llm;

/**
 * 模型调用失败 —— <b>带一句"可以直接发给用户看"的话</b>。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么要单独定义它，而不是直接抛 {@code IllegalStateException}</b>
 *
 * <p>之前所有失败都被压成一个普通异常，然后由 {@code MessageDispatcher}
 * 按"提供方是 ollama 还是 cloud"回一句写死的话。结果是：
 *
 * <pre>
 *   余额不足（HTTP 402）    → "我这边连不上云端服务"
 *   密钥无效（HTTP 401）    → "我这边连不上云端服务"
 *   模型名写错（HTTP 404）  → "我这边连不上云端服务"
 *   真的网络不通            → "我这边连不上云端服务"
 * </pre>
 *
 * <p>四种完全不同的原因，用户看到的是同一句话。尤其是<b>"余额不足"被说成
 * "连不上"</b>——这会让人去查网络、查服务器、查防火墙，方向从头就错了。
 * 实际发生过一次：账户欠费，用户照着"连不上"排查了很久。
 *
 * <p>所以本异常把两件事分开装：
 * <ul>
 *   <li>{@link #getUserMessage()} —— 给用户看的<strong>一句话</strong>。
 *       不含技术细节、不含堆栈，可以直接当作聊天回复发出去</li>
 *   <li>{@code getMessage()} / {@code getCause()} —— 给日志看的完整信息
 *       （HTTP 状态码、耗时、地址、模型名、响应体）</li>
 * </ul>
 *
 * <p>调用方（{@code MessageDispatcher}）只需要把 {@code getUserMessage()}
 * 原样回给用户，把 {@code getMessage()} 记进日志 —— 不需要再自己判断
 * "这句话该怎么说"。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>写 {@code userMessage} 时的两条约定</b>
 *
 * <ol>
 *   <li><b>说人话，不要甩术语</b>：用户看不懂 "HTTP 402 Insufficient Balance"，
 *       但看得懂"余额不足了"。状态码可以带上（方便管理员搜），但不能只有状态码</li>
 *   <li><b>能给出下一步就给</b>："稍后再试"是最没用的提示。能说是谁的问题、
 *       该找谁、该做什么，就都写上</li>
 * </ol>
 *
 * @see com.mybotweb.llm.cloud.CloudChatModelClient
 * @see com.mybotweb.llm.ollama.OllamaChatModelClient
 */
public class ModelCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 可以直接作为回复发给用户的一句话。 */
    private final String userMessage;

    /**
     * @param userMessage       给用户看的一句话（不含技术细节）
     * @param technicalMessage  给日志看的完整信息
     * @param cause             原始异常，保留堆栈
     */
    public ModelCallException(String userMessage, String technicalMessage, Throwable cause) {
        super(technicalMessage, cause);
        this.userMessage = userMessage;
    }

    /**
     * @param userMessage      给用户看的一句话
     * @param technicalMessage 给日志看的完整信息
     */
    public ModelCallException(String userMessage, String technicalMessage) {
        this(userMessage, technicalMessage, null);
    }

    /**
     * @return 可以直接发给用户的一句话
     */
    public String getUserMessage() {
        return this.userMessage;
    }
}
