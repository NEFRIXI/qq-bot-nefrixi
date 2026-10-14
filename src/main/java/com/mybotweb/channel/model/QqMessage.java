package com.mybotweb.channel.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 入站消息 —— 代表"从 QQ 收到的一条消息"，经过通道层解析后的统一形态。
 *
 * <p><b>为什么要有这个类，而不是直接把腾讯的 JSON 传下去？</b>
 * 因为下游的分发器、命令处理器不应该关心"这条消息是腾讯推来的还是
 * 本地调试接口发来的"。有了这个统一模型：
 * <ul>
 *   <li>Mock 通道直接 {@code new} 一个出来就能喂给下游，不需要伪造腾讯的 JSON</li>
 *   <li>将来腾讯改协议格式，只需要改通道层的解析代码，下游一行不动</li>
 * </ul>
 * 这就是"接入层"存在的意义：把外部协议的不确定性挡在门口。
 *
 * @author mybotweb
 */
@Data
public class QqMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息 ID，来自腾讯事件的 {@code d.id}。
     *
     * <p><b>这个字段是被动回复的关键</b>：回复时把它作为 {@code msg_id} 带上，
     * 腾讯才知道你回复的是哪条消息。
     */
    private String msgId;

    /**
     * 回复序号，默认从 1 开始。
     *
     * <p>腾讯的规定：相同的 {@code msg_id + msg_seq} 重复发送会<b>失败</b>，
     * 想对同一条消息回复多次就必须递增 {@code msg_seq}。
     *
     * <p>这个特性有个副作用很有用：它天然是幂等的——重复发送不会刷屏。
     */
    private Integer msgSeq = 1;

    /**
     * 发送者的用户标识，来自腾讯事件的 {@code d.author.user_openid}。
     *
     * <p>注意它<b>不是 QQ 号</b>。QQ 官方接口给的是 openid，
     * 同一个用户在不同的机器人下拿到的 openid 是不同的，
     * 这是腾讯的隐私设计。管理员白名单要存的就是这个值。
     */
    private String userId;

    /** 消息正文 */
    private String content;

    /** 消息来自哪种会话场景（单聊 / 群聊 / 频道） */
    private QqScene scene = QqScene.UNKNOWN;

    /**
     * 群聊或频道的标识。
     * 单聊时为 {@code null}；群聊时是群的 openid。
     */
    private String groupId;

    /**
     * 这条群里的话有没有 @ 机器人（或者是不是直接对机器人说的）。
     *
     * <p>═══════════════════════════════════════════════════════════
     * <p><b>★ 为什么必须有这个字段</b>
     *
     * <p>群聊有两种消息事件，形态几乎一样，<b>但处理方式必须完全不同</b>：
     *
     * <table border="1">
     *   <tr><th>事件</th><th>含义</th><th>该不该回复</th></tr>
     *   <tr>
     *     <td>{@code GROUP_AT_MESSAGE_CREATE}</td>
     *     <td>有人 @ 了机器人</td>
     *     <td><b>回</b> —— 人家点名了</td>
     *   </tr>
     *   <tr>
     *     <td>{@code GROUP_MESSAGE_CREATE}</td>
     *     <td>群里随便一句闲聊（全量模式才会推）</td>
     *     <td><b>不能回</b> —— 除非热聊模式判断值得插话</td>
     *   </tr>
     * </table>
     *
     * <p>没有这个字段的后果很严重：开了全量模式之后，群里<b>每一条</b>消息
     * 都会被当成"对我说的"，机器人会把整个群刷爆。
     *
     * <p><b>reference 实现是怎么处理的</b>（OpenClaw 的 group messages 文档）：
     * <pre>
     *   groupPolicy? disabled   -> drop
     *   groupPolicy? allowlist  -> 群不在名单里 -> drop
     *   requireMention? yes     -> 没 @ 我 -> store for context only
     *   otherwise               -> reply
     * </pre>
     * 注意第三行的 <b>store for context only</b> ——
     * 没 @ 的消息<b>只存进上下文、不回复</b>。这正是本字段要支撑的行为。
     *
     * <p>单聊时恒为 {@code true}（单聊本来就是直接对机器人说的）。
     */
    private boolean mentionedBot;

    /**
     * 事件 ID，来自腾讯事件的 {@code d.id}（在"响应事件"类通知里使用）。
     * 普通消息事件里为空。
     */
    private String eventId;

    /**
     * 收到这条消息的本地时间戳（毫秒）。
     *
     * <p>用途：配合 {@link QqScene#isWithinPassiveReplyWindow(long)}
     * 判断"算完了还来不来得及被动回复"。
     */
    private long receivedAtMillis = System.currentTimeMillis();

    /**
     * 腾讯推来的原始事件 JSON。
     *
     * <p>只在排查问题时用（比如某个字段没解析出来，需要看原文到底长什么样）。
     * 打印日志时注意别把它整个打进 ERROR 日志——里面可能含用户内容。
     */
    private String rawJson;

    /**
     * 判断这条消息是不是斜杠命令。
     *
     * <p>对应规格第 5.1 节消息入口路由的第一步：<b>以 "/" 开头就走斜杠命令，不走大模型</b>。
     *
     * <p>这个方法放在模型里而不是分发器里，是因为它是"消息自身的属性"，
     * 而不是"分发策略"。将来如果加别的命令前缀（比如"！"），只改这里。
     *
     * @return 是斜杠命令返回 {@code true}
     */
    public boolean isSlashCommand() {
        return this.content != null && this.content.stripLeading().startsWith("/");
    }

    /**
     * 取去掉首尾空白后的消息内容。
     *
     * <p>用户在手机上打字很容易带空格，直接拿 {@code content} 做字符串比较
     * 会出现"看着一样但不相等"的问题，所以统一在入口处规整。
     *
     * @return 规整后的内容；{@code content} 为 {@code null} 时返回空串
     */
    public String normalizedContent() {
        return this.content == null ? "" : this.content.strip();
    }
}
