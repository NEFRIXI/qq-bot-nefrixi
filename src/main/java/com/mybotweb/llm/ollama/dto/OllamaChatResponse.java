package com.mybotweb.llm.ollama.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Ollama {@code /api/chat} 接口的响应体。
 *
 * <p>对应的 JSON 长这样（省略了无关字段）：
 * <pre>{@code
 * {
 *   "model": "qwen3:4b",
 *   "created_at": "2026-01-01T00:00:00Z",
 *   "message": { "role": "assistant", "content": "你好呀！" },
 *   "done": true,
 *   "total_duration": 1234567890,
 *   "eval_count": 42
 * }
 * }</pre>
 *
 * <p><b>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 为什么必须有？</b>
 * Ollama 的响应里有十几个字段（各种耗时统计、token 计数等），
 * 我们只关心其中两个。Jackson 默认遇到未知字段会抛
 * {@code UnrecognizedPropertyException}，加上这个注解就是告诉它
 * "多出来的字段直接忽略"。这是对接第三方接口时的常规防御——
 * 对方加个新字段，你的程序不该因此崩掉。
 *
 * @author mybotweb
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaChatResponse {

    /** 实际使用的模型名 */
    private String model;

    /** 模型生成的回复 */
    private Message message;

    /**
     * 是否已生成完毕。
     *
     * <p>因为我们请求时传了 {@code "stream": false}，正常情况下
     * 一次响应就是完整的，这个字段应当是 {@code true}。
     * 如果拿到 {@code false}，说明配置没生效（可能被改成了流式），
     * 这时 {@code message.content} 可能只是片段，需要警惕。
     */
    private boolean done;

    /**
     * 出错信息。Ollama 在模型不存在等情况下会返回这个字段而不是 HTTP 错误码，
     * 所以必须检查——只看 HTTP 状态会以为调用成功了。
     */
    private String error;

    /**
     * Ollama 的回复消息结构。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {

        /** 角色，正常应当是 {@code assistant} */
        private String role;

        /** 回复正文 */
        private String content;
    }

    /**
     * 提取回复正文，并在各种异常情况下给出可读的失败原因。
     *
     * <p>为什么不直接 {@code response.getMessage().getContent()}？
     * 因为 Ollama 的错误返回方式比较特殊：HTTP 状态码可能仍是 200，
     * 但响应体里带着 {@code error} 字段。不加判断的话，
     * 我们会拿到一个 {@code null} 的 content，最后变成给用户发一条空消息。
     *
     * @return 回复正文
     * @throws IllegalStateException 响应缺少内容时抛出，由上层转换为用户提示
     */
    public String extractContent() {
        if (this.error != null && !this.error.isBlank()) {
            throw new IllegalStateException("Ollama 返回错误：" + this.error);
        }
        if (this.message == null || this.message.getContent() == null) {
            throw new IllegalStateException("Ollama 响应里没有 message.content");
        }
        return this.message.getContent();
    }
}
