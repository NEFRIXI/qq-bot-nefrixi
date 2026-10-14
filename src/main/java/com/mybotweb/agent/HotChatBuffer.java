package com.mybotweb.agent;

import com.mybotweb.config.AppProperties;
import com.mybotweb.llm.model.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 热聊消息缓冲区 —— 按会话保存"群里最近都说了些什么"。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>它解决的问题</b>
 *
 * <p>{@link ConversationMemory} 存的是"某个人和机器人的一对一对话"，
 * 而热聊模式要处理的是<b>多方发言</b>：群里 A 说了句、B 接了句、C 又来一句，
 * 机器人要能看懂这一串上下文，然后<b>主动插话</b>。
 *
 * <p>两者的区别用一个例子就清楚了：
 * <pre>
 *   ConversationMemory（被动模式）
 *     用户: 今天天气怎么样
 *     机器人: 还行吧
 *     用户: 那明天呢
 *
 *   HotChatBuffer（热聊模式）
 *     小明: 周末去哪玩
 *     小红: 我想去看电影
 *     小刚: 电影太贵了
 *     → 机器人自己决定要不要接一句
 * </pre>
 * 后者里<b>没有一句话是对机器人说的</b>，机器人的发言是自发行为。
 * 这就是为什么它需要单独的缓冲，而不能复用 ConversationMemory。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么按"场景"分组，而不是全局一个列表</b>
 *
 * <p>因为腾讯网关是<b>一条连接收所有群的消息</b>。如果只用一个列表，
 * A 群和 B 群的发言会混在一起，模型会看到"牛头不对马嘴"的对话流。
 *
 * <p>所以键是"场景 + 会话标识"（群号），每个群各存各的。
 * 这与 {@code ConversationMemory} 按 userId 分组的思路一致，
 * 只是分组的维度从"人"换成了"群"。
 *
 * <p><b>内存控制</b>：每个会话最多留 {@code app.hot-chat.max-messages} 条，
 * 超出从最老的丢。热聊是持续不断的，不设上限的话跑一天就会 OOM——
 * 注意这和 {@code ConversationMemory} 的 200 条上限是两回事，
 * 那个是"消息条数"，这里还叠加了一个"时间窗口"（太老的消息读着也没意义）。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class HotChatBuffer {

    /** 热聊配置 */
    private final AppProperties.HotChat config;

    /**
     * 会话键 → 该会话最近的消息。
     *
     * <p>内层用 {@link LinkedList} 而不是 {@code ArrayList}：
     * 淘汰最老的那条是 {@code removeFirst()}，在链表上是 O(1)，
     * 而 ArrayList 要整体搬移。
     */
    private final Map<String, LinkedList<BufferedMessage>> buffers = new ConcurrentHashMap<>();

    public HotChatBuffer(AppProperties appProperties) {
        this.config = appProperties.getHotChat();
    }

    /**
     * 记一条消息进缓冲。
     *
     * <p>正常消息和机器人自己的发言都要记 —— 机器人需要看到
     * "我刚才说过什么"，否则它会在同一个话题上反复搭话。
     *
     * @param conversationKey 会话标识（群聊用群号）
     * @param senderId        发言者标识；机器人自己发言时也传它的标识
     * @param senderName      发言者显示名，用来在上下文中区分"谁在说"
     * @param content         发言内容
     * @param fromBot         这条是不是机器人自己说的
     */
    public void record(String conversationKey, String senderId, String senderName,
                       String content, boolean fromBot) {
        if (conversationKey == null || conversationKey.isBlank()) {
            return;
        }
        if (content == null || content.isBlank()) {
            return;
        }

        LinkedList<BufferedMessage> buffer = this.buffers.computeIfAbsent(
                conversationKey, key -> new LinkedList<>());

        synchronized (buffer) {
            buffer.addLast(new BufferedMessage(
                    senderId, senderName, content.strip(), System.currentTimeMillis(), fromBot));

            // 淘汰最老的，直到不超上限
            int max = Math.max(2, this.config.getMaxMessages());
            while (buffer.size() > max) {
                buffer.removeFirst();
            }
        }
    }

    /**
     * 取该会话最近的上下文，转成可以直接拼进模型请求的消息列表。
     *
     * <p><b>拼装格式是这里最关键的设计</b>：模型只认识
     * system / user / assistant 三种角色，没有"第三方发言者"这种角色。
     * 而热聊场景必须让模型分清谁在说话，否则它会把三个人的话当成
     * 一个人说的，回复就完全跑偏。
     *
     * <p>做法是把发言者名字写进正文，统一用 user 角色发过去：
     * <pre>
     *   【小明】周末去哪玩
     *   【小红】我想去看电影
     *   【我】那我也去
     * </pre>
     * 机器人自己的历史发言标成"我"，这样模型知道那是它说过的，
     * 不会把自己的话当成别人的。
     *
     * @param conversationKey 会话标识
     * @param excludePending  被排除的那条消息（通常是当前这条，它由调用方单独放在最后，
     *                        避免在上下文里出现两次）；传 {@code null} 表示不排除
     * @return 消息列表，从旧到新；没有内容时返回空列表
     */
    public List<ChatMessage> contextAsMessages(String conversationKey, String excludePending) {
        List<BufferedMessage> snapshot = snapshot(conversationKey);
        if (snapshot.isEmpty()) {
            return List.of();
        }

        List<ChatMessage> result = new ArrayList<>(snapshot.size());
        for (BufferedMessage msg : snapshot) {
            if (excludePending != null && msg.content().equals(excludePending.strip())) {
                // 只跳过第一次出现的相同内容，避免把别人重复说的话也误删
                excludePending = null;
                continue;
            }
            result.add(ChatMessage.user(formatLine(msg)));
        }
        return result;
    }

    /**
     * 把一条缓冲消息渲染成"【谁】说了什么"的形式。
     *
     * @param msg 缓冲消息
     * @return 渲染后的文本
     */
    private String formatLine(BufferedMessage msg) {
        String who = msg.fromBot()
                ? this.config.getBotSelfLabel()
                : (msg.senderName() == null || msg.senderName().isBlank()
                        ? "某人"
                        : msg.senderName());
        return "【" + who + "】" + msg.content();
    }

    /**
     * 取该会话的缓冲快照。
     *
     * <p>返回副本而不是内部列表：调用方拿着它去拼请求时，
     * 别的线程可能正在往里加新消息，直接暴露内部结构会造成
     * {@code ConcurrentModificationException}。
     *
     * @param conversationKey 会话标识
     * @return 从旧到新的消息副本；没有内容时返回空列表
     */
    public List<BufferedMessage> snapshot(String conversationKey) {
        if (conversationKey == null || conversationKey.isBlank()) {
            return List.of();
        }
        LinkedList<BufferedMessage> buffer = this.buffers.get(conversationKey);
        if (buffer == null || buffer.isEmpty()) {
            return List.of();
        }
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    /**
     * 取该会话自某个时间点之后的消息条数。
     *
     * <p>用途：热聊模式的节流判断 —— "距上次发言之后又积累了几条"。
     *
     * @param conversationKey 会话标识
     * @param sinceMillis     起始时间戳
     * @return 条数
     */
    public int countSince(String conversationKey, long sinceMillis) {
        int count = 0;
        for (BufferedMessage msg : snapshot(conversationKey)) {
            if (msg.atMillis() > sinceMillis) {
                count++;
            }
        }
        return count;
    }

    /**
     * 清空某个会话的缓冲。
     *
     * <p>用途：关闭热聊模式时把这个群的上下文丢掉 —— 留着的话，
     * 重新开启时模型会看到一段过期很久的对话，reply 会莫名其妙。
     *
     * @param conversationKey 会话标识
     */
    public void clear(String conversationKey) {
        if (conversationKey != null) {
            this.buffers.remove(conversationKey);
        }
    }

    /**
     * 清空所有会话的缓冲。
     *
     * <p>由 {@code /c_hot off} 调用。这一步是必须的，不是可选的清理：
     * 关闭模式后如果这些上下文还留着，就有两个后果——
     * <ol>
     *   <li><b>占内存</b>：每个会话最多 {@code max-messages} 条，
     *       群多了就是持续占用，而模式已经关了根本用不上</li>
     *   <li><b>下次开启时会"看见过去"</b>：重新打开后模型读到的第一段上下文
     *       可能是几小时前另一群人的对话，回复会莫名其妙</li>
     * </ol>
     *
     * @return 被清掉的会话数，供命令回执显示
     */
    public int clearAll() {
        int count = this.buffers.size();
        this.buffers.clear();
        if (count > 0) {
            log.info("热聊上下文已全部清空，释放 {} 个会话的缓冲", count);
        }
        return count;
    }

    /**
     * 当前正在跟踪的会话数，供 {@code /c_hot} 和 {@code /status} 展示。
     *
     * @return 会话数
     */
    public int getConversationCount() {
        return this.buffers.size();
    }

    /**
     * 一条被缓冲的消息。
     *
     * @param senderId   发言者标识
     * @param senderName 发言者显示名
     * @param content    发言内容
     * @param atMillis   记录时间戳
     * @param fromBot    是否机器人自己说的
     */
    public record BufferedMessage(String senderId,
                                  String senderName,
                                  String content,
                                  long atMillis,
                                  boolean fromBot) {
    }
}
