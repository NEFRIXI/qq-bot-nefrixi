package com.mybotweb.channel.qq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mybotweb.channel.model.QqReply;
import com.mybotweb.channel.model.QqScene;
import com.mybotweb.config.AppProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * QQ OpenAPI 客户端 —— 通过 HTTPS 调腾讯的接口。
 *
 * <p>目前只做两件事：
 * <ol>
 *   <li>{@link #getGatewayUrl()} —— 拿到 WebSocket 网关地址，用于建长连接收消息</li>
 *   <li>{@link #sendReply(QqReply)} —— 把机器人的回复发出去</li>
 * </ol>
 *
 * <p><b>注意方向</b>：本类是<b>出站</b>调用——我们的程序主动请求腾讯。
 * 这也是为什么本地电脑能跑：出站请求不需要公网 IP、不需要端口映射。
 * 与之相对的是 Webhook 模式（腾讯主动 POST 给我们），那个必须公网可达。
 *
 * <p><b>凭证怎么带？</b> 每个请求都要带请求头
 * {@code Authorization: QQBot {access_token}}，token 由
 * {@link QqAccessTokenManager} 提供，本类不关心它什么时候过期。
 *
 * <p>装配条件：{@code app.channel.type: qq}。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.channel.type", havingValue = "qq")
public class QqApiClient {

    /** 调用超时时间（秒）。发消息和取网关地址都是轻请求 */
    private static final int REQUEST_TIMEOUT_SECONDS = 15;

    /** 文本消息的类型码，对应官方文档 {@code msg_type=0} */
    private static final int MSG_TYPE_TEXT = 0;

    /** QQ 配置 */
    private final AppProperties.Qq config;

    /** 凭证管理器 */
    private final QqAccessTokenManager tokenManager;

    /** HTTP 客户端 */
    private final RestClient restClient;

    public QqApiClient(AppProperties appProperties, QqAccessTokenManager tokenManager) {
        this.config = appProperties.getQq();
        this.tokenManager = tokenManager;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
        factory.setReadTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));

        this.restClient = RestClient.builder()
                .baseUrl(this.config.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 获取 WebSocket 网关地址。
     *
     * <p>对应官方接口 {@code GET /gateway}，返回形如：
     * <pre>{@code { "url": "wss://api.bot.qq.com/websocket/" } }</pre>
     *
     * <p><b>为什么要先取这个地址，而不是把 wss 地址写死在配置里？</b>
     * 因为腾讯可能调整网关域名或做灰度。按官方流程先问一次地址，
     * 我们的程序就不怕它换域名——这是对接长连接服务时的常规做法。
     *
     * @return 网关地址
     * @throws IllegalStateException 调用失败时抛出
     */
    public String getGatewayUrl() {
        log.debug("正在获取 QQ WebSocket 网关地址...");
        try {
            GatewayResponse response = restClient.get()
                    .uri("/gateway")
                    .header("Authorization", "QQBot " + tokenManager.getToken())
                    .retrieve()
                    .body(GatewayResponse.class);

            if (response == null || response.getUrl() == null || response.getUrl().isBlank()) {
                throw new IllegalStateException("获取网关地址失败：响应里没有 url 字段");
            }

            log.info("已获取 WebSocket 网关地址：{}", response.getUrl());
            return response.getUrl();

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("获取网关地址失败：" + e.getMessage(), e);
        }
    }

    /**
     * 发送一条回复。
     *
     * <p>按 {@link QqReply#getScene()} 自动选择单聊还是群聊接口：
     * <ul>
     *   <li>单聊 → {@code POST /v2/users/{user_openid}/messages}</li>
     *   <li>群聊 → {@code POST /v2/groups/{group_openid}/messages}</li>
     * </ul>
     *
     * <p><b>被动回复 vs 主动消息</b>：如果 {@code reply.msgId} 有值，
     * 这次发送就是"被动回复"（腾讯允许我们在 5 分钟内回复用户）。
     * 如果为空，就变成"主动消息"——受每日配额限制，
     * 而且用户可以在 QQ 客户端里关掉"允许主动发送"，关了就发不出去。
     * 所以正常情况下都应该带上 msgId。
     *
     * @param reply 要发送的回复
     * @throws IllegalStateException 发送失败时抛出，异常信息里带上腾讯的错误码
     */
    public void sendReply(QqReply reply) {
        SendMessageRequest request = new SendMessageRequest();
        request.setContent(reply.getContent());
        request.setMsgType(MSG_TYPE_TEXT);
        request.setMsgId(reply.getMsgId());
        request.setMsgSeq(reply.getMsgSeq() == null ? 1 : reply.getMsgSeq());

        String uri;
        if (reply.getScene() == QqScene.GROUP) {
            if (reply.getGroupId() == null || reply.getGroupId().isBlank()) {
                throw new IllegalStateException("群聊回复缺少 groupId，无法确定发到哪个群");
            }
            uri = "/v2/groups/" + reply.getGroupId() + "/messages";
        } else {
            // 单聊和未知场景都走单聊接口
            if (reply.getUserId() == null || reply.getUserId().isBlank()) {
                throw new IllegalStateException("回复缺少 userId，无法确定发给谁");
            }
            uri = "/v2/users/" + reply.getUserId() + "/messages";
        }

        try {
            SendMessageResponse response = restClient.post()
                    .uri(uri)
                    .header("Authorization", "QQBot " + tokenManager.getToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(SendMessageResponse.class);

            // 腾讯的业务错误可能混在 200 响应里，所以还是要看一眼 code
            if (response != null && response.getCode() != null && response.getCode() != 0) {
                throw new IllegalStateException(formatSendError(response.getCode(), response.getMessage()));
            }

            String messageId = response == null ? "未知" : response.getId();
            log.info("消息已发往 QQ，返回消息 ID={}", messageId);

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("发送消息失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把腾讯的错误码翻译成人能看懂的排查方向。
     *
     * <p>这张表不是完备的，只收录了实际最容易撞上的几个。
     * 完整错误码见官方文档"发送单聊消息"页面的错误码表。
     *
     * @param code    腾讯返回的错误码
     * @param message 腾讯返回的原始错误信息
     * @return 便于排查的说明
     */
    private String formatSendError(int code, String message) {
        String hint = switch (code) {
            case 40054004 -> "没有好友关系 —— 需要先在沙箱配置里添加这个成员，并扫码把机器人加为好友";
            case 40054005 -> "消息被去重 —— 相同的 msg_id + msg_seq 重复发送了，递增 msg_seq 即可";
            case 40054007, 40054018 -> "消息内容过长 —— 需要缩短回复";
            case 304103, 40034005 -> "被动回复的 msg_id 已过期 —— 超过 5 分钟才回复，只能降级成主动消息";
            case 40034128 -> "被动回复时间或次数超限 —— 同一条消息最多回复 4 次";
            case 40054013 -> "用户拒收了消息 —— 对方在资料卡里关闭了接收";
            case 40054016 -> "机器人已下线 —— 去开放平台检查机器人状态";
            default -> "见官方文档错误码表";
        };
        return String.format("QQ 接口返回错误：code=%d（%s）原始信息：%s", code, hint, message);
    }

    // ========================================================
    // 下面是与腾讯接口对应的数据结构。
    // 字段名里的下划线是腾讯的 JSON 风格，用 @JsonProperty 显式映射，
    // 这样 Java 侧可以保持驼峰命名，两边都符合各自的习惯。
    // ========================================================

    /**
     * {@code GET /gateway} 的响应体
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GatewayResponse {

        /** WebSocket 接入地址 */
        private String url;
    }

    /**
     * 发送消息的请求体。
     *
     * <p>字段只用了最必要的三个，其余（markdown、keyboard、media 等）
     * 等需要时再加。腾讯允许只传 {@code content} + {@code msg_type}。
     */
    @Data
    public static class SendMessageRequest {

        /** 文本内容 */
        private String content;

        /** 消息类型：0 = 纯文本 */
        @JsonProperty("msg_type")
        private Integer msgType;

        /**
         * 被动回复的原消息 ID。
         *
         * <p>来自消息事件的 {@code d.id}。<b>官方文档明确说只有 5 分钟有效</b>，
         * 过期后带上它会报 304103 / 40034005。
         */
        @JsonProperty("msg_id")
        private String msgId;

        /**
         * 回复序号，默认 1。
         *
         * <p>相同的 {@code msg_id + msg_seq} 重复发送会失败（错误码 40054005），
         * 这个特性正好被我们用来做防重复发送。
         */
        @JsonProperty("msg_seq")
        private Integer msgSeq;
    }

    /**
     * 发送消息的响应体。
     *
     * <p>成功时给 {@code id} 和 {@code timestamp}，失败时给 {@code code}
     * 和 {@code message}，所以合成一个类。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SendMessageResponse {

        /** 消息 ID，可用于后续撤回 */
        private String id;

        /** 发送时间（RFC3339 东八区） */
        private String timestamp;

        /** 业务错误码，成功时为 null 或 0 */
        private Integer code;

        /** 错误信息 */
        private String message;
    }
}
