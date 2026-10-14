package com.mybotweb.controller.vo;

import com.mybotweb.dispatcher.DispatchOutcome;
import lombok.Data;

/**
 * 调试入口的响应数据。
 *
 * <p>刻意比"只返回一个回复字符串"多给两个字段，原因是实际调试时的教训：
 * 当接口只返回一个字符串时，"没收到回复"会表现为一个空字段，
 * 而<b>空的原因有好几种</b>（被限流、内容为空、模型返回空白）。
 * 这几种情况的排查方向完全不同，但看起来一模一样，
 * 只能去翻服务端日志才能分辨。
 *
 * <p>多这两个字段之后，调用方一眼就能看出发生了什么。
 *
 * @author mybotweb
 */
@Data
public class DebugReplyVo {

    /** 是否产生了回复 */
    private boolean replied;

    /** 回复文本；没有回复时为 {@code null} */
    private String reply;

    /**
     * 处置结果的一句话描述，形如 {@code 已回复} 或 {@code 已忽略（触发限流）}。
     *
     * <p>设计成给"人"看的文本，而不是给程序判断的枚举值——
     * 因为这是调试接口，读者是人。程序要判断的话有 {@code replied} 字段。
     */
    private String status;

    /**
     * 由分发结果构造响应数据。
     *
     * @param outcome 消息处理结果
     * @return 响应数据
     */
    public static DebugReplyVo from(DispatchOutcome outcome) {
        DebugReplyVo vo = new DebugReplyVo();
        vo.setReplied(outcome.hasReply());
        vo.setReply(outcome.getReply());
        vo.setStatus(outcome.describe());
        return vo;
    }
}
