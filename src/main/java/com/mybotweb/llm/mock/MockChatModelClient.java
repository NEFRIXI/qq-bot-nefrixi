package com.mybotweb.llm.mock;

import com.mybotweb.llm.ChatModelClient;
import com.mybotweb.llm.model.ChatMessage;
import com.mybotweb.llm.model.ChatRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mock 大模型客户端 —— 不真调模型，把用户说的话回显回去。
 *
 * <p><b>用途</b>：在本地模型还没跑起来、或者只是想验证消息链路时，
 * 让整条流程能走完。它回复的内容形如：
 * <pre>
 *   [Mock] 收到你的消息：你好呀（上下文 3 条消息）
 * </pre>
 * 这比回复一句固定的"你好"信息量大得多——从括号里的条数能看出
 * 历史上下文有没有正确拼进来。
 *
 * <p>装配条件：{@code app.role=mock}（见类上的 {@code @ConditionalOnProperty}）。
 * 它是三个模型客户端里<b>唯一</b>带条件装配的 —— 另外两个
 * （{@code OllamaChatModelClient}、{@code CloudChatModelClient}）都是无条件装配，
 * 由 {@code route/ModelRouter} 在运行期挑。
 *
 * <p>想启用它，把 {@code app.role} 改成 {@code mock} 即可。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "app.role",
        havingValue = "mock"
)
public class MockChatModelClient implements ChatModelClient {

    @Override
    public String chat(List<ChatMessage> messages) {
        // 取最后一条用户消息作为回显内容；正常调用下最后一条一定是 user
        String lastUserContent = messages.stream()
                .filter(m -> m.getRole() == ChatRole.USER)
                .reduce((first, second) -> second)   // 取最后一个
                .map(ChatMessage::getContent)
                .orElse("（没有找到用户消息）");

        int contextCount = messages.size();
        int estimatedTokens = messages.stream().mapToInt(ChatMessage::estimateTokens).sum();

        log.debug("Mock 模型被调用：上下文 {} 条消息，估算 {} tokens", contextCount, estimatedTokens);

        return String.format("[Mock] 收到你的消息：%s（上下文 %d 条消息，估算 %d tokens）",
                lastUserContent, contextCount, estimatedTokens);
    }

    @Override
    public String getProviderName() {
        return "mock";
    }

    /**
     * Mock 永远可用 —— 它不需要任何外部服务。
     *
     * @return 恒为 {@code true}
     */
    @Override
    public boolean isAvailable() {
        return true;
    }
}
