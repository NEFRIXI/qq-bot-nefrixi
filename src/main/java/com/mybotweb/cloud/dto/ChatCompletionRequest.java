package com.mybotweb.cloud.dto;

import com.mybotweb.llm.model.ChatMessage;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 云端大模型请求体 —— OpenAI 兼容格式。
 *
 * <p>DeepSeek、以及绝大多数国内大模型服务，都采用这套和 OpenAI 一致的接口格式。
 * 所以本类不是"给 DeepSeek 专用的"，<b>换任何一家 OpenAI 兼容服务都不用改它</b>——
 * 只要改配置里的 base-url 和 model 即可。
 *
 * <p>对应的 JSON：
 * <pre>{@code
 * {
 *   "model": "deepseek-flash",
 *   "messages": [
 *     { "role": "system", "content": "你是一个QQ机器人" },
 *     { "role": "user",   "content": "你好" }
 *   ],
 *   "stream": false
 * }
 * }</pre>
 *
 * <p><b>和 Ollama 的请求体长得很像</b>（这也是为什么两边的代码结构几乎一样），
 * 但有两处关键差异：
 * <ul>
 *   <li>路径不同：OpenAI 兼容是 {@code /chat/completions}，Ollama 是 {@code /api/chat}</li>
 *   <li><b>响应结构完全不同</b>：OpenAI 的回复藏在 {@code choices[0].message.content}
 *       里（多一层数组），而 Ollama 直接在 {@code message.content}</li>
 * </ul>
 * 第二点是初学时最容易踩的坑——解析路径写错了，表现就是"拿到了响应但内容为 null"。
 *
 * @author mybotweb
 */
@Data
public class ChatCompletionRequest {

    /** 模型名，例如 {@code deepseek-flash} */
    private String model;

    /** 对话历史，顺序是"人设 → 历史 → 用户最新一句" */
    private List<Message> messages;

    /**
     * 是否流式返回。
     *
     * <p>固定 {@code false}：QQ 消息是"凑齐了再发一条"的模式，
     * 逐字流式对我们没有意义，反而让解析变复杂
     * （流式响应是一串 SSE 分块，不是单个 JSON）。
     */
    private boolean stream = false;

    /**
     * 单条消息。
     *
     * <p>字段和 Ollama 那边一样是 {@code role} + {@code content}，
     * 但这里的 {@code role} 是<b>接口字面值</b>（{@code system}/{@code user}/{@code assistant}），
     * 不是我们内部的枚举。
     */
    @Data
    public static class Message {

        /** 角色字面值 */
        private String role;

        /** 消息正文 */
        private String content;

        public Message() {
        }

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * 由内部对话模型构造请求体。
     *
     * @param model    模型名
     * @param messages 内部格式的对话历史
     * @return OpenAI 格式的请求体
     */
    public static ChatCompletionRequest of(String model, List<ChatMessage> messages) {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel(model);

        List<Message> converted = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            // 走枚举的 getValue() 拿到接口要的小写英文，而不是 name()
            converted.add(new Message(message.getRole().getValue(), message.getContent()));
        }
        request.setMessages(converted);
        request.setStream(false);
        return request;
    }
}
