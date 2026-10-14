package com.mybotweb.llm.model;

/**
 * 对话消息的角色 —— 对应 OpenAI 兼容接口里每条消息的 {@code role} 字段。
 *
 * <p>大模型的对话接口接收的是一个"消息数组"，每条消息都要标明是谁说的。
 * 模型靠这个字段区分"设定"、"用户输入"和"自己之前说过的话"。
 *
 * <p>本地 Ollama 和云端 DeepSeek 都遵循同一套角色约定，
 * 这正是"一套代码能换后端"的前提。
 *
 * @author mybotweb
 */
public enum ChatRole {

    /**
     * 系统设定 —— 通常只有一条，放在数组最前面。
     *
     * <p>这就是规格第 6.3 节里那些 md 文件（{@code chat_default.md} 等）的去处：
     * 文件内容读出来，作为 {@code system} 角色的消息塞进去。
     *
     * <p>本地 7B/4B 模型上下文小，这个角色的内容要尽量短——
     * 它和工具定义、历史对话争抢同一块上下文预算。
     */
    SYSTEM("system"),

    /** 用户说的话 */
    USER("user"),

    /** 模型自己之前的回复 */
    ASSISTANT("assistant");

    /** 传给模型接口时使用的字面值，必须是这一串小写英文 */
    private final String value;

    ChatRole(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 按接口字面值反查枚举。
     *
     * <p>用途：解析模型返回的历史记录（比如从数据库或缓存里读回来时，
     * 存的是 JSON，角色是字符串）。
     *
     * @param value 接口字面值，如 {@code "user"}
     * @return 对应的枚举；找不到时返回 {@link #USER} 作为兜底（宁可当成用户输入，也不要抛异常打断对话）
     */
    public static ChatRole fromValue(String value) {
        if (value != null) {
            for (ChatRole role : values()) {
                if (role.value.equalsIgnoreCase(value.strip())) {
                    return role;
                }
            }
        }
        return USER;
    }
}
