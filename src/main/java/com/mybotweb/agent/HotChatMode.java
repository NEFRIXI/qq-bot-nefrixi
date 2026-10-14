package com.mybotweb.agent;

import com.mybotweb.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 热聊模式状态 —— 记录"主动搭话"这个模式开没开、以及各群的搭话节奏。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>两种模式的区别（这是本类的存在意义）</b>
 *
 * <table border="1">
 *   <tr><th></th><th>被动模式（默认）</th><th>热聊模式（{@code /c_hot}）</th></tr>
 *   <tr>
 *     <td>触发条件</td>
 *     <td>有人对机器人说话 / @它</td>
 *     <td>群里有人在聊，<b>不需要提到机器人</b></td>
 *   </tr>
 *   <tr>
 *     <td>上下文</td>
 *     <td>这个人和机器人的一对一历史</td>
 *     <td>这个群<b>多个人</b>的连续发言</td>
 *   </tr>
 *   <tr>
 *     <td>发言动机</td>
 *     <td>被问才答</td>
 *     <td><b>机器人自己决定要不要插话</b></td>
 *   </tr>
 *   <tr>
 *     <td>成本</td>
 *     <td>低（一问一答）</td>
 *     <td>高（群里每积累几条消息就调一次模型）</td>
 *   </tr>
 * </table>
 *
 * <p>注意最后一行不是小事：热聊模式会让 API 调用量随群活跃度上涨，
 * 所以 {@code app.hot-chat.min-messages} 那个节流是必须的，
 * 不是可以省的优化。没有它，一个刷屏的群能在几小时内烧掉大量额度。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>状态存哪、为什么不持久化</b>
 *
 * <p>只存内存，<b>重启后回到关闭状态</b>。这是有意的：
 * 热聊是个"临时玩一会儿"的模式（比如群里正聊得热闹），
 * 不是常态配置。如果持久化，某次忘了关，机器人会在你不在的时候
 * 一直花钱搭话——那种账单比"重启后要重新开一次"麻烦得多。
 *
 * <p>按群记录"上次发言时间"是为了节流：同一个人刷屏时，
 * 不能让机器人跟着刷。每个群独立计时，A 群的热闹不影响 B 群。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class HotChatMode {

    /** 热聊配置 */
    private final AppProperties.HotChat config;

    /**
     * 模式总开关。
     *
     * <p>是<b>全局</b>的，不是按群开的。理由：这个模式是管理员
     * "实验/体验"用的，按群开关会让状态管理复杂一圈，而收益不明显。
     * 如果将来确实需要"只对某个群开"，把它换成 {@code Set<String>} 即可，
     * 判断入口是下面这一个方法，改动不会散开。
     */
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /**
     * 会话键 → 机器人上次在这个群发言的时间戳。
     *
     * <p>用于节流：距上次发言不足 {@code min-interval-seconds} 时，
     * 即使消息条数够了也不搭话。这是防止机器人在一个节奏很快的群里
     * 变成刷屏机器的关键。
     */
    private final Map<String, Long> lastSpeakAtMillis = new ConcurrentHashMap<>();

    public HotChatMode(AppProperties appProperties) {
        this.config = appProperties.getHotChat();
    }

    /**
     * 模式是否开启。
     *
     * <p>会被每条消息调用一次，实现必须廉价——这里只读一个原子布尔量。
     *
     * @return 开启返回 {@code true}
     */
    public boolean isEnabled() {
        return this.enabled.get();
    }

    /**
     * 开或关。
     *
     * @param value 目标状态
     * @return 状态是否真的发生了变化（用于给用户一句准确的回执）
     */
    public boolean setEnabled(boolean value) {
        boolean changed = this.enabled.compareAndSet(!value, value);
        if (changed) {
            log.warn("热聊模式已{}（主动搭话；群消息累计 {} 条且距上次发言 {} 秒才触发一次）",
                    value ? "开启" : "关闭",
                    this.config.getMinMessages(), this.config.getMinIntervalSeconds());
        }
        if (!value) {
            // 关闭时清掉节奏记录：不清的话，下次开启的头几秒
            // 会因为"距上次发言很久了"而立刻搭话，显得很突兀
            this.lastSpeakAtMillis.clear();
        }
        return changed;
    }

    /**
     * 本模式在"未开启"状态下会不会占用上下文缓冲。
     *
     * <p><b>为什么专门写一个方法回答这件事</b>：因为它是个被反复问到的问题，
     * 而且答案是"不会"——判断依据在 {@code MessageDispatcher.handle} 里：
     * 热聊分支的前置条件包含 {@code hotChatMode.isEnabled()}，
     * 模式没开时消息<b>连 {@code HotChatBuffer.record} 都不会调用</b>，
     * 所以缓冲在那时是彻底空的，不占内存。
     *
     * <p>至于"关闭之后残留的上下文"由谁清：由 {@code /c_hot off} 命令清
     * （它同时持有本类和缓冲，调用 {@code HotChatBuffer.clearAll()}）。
     * 不在本类里清是为了避免让 {@code HotChatMode} 反向依赖
     * {@code HotChatBuffer} —— 那会绕出一个循环依赖。职责上这样也更顺：
     * 本类只回答"开没开、该不该说话"，不碰数据存储。
     *
     * @return 恒为 {@code false}，即未开启时不占缓冲
     */
    public boolean usesBufferWhenDisabled() {
        return false;
    }

    /**
     * 判断这个会话现在"该不该让机器人搭话"。
     *
     * <p>两个条件都满足才触发：
     * <ol>
     *   <li><b>累计消息数够了</b> —— {@code recentCount >= min-messages}。
     *       避免群友只说了一句话机器人就急着插嘴</li>
     *   <li><b>距上次发言够久了</b> —— 防止连续刷屏</li>
     * </ol>
     *
     * <p><b>为什么条件一由调用方传进来、而不是在这里数</b>：
     * 数消息需要访问 {@link HotChatBuffer}，而那个组件不该反过来依赖本类
     * （会形成"模式 → 缓冲 → 模式"的循环）。所以调用方数好、
     * 把数字传进来，本类只管判断。这样依赖方向是单向的。
     *
     * @param conversationKey  会话标识
     * @param recentCount      距上次发言之后该会话累积的消息条数
     * @return 该搭话返回 {@code true}
     */
    public boolean shouldSpeak(String conversationKey, int recentCount) {
        if (!this.enabled.get()) {
            return false;
        }
        if (conversationKey == null || conversationKey.isBlank()) {
            return false;
        }
        if (recentCount < Math.max(1, this.config.getMinMessages())) {
            return false;
        }

        long minInterval = Math.max(0, this.config.getMinIntervalSeconds()) * 1000L;
        Long last = this.lastSpeakAtMillis.get(conversationKey);
        if (last != null && System.currentTimeMillis() - last < minInterval) {
            log.debug("热聊节流：距上次发言不足 {} 秒，本次不搭话", this.config.getMinIntervalSeconds());
            return false;
        }
        return true;
    }

    /**
     * 记一次"机器人刚发过言"。
     *
     * <p>调用时机很重要：<b>必须是"确实发出去了"之后</b>，
     * 不能在"决定发言"时就记。否则一次发送失败会让机器人
     * 白白沉默一整个节流周期。
     *
     * @param conversationKey 会话标识
     */
    public void markSpoken(String conversationKey) {
        if (conversationKey != null && !conversationKey.isBlank()) {
            this.lastSpeakAtMillis.put(conversationKey, System.currentTimeMillis());
        }
    }

    /**
     * 取上次发言距今的秒数，供 {@code /c_hot} 和 {@code /status} 展示。
     *
     * @param conversationKey 会话标识
     * @return 秒数；本会话还没发过言返回 {@code -1}
     */
    public long secondsSinceLastSpeak(String conversationKey) {
        Long last = this.lastSpeakAtMillis.get(conversationKey);
        if (last == null) {
            return -1L;
        }
        return (System.currentTimeMillis() - last) / 1000;
    }

    /**
     * 取热聊配置，供命令展示当前节流参数。
     *
     * @return 配置对象
     */
    public AppProperties.HotChat getConfig() {
        return this.config;
    }
}
