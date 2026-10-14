package com.mybotweb.cloud.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 云端大模型响应体 —— OpenAI 兼容格式。
 *
 * <p>对应的 JSON（省略了无关字段）：
 * <pre>{@code
 * {
 *   "id": "chatcmpl-xxx",
 *   "object": "chat.completion",
 *   "model": "deepseek-flash",
 *   "choices": [
 *     {
 *       "index": 0,
 *       "message": { "role": "assistant", "content": "你好呀！" },
 *       "finish_reason": "stop"
 *     }
 *   ],
 *   "usage": { "prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20 }
 * }
 * }</pre>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>★ 和 Ollama 最关键的一处差异：回复外面多包了一层数组</b>
 *
 * <pre>
 *   Ollama： response.message.content            ← 直接就是 message
 *   OpenAI： response.choices[0].message.content ← 要先从 choices 数组里取第一个
 * </pre>
 *
 * <p>为什么 OpenAI 要设计成数组？因为它支持 <b>n 参数</b>——一次请求生成多个
 * 候选回答供挑选。我们不用这个能力，但结构上必须跟着走，
 * 否则会一直拿到 {@code null} 却不知道错在哪。
 *
 * <p>所以要取正文，正确写法是：{@code getChoices().get(0).getMessage().getContent()}。
 * 本类的 {@link #extractContent()} 把这套取值和空值判断包起来了，
 * 调用方不用重复写。
 *
 * @author mybotweb
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatCompletionResponse {

    /** 响应 ID */
    private String id;

    /** 实际使用的模型名 */
    private String model;

    /**
     * 候选回答列表。
     *
     * <p>正常情况长度为 1。空列表意味着服务端没给出任何回答，
     * 这在 {@link #extractContent()} 里被当成错误处理。
     */
    private List<Choice> choices;

    /** token 用量统计 */
    private Usage usage;

    /**
     * 错误信息。
     *
     * <p>OpenAI 兼容服务在鉴权失败等情况下，可能返回一个带 {@code error}
     * 字段的 200 响应（也可能直接返回 4xx，那样会被 RestClient 抛异常）。
     * 两种都要能接住。
     */
    private ApiError error;

    /**
     * 单个候选回答。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {

        /** 序号 */
        private Integer index;

        /** 模型生成的这条消息 */
        private Message message;

        /**
         * 结束原因，例如 {@code stop}（正常结束）、{@code length}（被长度限制截断）。
         *
         * <p>看到 {@code length} 说明回答没说完就断了——本地小模型因为上下文小，
         * 这种情况比云端常见得多。
         */
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    /**
     * 回答消息。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {

        /** 角色，正常是 {@code assistant} */
        private String role;

        /** 回复正文 */
        private String content;
    }

    /**
     * token 用量。
     *
     * <p>云端按 token 计费，所以这个字段有实际意义——
     * 想统计花了多少钱就靠它。本地 Ollama 也有类似字段，
     * 但那边是免费的，所以之前没建对应的类。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {

        @JsonProperty("prompt_tokens")
        private Integer promptTokens;

        @JsonProperty("completion_tokens")
        private Integer completionTokens;

        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }

    /**
     * 错误结构。
     *
     * <p>用 {@link Map} 而不是精细的类，是因为各家的 error 字段结构不完全一致，
     * 而我们只需要把原始内容打出来给人看，不需要按字段处理。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiError {

        /** 错误信息 */
        private String message;

        /** 错误类型 */
        private String type;

        /** 错误码 */
        private String code;
    }

    /**
     * 提取回复正文，并在各种异常情况下给出可读的失败原因。
     *
     * <p>为什么不直接 {@code getChoices().get(0).getMessage().getContent()}？
     * 因为那条链路上有四个可能为 {@code null} 的地方
     * （choices、第一个元素、message、content），
     * 任何一处为 {@code null} 都会抛 {@code NullPointerException}，
     * 而堆栈信息完全看不出"是服务端返回了空内容"这个真实原因。
     *
     * @return 回复正文
     * @throws IllegalStateException 响应缺少内容时抛出，由上层转换成给用户的提示
     */
    public String extractContent() {
        if (this.error != null && this.error.getMessage() != null) {
            throw new IllegalStateException("云端大模型返回错误：" + this.error.getMessage());
        }
        if (this.choices == null || this.choices.isEmpty()) {
            throw new IllegalStateException("云端大模型响应里没有 choices 内容");
        }
        Choice first = this.choices.get(0);
        if (first == null || first.getMessage() == null || first.getMessage().getContent() == null) {
            throw new IllegalStateException("云端大模型响应里没有 message.content");
        }
        return first.getMessage().getContent();
    }

    /**
     * 生成一句话的用量描述，供日志使用。
     *
     * <p>云端是按 token 收费的，所以每次调用都记一下用量很有价值——
     * 出问题时能立刻看出"是不是某次请求把上下文撑大了"。
     *
     * @return 形如 {@code 输入 120 + 输出 45 = 165 tokens}；没有用量信息时返回"用量未知"
     */
    public String describeUsage() {
        if (this.usage == null) {
            return "用量未知";
        }
        return String.format("输入 %s + 输出 %s = %s tokens",
                this.usage.getPromptTokens(),
                this.usage.getCompletionTokens(),
                this.usage.getTotalTokens());
    }
}
