package com.mybotweb.channel.model;

/**
 * QQ 消息的会话场景。
 *
 * <p>腾讯的消息事件按场景分不同的事件类型，我们处理时需要知道
 * "这条消息是从哪来的"，因为不同场景的<b>被动回复时效不一样</b>：
 * <ul>
 *   <li>{@link #C2C} 单聊：被动回复有效期 <b>60 分钟</b>，每条可回复 4 次</li>
 *   <li>{@link #GROUP} 群聊：被动回复有效期 <b>5 分钟</b>，每条可回复 5 次</li>
 *   <li>{@link #CHANNEL} 频道：被动回复有效期 5 分钟</li>
 * </ul>
 * 这个差别在"本地小模型算得慢"的时候很关键——群聊只有 5 分钟窗口，
 * 而单聊有 60 分钟，容错空间大得多。
 *
 * @author mybotweb
 */
public enum QqScene {

    /** 单聊（私聊）。对应腾讯事件 {@code C2C_MESSAGE_CREATE} */
    C2C("单聊", 60 * 60 * 1000L, 4),

    /** 群聊。对应腾讯事件 {@code GROUP_AT_MESSAGE_CREATE} / {@code GROUP_MESSAGE_CREATE} */
    GROUP("群聊", 5 * 60 * 1000L, 5),

    /** 文字子频道。对应腾讯 {@code AT_MESSAGE_CREATE} 等事件 */
    CHANNEL("频道", 5 * 60 * 1000L, 5),

    /**
     * 未知场景。仅用于本地调试入口（Mock 通道）和兜底，
     * 表示这条消息不来自真实的腾讯事件。
     */
    UNKNOWN("未知", 60 * 60 * 1000L, 1);

    /** 中文名称，用于日志输出 */
    private final String label;

    /** 被动回复的有效期（毫秒）。超过这个时间再回复会被腾讯拒绝 */
    private final long passiveReplyWindowMillis;

    /** 一条用户消息最多能被动回复几次 */
    private final int maxPassiveReplies;

    QqScene(String label, long passiveReplyWindowMillis, int maxPassiveReplies) {
        this.label = label;
        this.passiveReplyWindowMillis = passiveReplyWindowMillis;
        this.maxPassiveReplies = maxPassiveReplies;
    }

    public String getLabel() {
        return label;
    }

    public long getPassiveReplyWindowMillis() {
        return passiveReplyWindowMillis;
    }

    public int getMaxPassiveReplies() {
        return maxPassiveReplies;
    }

    /**
     * 判断从收到消息到现在，是否还在被动回复的有效期内。
     *
     * <p>用途：本地小模型算得慢，如果算完已经超时了，
     * 就不该再走"被动回复"（会失败），而应该考虑降级成主动消息或直接放弃。
     *
     * @param receivedAtMillis 收到消息的时间戳（毫秒）
     * @return 还在有效期内返回 {@code true}
     */
    public boolean isWithinPassiveReplyWindow(long receivedAtMillis) {
        long elapsed = System.currentTimeMillis() - receivedAtMillis;
        return elapsed >= 0 && elapsed < this.passiveReplyWindowMillis;
    }
}
