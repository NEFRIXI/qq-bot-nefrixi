package com.mybotweb.llm.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 一条对话消息 —— 这是喂给大模型的最小单位。
 *
 * <p>一次模型调用传进去的是一个 {@code List<ChatMessage>}，形如：
 * <pre>{@code
 * [
 *   ChatMessage.system("你是一个友好的QQ机器人..."),   // 人设
 *   ChatMessage.user("你好"),                        // 用户第 1 轮
 *   ChatMessage.assistant("你好呀！"),                // 机器人第 1 轮
 *   ChatMessage.user("今天天气怎么样")                 // 用户第 2 轮
 * ]
 * }</pre>
 * 模型看完整段才生成回复——所以"多轮记忆"的本质就是<b>把历史拼回去再发一遍</b>。
 * 这也解释了为什么上下文会爆：历史越长，每次都重发一遍，token 消耗线性增长。
 *
 * @author mybotweb
 */
@Data
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 这条消息是谁说的 */
    private ChatRole role;

    /** 消息正文 */
    private String content;

    /**
     * 私有构造器，强制使用下面三个静态工厂方法。
     * 这样调用处不用记"role 该填哪个枚举"，读起来也更像自然语言。
     */
    private ChatMessage(ChatRole role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * 构造一条系统设定消息（人设）。
     *
     * @param content 人设内容，通常来自 {@code prompts/*.md} 文件
     * @return 系统消息
     */
    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content);
    }

    /**
     * 构造一条用户消息。
     *
     * @param content 用户说的话
     * @return 用户消息
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content);
    }

    /**
     * 构造一条助手（模型）消息，用于把历史回复拼回上下文。
     *
     * @param content 模型之前说过的话
     * @return 助手消息
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content);
    }

    /**
     * 粗略估算这条消息占用的 token 数。
     *
     * <p><b>为什么要估算？</b> 规格第 6.5 节要求"按 token 数动态裁剪"：
     * 发请求前先估一下总长度，超了就丢掉最老的历史。
     *
     * <p><b>这个估算有多准？</b> 不准，而且对中文偏差较大。
     * 真实的 tokenizer 是按词表切分的，不是按字符。这里的规则是：
     * <ul>
     *   <li>ASCII 字符（英文、数字、标点）大约 4 个字符 = 1 token</li>
     *   <li>CJK 字符（中文、日文、韩文）大约 1 个字符 = 1 token（偏保守，宁可高估）</li>
     * </ul>
     * 保守估计的好处是：裁剪会早一点发生，不会因为低估而把请求撑爆。
     * 如果以后需要精确值，可以在返回的 usage 字段里读真实值并做校准。
     *
     * @return 估算的 token 数
     */
    public int estimateTokens() {
        if (this.content == null || this.content.isEmpty()) {
            // 即使内容为空，消息本身也有角色标记等开销，算 4 个 token
            return 4;
        }
        int cjkCount = 0;
        int otherCount = 0;
        for (int i = 0; i < this.content.length(); i++) {
            if (isCjk(this.content.charAt(i))) {
                cjkCount++;
            } else {
                otherCount++;
            }
        }
        // 中文按 1 字 1 token，其他字符按 4 字 1 token，最后加 4 作为角色开销
        return cjkCount + (otherCount / 4) + 4;
    }

    /**
     * 判断一个字符是否属于中日韩文字范围。
     *
     * @param c 待判断字符
     * @return 是 CJK 字符返回 {@code true}
     */
    private boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 中日韩统一表意文字（常用汉字）
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A 区
                || (c >= 0x3000 && c <= 0x303F)  // 中文标点（，。！？等）
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角字符
    }
}
