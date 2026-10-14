package com.mybotweb.controller;

import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.channel.model.QqScene;
import com.mybotweb.common.result.Result;
import com.mybotweb.controller.dto.DebugMessageRequest;
import com.mybotweb.controller.vo.DebugReplyVo;
import com.mybotweb.dispatcher.DispatchOutcome;
import com.mybotweb.dispatcher.MessageDispatcher;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 本地调试入口 —— 用 HTTP 请求模拟"腾讯推来一条 QQ 消息"。
 *
 * <p><b>它解决什么问题？</b> 在拿到 QQ 凭证之前、或者不想每次都去手机上打字的时候，
 * 需要一种办法来验证消息处理链路。这个接口就是那扇门：
 * 你用 curl 发一条消息进来，它走的路和真实 QQ 消息<b>完全一样</b>
 * （同一个 {@link MessageDispatcher}），只有"进门"的方式不同。
 *
 * <p>所以它能验证的是真问题：斜杠命令有没有走大模型、
 * 权限判断对不对、上下文有没有正确拼起来。等凭证到手，
 * 只要换掉通道实现，这段调试代码可以直接留着继续用。
 *
 * <p><b>安全提醒</b>：这个接口<b>没有任何鉴权</b>，任何人都能通过它
 * 让机器人发消息、消耗模型算力。开发阶段在本机跑没问题，
 * 但<b>部署到云服务器之前必须处理</b>，做法二选一：
 * <ul>
 *   <li>用配置开关控制，生产环境不注册这个 Controller
 *       （给类加 {@code @ConditionalOnProperty(name = "app.debug.enabled", havingValue = "true")}）</li>
 *   <li>或者保留但加上内网限制 / Token 校验</li>
 * </ul>
 * 这件事记在阶段九的清单里。
 *
 * @author mybotweb
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/qq")
@RequiredArgsConstructor
public class DebugQqController {

    /** 消息分发器，所有消息的统一入口 */
    private final MessageDispatcher messageDispatcher;

    /**
     * 模拟收到一条 QQ 消息。
     *
     * <p>调用示例：
     * <pre>
     * curl -X POST http://localhost:8080/api/debug/qq/message \
     *      -H "Content-Type: application/json" \
     *      -d '{"userId":"test-user-1","content":"你好"}'
     * </pre>
     *
     * @param request 模拟消息
     * @return 统一响应体，{@code data} 里包含"是否产生回复"、"回复文本"和"处置结果"。
     *         消息被忽略时 {@code replied} 为 {@code false}，
     *         {@code status} 会说明原因（比如"已忽略（触发限流）"），
     *         这样就不用去翻服务端日志才知道发生了什么
     */
    @PostMapping("/message")
    public Result<DebugReplyVo> receiveMessage(@Valid @RequestBody DebugMessageRequest request) {

        QqMessage message = toQqMessage(request);

        log.info("收到调试消息：userId={}，内容={}", request.getUserId(), request.getContent());

        // 真消息和调试消息走的是同一个入口方法，这是这个调试接口的价值所在
        DispatchOutcome outcome = messageDispatcher.handle(message);

        if (!outcome.hasReply()) {
            // 被忽略的情况：空内容、命中限流、结果为空。
            // 仍然返回成功码（请求本身处理成功了），但把原因明确带回去
            log.info("调试消息未产生回复：{}", outcome.describe());
        }

        return Result.success(DebugReplyVo.from(outcome));
    }

    /**
     * 把调试请求转成内部统一的消息模型。
     *
     * <p>这里的"造"数据是刻意的：调试入口不需要伪造腾讯的完整 JSON，
     * 只要填上处理逻辑真正会用到的字段（userId、content、scene、msgId）。
     *
     * <p>{@code msgId} 用一个随机 UUID 生成，模拟腾讯给的消息 ID——
     * 有了它，回复才算"被动回复"而不是"主动消息"。
     *
     * @param request 调试请求
     * @return 内部消息模型
     */
    private QqMessage toQqMessage(DebugMessageRequest request) {
        QqMessage message = new QqMessage();
        message.setUserId(request.getUserId());
        message.setContent(request.getContent());
        message.setScene(parseScene(request.getScene()));

        // 是否 @ 了机器人。不填默认 true（等同于点名），这样老的调试用法行为不变。
        // 传 false 配合 scene=group，就能模拟"群里随口一句话"这种全量模式下的消息
        message.setMentionedBot(request.getMentioned() == null || request.getMentioned());

        // 群聊场景下补一个群标识 —— 热聊的上下文是按群分组的，
        // 没有它就分不出"这是哪个群在说话"，热聊逻辑会直接跳过
        if (message.getScene() == QqScene.GROUP && request.getGroupId() != null
                && !request.getGroupId().isBlank()) {
            message.setGroupId(request.getGroupId());
        }

        // 模拟腾讯的消息 ID
        message.setMsgId("debug-" + UUID.randomUUID());
        message.setMsgSeq(1);
        message.setReceivedAtMillis(System.currentTimeMillis());
        message.setRawJson(null);

        return message;
    }

    /**
     * 把请求里的场景字符串转成枚举。
     *
     * <p>大小写不敏感，认不出来的一律当成单聊——
     * 单聊的被动回复窗口最长（60 分钟），作为默认值最不容易误伤。
     *
     * @param scene 场景字符串，可为 {@code null}
     * @return 场景枚举
     */
    private QqScene parseScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return QqScene.C2C;
        }
        return switch (scene.strip().toLowerCase()) {
            case "group", "群聊" -> QqScene.GROUP;
            case "channel", "频道" -> QqScene.CHANNEL;
            default -> QqScene.C2C;
        };
    }
}
