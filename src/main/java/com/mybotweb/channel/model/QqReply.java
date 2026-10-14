package com.mybotweb.channel.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 出站回复 —— 代表"要发给 QQ 用户的一条消息"。
 *
 * <p>它是 {@link QqMessage} 的镜像：消息进来时是 {@code QqMessage}，
 * 处理完要发出去时是 {@code QqReply}。通道实现负责把 {@code QqReply}
 * 翻译成具体协议（腾讯 REST 调用 / 或本地日志输出）。
 *
 * @author mybotweb
 */
@Data
public class QqReply implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 要发给哪个用户，取自入站消息的 {@code userId} */
    private String userId;

    /** 回复正文 */
    private String content;

    /**
     * 被动回复要带上的原消息 ID。
     *
     * <p>带上它表示"这是在回复某条消息"，腾讯会给这个回复更宽松的限制
     * （单聊 60 分钟内有效）。如果这个字段为 {@code null}，
     * 这次发送就变成<b>主动消息</b>——而主动消息有每日配额，
     * 且用户可以在 QQ 客户端里关掉"允许主动发送"。
     */
    private String msgId;

    /** 回复序号。同一 {@code msgId} 下每次回复都要递增，重复的组合会被腾讯拒绝 */
    private Integer msgSeq;

    /** 原消息的场景，决定调哪个发送接口、以及走哪套时效规则 */
    private QqScene scene = QqScene.UNKNOWN;

    /** 群聊/频道标识，群聊场景下发送需要 */
    private String groupId;

    /**
     * 是否是错误提示（比如权限不足的拒绝语）。
     *
     * <p>目前不参与协议，只用于日志区分颜色和本地调试时判断。
     */
    private boolean errorTip;

    /**
     * 构造一条针对某条入站消息的回复。
     *
     * <p>这样构造的好处是 {@code msgId} / {@code msgSeq} / {@code scene} /
     * {@code userId} 这些"回信必须带上的信息"直接从原消息复制过来，
     * 不会漏字段——漏了 {@code msgId} 就变成主动消息，漏了 {@code userId} 就发不出去。
     *
     * @param message 要回复的那条入站消息
     * @param content 回复正文
     * @return 组装好的出站回复
     */
    public static QqReply replyTo(QqMessage message, String content) {
        QqReply reply = new QqReply();
        reply.setUserId(message.getUserId());
        reply.setMsgId(message.getMsgId());
        reply.setMsgSeq(message.getMsgSeq());
        reply.setScene(message.getScene());
        reply.setGroupId(message.getGroupId());
        reply.setContent(content);
        return reply;
    }
}
