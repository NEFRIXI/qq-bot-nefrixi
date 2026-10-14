package com.mybotweb.agent;

import com.mybotweb.config.AppProperties;
import com.mybotweb.llm.model.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话记忆 —— 按用户保存最近几轮对话，用于拼进下一次请求。
 *
 * <p><b>为什么需要它？</b> 大模型本身是无状态的：每次调用都只看到你这次传进去的
 * 内容。想让它"记得"上一句说了什么，唯一的办法就是<b>把历史一起发过去</b>。
 * 这就是多轮记忆的全部秘密——不是模型记住了，是你每次都替它回忆一遍。
 *
 * <p>这也直接解释了为什么上下文会爆：历史越长，每次重发的 token 越多，
 * 而且成本是<b>线性增长</b>的（第 10 轮要把前 9 轮全部重发）。
 * 所以必须裁剪——这就是规格第 6.5 节"滑动窗口"的由来。
 *
 * <p><b>本阶段的存储位置</b>：内存里的 {@link ConcurrentHashMap}。
 * 后果是<b>重启程序记忆就清空</b>。这在开发期完全可以接受，
 * 等做网站后端时（阶段五）会换成 Redis 或数据库，
 * 届时只需要替换本类的实现，调用方一行不用改——
 * 这也是为什么所有存取都收敛在这一条 {@code history} 方法上。
 *
 * <p><b>内存泄漏防护</b>：每个用户最多保留 {@link #MAX_MESSAGES_PER_USER} 条消息。
 * 这是必要的——QQ 机器人可能被很多人用过，如果每个用户的历史都无限增长，
 * 跑几天就会 OOM。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class ConversationMemory {

    /**
     * 每个用户最多保留的消息条数（不是轮数）。
     *
     * <p>200 条消息约等于 100 轮对话，远超实际会用到窗口长度（默认 10 轮 = 20 条）。
     * 留这么多是故意的：将来把窗口调大也不用改这里，
     * 而 200 条的上限又足够小，跑几千个用户也占不了多少内存。
     */
    private static final int MAX_MESSAGES_PER_USER = 200;

    /** 应用配置，用来读"保留最近几轮" */
    private final AppProperties.Chat chatConfig;

    /**
     * 用户 ID → 该用户的对话历史。
     *
     * <p>用 {@link ConcurrentHashMap} 而不是 {@code HashMap}：
     * 多条 QQ 消息可能被不同线程同时处理，普通 HashMap 并发写会丢数据甚至死循环。
     */
    private final Map<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    /**
     * 构造器注入。
     *
     * @param appProperties 应用配置
     */
    public ConversationMemory(AppProperties appProperties) {
        this.chatConfig = appProperties.getChat();
    }

    /**
     * 记录一轮完整对话（用户一句 + 机器人一句）。
     *
     * <p>两句话一起记，是为了保证历史的完整性——如果只记用户那句，
     * 模型会看到"用户说了 A，用户又说了 B"，前后文对不上，
     * 表现就是答非所问。
     *
     * @param userId           用户标识
     * @param userContent      用户说的话
     * @param assistantContent 机器人回的话
     */
    public void appendTurn(String userId, String userContent, String assistantContent) {
        if (userId == null || userId.isBlank()) {
            return;
        }

        // computeIfAbsent + synchronized 块：保证同一个用户的历史列表
        // 不会被两个线程同时修改导致内容错乱或抛 ConcurrentModificationException
        List<ChatMessage> history = store.computeIfAbsent(
                userId, key -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (history) {
            history.add(ChatMessage.user(userContent));
            history.add(ChatMessage.assistant(assistantContent));

            // 超出上限就从最老的开始删（滑动窗口）
            while (history.size() > MAX_MESSAGES_PER_USER) {
                history.remove(0);
            }
        }
    }

    /**
     * 取该用户最近若干轮的历史，用于拼进下一次请求。
     *
     * <p>返回的顺序是<b>从旧到新</b>（最老的在前），这正是模型接口要求的顺序：
     * 它按顺序阅读，最后一条是用户最新的问题。
     *
     * @param userId 用户标识
     * @return 历史消息列表（不含人设，人设由调用方单独加在最前面）；
     *         没有历史时返回空列表，<b>不返回 null</b>
     */
    public List<ChatMessage> history(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }

        List<ChatMessage> history = store.get(userId);
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        // 配置里写的是"轮数"，一轮 = 用户一句 + 机器人一句 = 2 条消息
        int maxMessages = Math.max(1, chatConfig.getHistoryRounds()) * 2;

        synchronized (history) {
            int size = history.size();
            if (size <= maxMessages) {
                // 复制一份再返回：直接返回内部列表的话，调用方一改就把记忆改坏了
                return new ArrayList<>(history);
            }
            // 只取最后 maxMessages 条，即最近的内容
            return new ArrayList<>(history.subList(size - maxMessages, size));
        }
    }

    /**
     * 清空某个用户的记忆。
     *
     * <p>用途：将来做 {@code /clear} 命令（规格第 10.1 节提到 Hermes 框架内置了它），
     * 用户说"重来"时调用。
     *
     * @param userId 用户标识
     */
    public void clear(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        store.remove(userId);
        log.info("已清空用户记忆：{}", userId.length() > 6 ? userId.substring(0, 6) + "…" : userId);
    }

    /**
     * 当前正在被记录的用户数量，供 {@code /status} 展示。
     *
     * @return 用户数
     */
    public int getUserCount() {
        return store.size();
    }
}
