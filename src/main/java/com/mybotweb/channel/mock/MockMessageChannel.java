package com.mybotweb.channel.mock;

import com.mybotweb.channel.MessageChannel;
import com.mybotweb.channel.model.QqReply;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock 消息通道 —— 不真发消息，只把回复打到控制台日志。
 *
 * <p><b>它的用途</b>：在没有 QQ 凭证的情况下，验证"接收 → 判定 → 分发 → 回复"
 * 这条完整链路是否跑通。因为回复被打印到日志，你可以用 curl 发一条假消息，
 * 然后看日志里打印了什么，就完成了端到端验证。
 *
 * <p><b>{@code @ConditionalOnProperty} 是什么？</b>
 * 它是 Spring Boot 的<b>条件装配</b>注解族里最常用的一个：
 * "当配置项 X 等于 Y 时，才把这个 Bean 注册进容器"。
 *
 * <p>写成：
 * <pre>{@code
 * @ConditionalOnProperty(
 *     name = "app.channel.type",     // 看哪个配置项
 *     havingValue = "mock",          // 期望什么值
 *     matchIfMissing = true          // 配置项不存在时也算命中
 * )
 * }</pre>
 * 于是 {@code app.channel.type: mock} 时装配本类，
 * 改成 {@code qq} 就自动换成 QqMessageChannel，<b>不用改一行 Java 代码</b>。
 *
 * <p>这不是什么偏门技巧——Spring Boot 的自动配置整套都建立在条件装配之上
 * （比如"classpath 里有 spring-boot-starter-web 才装配 Tomcat"用的是
 * {@code @ConditionalOnClass}，是同一个家族）。看懂这一个，
 * 自动配置就不再是黑盒。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "app.channel.type",
        havingValue = "mock",
        matchIfMissing = true
)
public class MockMessageChannel implements MessageChannel {

    @Override
    public void send(QqReply reply) {
        // 用醒目的分隔线包起来，方便在一堆启动日志里一眼找到
        log.info("""
                        
                        ┌───────── Mock 通道发送（未真实发往 QQ）─────────
                        │ 收件人 : {}
                        │ 场景   : {}
                        │ 被动回复: msgId={}, msgSeq={}
                        ├──────────────────────────────────────────────
                        │ {}
                        └──────────────────────────────────────────────""",
                reply.getUserId(),
                reply.getScene() == null ? "未知" : reply.getScene().getLabel(),
                reply.getMsgId(),
                reply.getMsgSeq(),
                // 回复内容可能多行，这里不缩进，保持原样便于阅读
                reply.getContent());
    }

    @Override
    public String getType() {
        return "mock";
    }
}
