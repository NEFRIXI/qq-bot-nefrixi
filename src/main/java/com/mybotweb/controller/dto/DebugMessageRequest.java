package com.mybotweb.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 本地调试入口的请求体。
 *
 * <p>它模拟的是"腾讯推来的一条消息"。字段刻意保持最少，
 * 因为调试入口的目的是验证处理链路，不是复刻腾讯的协议。
 *
 * @author mybotweb
 */
@Data
public class DebugMessageRequest {

    /**
     * 模拟的发消息人标识。
     *
     * <p>真实场景下这是腾讯给的 openid。调试时随便填一个字符串即可，
     * 例如 {@code "test-user-1"}。
     *
     * <p><b>想测试管理员权限</b>：把你填的这个值加到
     * {@code application-local.yml} 的 {@code app.admin.user-ids} 里，
     * 它就被当成管理员了。
     */
    @NotBlank(message = "userId 不能为空")
    @Size(max = 128, message = "userId 过长")
    private String userId;

    /**
     * 消息内容。
     *
     * <p>以 {@code /} 开头会被当成斜杠命令（不走大模型），
     * 否则走聊天流程。
     */
    @NotBlank(message = "content 不能为空")
    @Size(max = 2000, message = "content 过长")
    private String content;

    /**
     * 会话场景，可选。取值：{@code c2c}（单聊）、{@code group}（群聊）、
     * {@code channel}（频道）。不填则视为单聊。
     *
     * <p>这个字段在调试时有用：群聊的被动回复窗口只有 5 分钟，
     * 单聊是 60 分钟，可以通过切换它来观察超时降级逻辑。
     */
    private String scene;

    /**
     * 这条群消息有没有 @ 机器人。<b>不填默认为 {@code true}</b>（等同于点名）。
     *
     * <p>它对应真实世界里两种不同的腾讯事件：
     * <ul>
     *   <li>{@code true} —— 相当于 {@code GROUP_AT_MESSAGE_CREATE}（@了机器人），会正常回复</li>
     *   <li>{@code false} —— 相当于 {@code GROUP_MESSAGE_CREATE}（群里随口一句），
     *       <b>不会直接回复</b>，只在热聊模式下进上下文缓冲</li>
     * </ul>
     *
     * <p>调这个字段是验证"机器人会不会把群刷爆"最直接的方式：
     * 用 {@code scene=group} + {@code mentioned=false} 连发几条，
     * 看看它是不是安静地收下而不回话。
     *
     * <p>单聊场景下这个字段没有意义（单聊本来就是直接对机器人说的）。
     */
    private Boolean mentioned;

    /**
     * 群标识，可选。<b>只有 {@code scene=group} 时才有意义。</b>
     *
     * <p>热聊的上下文是<b>按群分组</b>存的（一个群一份缓冲）。不填的话
     * 会用不到群分组，热聊逻辑会因为"分不出这是哪个群"而直接跳过。
     *
     * <p>调试时随便编一个即可，比如 {@code "debug-group-1"}；
     * 想看两个群互不干扰，就换不同的值分别发几条。
     */
    private String groupId;
}
