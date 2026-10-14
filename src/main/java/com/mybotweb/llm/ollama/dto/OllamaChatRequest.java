package com.mybotweb.llm.ollama.dto;

import com.mybotweb.llm.model.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用 Ollama {@code /api/chat} 接口的请求体。
 *
 * <p>对应的 JSON 长这样：
 * <pre>{@code
 * {
 *   "model": "qwen3:4b",
 *   "messages": [
 *     { "role": "system", "content": "你是一个QQ机器人" },
 *     { "role": "user",   "content": "你好" }
 *   ],
 *   "stream": false
 * }
 * }</pre>
 *
 * <p><b>为什么单独建一个类，直接用 Map 不行吗？</b>
 * 用 Map 也能发出去，但代价是：字段名拼错了编译期发现不了，
 * 只能等运行时收到 Ollama 的报错。写成类之后，
 * 字段名由 IDE 补全和编译器把关，这是"通用 Spring 项目"的标准做法。
 *
 * @author mybotweb
 */
@Data
public class OllamaChatRequest {

    /** 模型名，例如 {@code qwen3:4b}。必须与 {@code ollama list} 里的名字完全一致 */
    private String model;

    /** 对话历史。顺序是"人设 → 历史 → 用户最新一句" */
    private List<Message> messages;

    /**
     * 是否流式返回。
     *
     * <p>这里固定为 {@code false}（一次性返回完整结果），因为 QQ 消息是
     * "凑齐了再发一条"的模式，逐字流式对我们没有意义，反而增加解析复杂度。
     * 注意不要删掉这个字段：Ollama 的默认值是 {@code true}，
     * 不显式传 {@code false} 会收到一串分块的 NDJSON，解析会失败。
     */
    private boolean stream = false;

    /**
     * Ollama 消息结构。
     *
     * <p>字段只有 {@code role} 和 {@code content}，比我们自己内部的
     * {@link ChatMessage} 更简单——不做映射会缺少 {@code role} 的枚举转换。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {

        /** 角色字面值：{@code system} / {@code user} / {@code assistant} */
        private String role;

        /** 消息正文 */
        private String content;
    }

    /**
     * 由内部对话模型构造 Ollama 请求体。
     *
     * <p>这个静态方法承担"适配"职责：把内部的 {@link ChatMessage}
     * 转成 Ollama 认识的结构。将来换云端大模型时，
     * 转换逻辑写在它自己的 DTO 里，两边互不影响。
     *
     * @param model    模型名
     * @param messages 内部格式的对话历史
     * @return Ollama 格式的请求体
     */
    public static OllamaChatRequest of(String model, List<ChatMessage> messages) {
        OllamaChatRequest request = new OllamaChatRequest();
        request.setModel(model);

        List<Message> converted = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            converted.add(new Message(
                    message.getRole().getValue(),   // 走枚举的 getValue()，保证是接口要的小写英文
                    message.getContent()
            ));
        }
        request.setMessages(converted);
        request.setStream(false);
        return request;
    }
}
