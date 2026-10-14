package com.mybotweb.route;

/**
 * 路由策略 —— 决定"这次请求交给谁来算"。
 *
 * <p>对应规格第 7.1 节。注意它与"模式"是两个正交的维度：
 * <ul>
 *   <li><b>路由策略</b>（本枚举）决定<b>谁来算</b>：本地小模型 / 云端大模型</li>
 *   <li><b>模式</b>（纯聊天 / Agent）决定<b>怎么算</b>：带不带工具、上下文怎么拼</li>
 * </ul>
 * 两者组合出各种场景，例如"路由=本地 + 模式=纯聊天"是最省钱的组合，
 * 而"路由=云端 + 模式=Agent"是能力最强的组合。规格第 7.4 节列了对照表。
 *
 * <p><b>本枚举的定义现在就写全，是因为它是配置的值域</b>——
 * 数据库表、配置项、命令参数都要引用它，先定下来避免各处写裸字符串。
 *
 * <p><b>路由判断逻辑已经实现了</b>（{@code ModelRouterImpl}），
 * 调用点在 {@code MessageDispatcher.handleChat} 里。
 * 这段注释以前写的是"真正的判断逻辑还没实现"，那已经不对了。
 *
 * @author mybotweb
 */
public enum RouteStrategy {

    /**
     * 本地优先 —— 本地节点在线就用本地，离线自动降级到云端。
     *
     * <p>这是日常运行的默认策略：在家时省钱，出门后自动切云端，用户无感。
     * 它依赖"本地在线状态"这个探测能力。
     */
    LOCAL_FIRST("本地优先，离线降级云端"),

    /**
     * 云端优先 —— 不管本地在不在，都走云端。
     *
     * <p>用途：本地模型效果不好时临时切过来（比如某类问题本地 4B 答得很差），
     * 以及做效果对比。
     */
    CLOUD_FIRST("云端优先"),

    /**
     * 仅本地 —— 只用本地，本地离线则直接报错，不降级。
     *
     * <p>用途有两个：<b>省钱模式</b>（坚决不产生云端费用），
     * 以及<b>测试模式</b>（验证本地模型独当一面的效果，不让云端兜底掩盖问题）。
     */
    LOCAL_ONLY("仅本地，离线则报错"),

    /**
     * 仅云端 —— 只用云端大模型。
     *
     * <p>用途：需要最佳效果的场景，以及本地机器要关机维护时的兜底策略。
     */
    CLOUD_ONLY("仅云端");

    /** 中文说明，用于 {@code /status} 和 {@code /strategy} 的输出 */
    private final String description;

    RouteStrategy(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 按名称解析策略，大小写不敏感。
     *
     * <p>用途：{@code /strategy local_first} 这类命令参数要先转成枚举。
     * 命令参数的来源是用户在 QQ 里手打的文本，必须容错——
     * 用户可能打成 {@code Local_First} 或者 {@code LOCAL_FIRST}。
     *
     * @param name 策略名，例如 {@code "local_first"}
     * @return 对应的策略；无法识别时返回 {@code null}（由调用方决定怎么提示用户）
     */
    public static RouteStrategy fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.strip().toUpperCase().replace('-', '_');
        for (RouteStrategy strategy : values()) {
            if (strategy.name().equals(normalized)) {
                return strategy;
            }
        }
        return null;
    }

    /**
     * 生成一句话说明"这个策略是什么意思"，供命令回显。
     *
     * @return 形如 {@code LOCAL_FIRST（本地优先，离线降级云端）}
     */
    public String toDisplayText() {
        return name() + "（" + description + "）";
    }

    /**
     * 判断该策略是否依赖本地算力。
     *
     * <p>用途：本地离线时的降级判断。{@code LOCAL_ONLY} 和 {@code LOCAL_FIRST}
     * 都需要本地，但两者对待离线的态度不同（前者报错，后者降级），
     * 所以判定时要分清"是否需要本地"和"本地没了怎么办"两件事。
     *
     * @return 需要本地算力返回 {@code true}
     */
    public boolean requiresLocal() {
        return this == LOCAL_FIRST || this == LOCAL_ONLY;
    }

    /**
     * 判断本地离线时是否允许降级到云端。
     *
     * @return 允许降级返回 {@code true}
     */
    public boolean allowsFallbackToCloud() {
        return this == LOCAL_FIRST || this == CLOUD_FIRST || this == CLOUD_ONLY;
    }
}
