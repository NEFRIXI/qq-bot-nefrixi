package com.mybotweb.channel.qq;

import com.mybotweb.channel.MessageChannel;
import com.mybotweb.channel.model.QqReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 真实 QQ 消息通道 —— 调腾讯接口把回复发给用户。
 *
 * <p>它是 {@link MessageChannel} 在 {@code app.channel.type: qq} 时的实现，
 * 与 {@code MockMessageChannel} 是同一个接口的两个实现：
 * 换一个配置值就切换，{@code MessageDispatcher} 一行都不用改。
 *
 * <p><b>发送是"尽力而为"的</b>：按接口约定，发送失败不往上抛异常，
 * 只记日志。理由是——走到发送这一步时，业务逻辑已经处理完了，
 * 抛异常既不能让消息发出去，还会让调用方以为"处理失败"。
 * 真正需要知道的失败信息都在日志里。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.channel.type", havingValue = "qq")
@RequiredArgsConstructor
public class QqMessageChannel implements MessageChannel {

    /** OpenAPI 客户端 */
    private final QqApiClient apiClient;

    /**
     * 把回复发给 QQ 用户。
     *
     * @param reply 要发送的回复
     */
    @Override
    public void send(QqReply reply) {
        try {
            apiClient.sendReply(reply);
        } catch (Exception e) {
            // 这里必须接住：异常继续往上抛会打断消息处理链路，
            // 而那时候已经没有任何补救动作可做了。
            // 日志里带上收件人和错误详情，方便排查（腾讯的错误码已由
            // QqApiClient 翻译成人能看懂的排查方向）
            log.error("发送 QQ 消息失败，收件人 {}，内容 {} 字：{}",
                    shortId(reply.getUserId()),
                    reply.getContent() == null ? 0 : reply.getContent().length(),
                    e.getMessage());
        }
    }

    @Override
    public String getType() {
        return "qq";
    }

    /**
     * 截断用户标识，避免把完整 openid 写进日志。
     *
     * @param userId 完整用户标识
     * @return 前 6 位加省略号
     */
    private String shortId(String userId) {
        if (userId == null) {
            return "null";
        }
        return userId.length() > 6 ? userId.substring(0, 6) + "…" : userId;
    }
}
