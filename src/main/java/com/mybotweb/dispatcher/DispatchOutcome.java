package com.mybotweb.dispatcher;

import lombok.Getter;

/**
 * 消息处理结果 —— 描述"这条消息最后被怎么处置了"。
 *
 * <p><b>为什么需要这个类？</b> 最初 {@code handle()} 直接返回一个 {@code String}，
 * 用 {@code null} 表示"没有回复"。实际调试时立刻暴露了问题：
 * 没收到回复时，<b>根本分不清是因为什么</b>——是消息是空的？
 * 是被限流了？还是模型返回了空白？三种情况的处理方式完全不同，
 * 但对外表现都是"接口返回了一个空 data"。
 *
 * <p>这个类把"没回复"这个笼统的状态拆成了明确的原因。
 * 对生产链路没有任何影响（回复照样发出去），
 * 但调试接口和日志能给出准确答案——这是很划算的一笔复杂度投资，
 * 因为"不知道发生了什么"是最耗时的一类问题。
 *
 * @author mybotweb
 */
@Getter
public class DispatchOutcome {

    /**
     * 消息被忽略的原因。
     */
    @Getter
    public enum IgnoreReason {

        /** 没有没被忽略，正常产生了回复 */
        NONE("正常处理"),

        /** 消息里没有 userId，不知道该回复给谁 */
        NO_USER_ID("消息缺少 userId"),

        /** 消息内容是空的（用户误发或只发了空格） */
        EMPTY_CONTENT("消息内容为空"),

        /** 触发限流，被丢弃 */
        RATE_LIMITED("触发限流"),

        /** 处理完了但结果为空，没有东西可发 */
        EMPTY_REPLY("处理结果为空"),

        /**
         * 热聊模式下机器人自己决定不搭话。
         *
         * <p>和 {@link #EMPTY_REPLY} 的区别很重要：后者是"出了意外，
         * 本该有内容却是空的"，前者是"模型主动选择沉默，结果完全正常"。
         * 混在一起会让调试时把正常行为当成故障查。
         */
        HOT_CHAT_SILENT("热聊模式：机器人选择不搭话"),

        /** 热聊模式下消息只进了上下文缓冲，还没到搭话的时机 */
        HOT_CHAT_BUFFERED("热聊模式：消息已进入上下文，暂不搭话"),

        /**
         * 群里说了句话但没 @ 机器人，而且热聊模式没开 —— 只当上下文，不回复。
         *
         * <p><b>这不是异常，是常态。</b> 开了「接收所有消息」之后，群里绝大多数
         * 消息都属于这一类。参考实现（OpenClaw 的 group messages 文档）管这个
         * 叫 {@code store for context only}：消息收下但不回应。
         *
         * <p>单独列出来的意义在于让日志能区分"我没理它"和"我出错了"——
         * 否则一天下来满屏都是"未产生回复"，真正的问题会被淹掉。
         */
        NOT_MENTIONED("群消息未@机器人，只存上下文不回复");

        private final String description;

        IgnoreReason(String description) {
            this.description = description;
        }
    }

    /** 实际要发出的回复文本；被忽略时为 {@code null} */
    private final String reply;

    /** 被忽略的原因；正常处理时为 {@link IgnoreReason#NONE} */
    private final IgnoreReason reason;

    private DispatchOutcome(String reply, IgnoreReason reason) {
        this.reply = reply;
        this.reason = reason;
    }

    /**
     * 构造一个"正常回复"的结果。
     *
     * @param reply 回复文本
     * @return 结果对象
     */
    public static DispatchOutcome replied(String reply) {
        return new DispatchOutcome(reply, IgnoreReason.NONE);
    }

    /**
     * 构造一个"被忽略"的结果。
     *
     * @param reason 忽略原因
     * @return 结果对象
     */
    public static DispatchOutcome ignored(IgnoreReason reason) {
        return new DispatchOutcome(null, reason);
    }

    /**
     * 本次是否产生了回复。
     *
     * @return 有回复返回 {@code true}
     */
    public boolean hasReply() {
        return this.reply != null && !this.reply.isBlank();
    }

    /**
     * 给调用方看的一句话描述。
     *
     * <p>用途：调试接口把它放进响应里，让调用者一眼看出发生了什么，
     * 而不是对着一个空的 {@code data} 猜。
     *
     * @return 形如 {@code 已回复} 或 {@code 已忽略（触发限流）}
     */
    public String describe() {
        if (hasReply()) {
            return "已回复";
        }
        return "已忽略（" + reason.getDescription() + "）";
    }
}
