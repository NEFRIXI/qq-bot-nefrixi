package com.mybotweb.command.impl;

import com.mybotweb.agent.HotChatBuffer;
import com.mybotweb.agent.HotChatMode;
import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.channel.model.QqScene;
import com.mybotweb.command.CommandHandler;
import com.mybotweb.command.CommandNames;
import com.mybotweb.config.AppProperties;
import com.mybotweb.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code /c_hot} —— 开关"热聊模式"（主动搭话）。仅管理员可用。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>这个模式到底改变了什么</b>
 *
 * <p>默认是被动：有人对机器人说话，它才回答。开启热聊后，机器人开始
 * <b>读群里最近的一串发言</b>（多个人说的），理解大家在聊什么，
 * 然后<b>自己决定要不要插一句</b>。
 *
 * <pre>
 *   被动模式
 *     用户: 帮我查一下天气      → 机器人回答
 *     （没人说话时，机器人永远沉默）
 *
 *   热聊模式
 *     小明: 周末去哪玩           → 只进上下文
 *     小红: 我想去看电影         → 只进上下文
 *     小刚: 电影太贵了           → 累计够了 → 机器人决定要不要接一句
 * </pre>
 *
 * <p>注意最后一步是<b>机器人自己判断</b>的：它可以选择沉默
 * （输出 {@code [沉默]} 标记，程序就不发消息）。这样才能像个人，
 * 而不是个每条都要接的话痨。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>提示词是怎么拼的（回答"为什么换人设不用改这个模式"）</b>
 *
 * <pre>
 *   最终 System Prompt
 *     = 人设（/model-p 选的，或按身份自动选）
 *     + SysPrompt/chat_hot.md 的内容
 * </pre>
 *
 * <p>两段分工明确，所以可以独立改：
 * <ul>
 *   <li><b>人设</b>管"机器人是谁"——语气、性格、身份。用 {@code /model-p} 随时换，
 *       换了之后热聊模式下说话的就是那个新身份</li>
 *   <li><b>chat_hot.md</b> 管"这个场合怎么说话"——怎么看群聊上下文、
 *       什么时候插话、不想说话时输出什么标记</li>
 * </ul>
 *
 * <p>这意味着你可以先 {@code /c_hot on} 开启搭话能力，再用
 * {@code /model-p kong} 把身份换成孙悟空——两件事互不干扰。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>三个要知道的代价</b>
 * <ol>
 *   <li><b>花钱变快</b>：群里每积累够几条消息就会真调一次模型，
 *       而且每次都带上最近几十条上下文。所以有
 *       {@code min-messages} 和 {@code min-interval-seconds} 两道节流</li>
 *   <li><b>只对群聊生效</b>：单聊里没有"多方发言"这回事，
 *       开了也只在群里起作用（{@code app.hot-chat.group-only}）</li>
 *   <li><b>重启后回到关闭</b>：状态只在内存里存着。
 *       这是刻意的——万一忘了关，机器人不会在你不在的时候一直花钱搭话</li>
 * </ol>
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CHotCommand implements CommandHandler {

    /** 表示"开启"的参数 */
    private static final List<String> ON_KEYWORDS =
            List.of("on", "open", "start", "开", "开启", "打开", "启用");

    /** 表示"关闭"的参数 */
    private static final List<String> OFF_KEYWORDS =
            List.of("off", "close", "stop", "关", "关闭", "停止", "禁用");

    /** 表示"看状态"的参数 */
    private static final List<String> STATUS_KEYWORDS =
            List.of("status", "state", "info", "状态", "查看");

    /** 热聊模式状态 */
    private final HotChatMode hotChatMode;

    /** 热聊上下文缓冲，用于展示"已经攒了多少句" */
    private final HotChatBuffer hotChatBuffer;

    /** 应用配置，读热聊参数 */
    private final AppProperties appProperties;

    @Override
    public String getName() {
        return CommandNames.HOT_CHAT;
    }

    @Override
    public List<String> getAliases() {
        // 中文别名：手机上打"热聊"比打"c_hot"快得多（下划线还容易打错）
        return CommandNames.HOT_CHAT_ALIASES;
    }

    @Override
    public String getDescription() {
        return "开关热聊模式（机器人主动搭话）";
    }

    @Override
    public String getUsage() {
        return CommandNames.withSlash(CommandNames.HOT_CHAT) + " [on|off]";
    }

    /**
     * 仅管理员。
     *
     * <p>两个理由：<b>它会持续花钱</b>（每次搭话都是一次模型调用），
     * 以及<b>它改变的是全局行为</b>（所有群的消息处理方式都变了）。
     * 这种开关不该让普通用户随手拨。
     */
    @Override
    public boolean isAdminOnly() {
        return true;
    }

    @Override
    public String execute(QqMessage message, UserContext user, String[] args) {
        if (args.length == 0) {
            return describeStatus();
        }

        String arg = args[0].toLowerCase();

        if (ON_KEYWORDS.contains(arg)) {
            boolean changed = hotChatMode.setEnabled(true);
            if (!changed) {
                return "热聊模式本来就是开着的。\n\n" + describeStatus();
            }
            log.warn("管理员 {} 开启了热聊模式", user.toLogTag());
            return """
                    热聊模式已开启。

                    从现在起，群里大家聊天时我不再只等着被点名——
                    会读最近的一串发言，自己判断要不要插一句。
                    不想说话的时候我就安静待着，不会每条都接。

                    当前身份：%s

                    想换个身份来说话，用 %s 切换人设文件。
                    想关掉：%s off

                    【提醒】这个模式下每次搭话都会真实调用大模型（花钱），
                    所以设置了节流：距上次搭话后群里再攒够 %d 条、
                    且间隔超过 %d 秒，才会考虑搭话。""".formatted(
                    currentIdentityHint(),
                    CommandNames.withSlash(CommandNames.MODEL_PROMPT),
                    CommandNames.withSlash(CommandNames.HOT_CHAT),
                    hotChatMode.getConfig().getMinMessages(),
                    hotChatMode.getConfig().getMinIntervalSeconds());
        }

        if (OFF_KEYWORDS.contains(arg)) {
            boolean changed = hotChatMode.setEnabled(false);
            if (!changed) {
                return "热聊模式本来就是关着的。";
            }
            // 关掉之后必须把上下文也清掉。不清的话有两个后果：
            //   1. 白占内存 —— 模式都关了，每个会话还留着最多 max-messages 条
            //   2. 下次开启时模型会读到几小时前的旧对话，回复莫名其妙
            int cleared = hotChatBuffer.clearAll();
            log.warn("管理员 {} 关闭了热聊模式，清理了 {} 个会话的上下文",
                    user.toLogTag(), cleared);
            return """
                    热聊模式已关闭，回到被动模式。

                    现在我只在有人 @ 我或直接对我说话时才回答，不再主动插话。
                    已清掉 %d 个会话的聊天上下文，那部分内存释放了。

                    想再开：%s on""".formatted(
                    cleared, CommandNames.withSlash(CommandNames.HOT_CHAT));
        }

        if (STATUS_KEYWORDS.contains(arg)) {
            return describeStatus();
        }

        return """
                不认识的参数：%s

                用法：
                  %s          看当前状态
                  %s on       开启主动搭话
                  %s off      关闭，回到被动模式""".formatted(
                args[0],
                CommandNames.withSlash(CommandNames.HOT_CHAT),
                CommandNames.withSlash(CommandNames.HOT_CHAT),
                CommandNames.withSlash(CommandNames.HOT_CHAT));
    }

    /**
     * 描述当前状态。
     *
     * @return 状态文本
     */
    private String describeStatus() {
        boolean on = hotChatMode.isEnabled();
        AppProperties.HotChat config = hotChatMode.getConfig();

        StringBuilder sb = new StringBuilder();
        sb.append("热聊模式（主动搭话）\n");
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("当前状态：").append(on ? "已开启 ✅" : "已关闭（被动模式）").append('\n');
        sb.append("生效范围：").append(config.isGroupOnly() ? "仅群聊" : "群聊 + 单聊").append('\n');

        if (on) {
            sb.append("触发条件：距上次搭话后攒够 ").append(config.getMinMessages())
                    .append(" 条消息，且间隔 ≥ ").append(config.getMinIntervalSeconds())
                    .append(" 秒\n");
            sb.append("上下文条数：最多 ").append(config.getMaxMessages())
                    .append(" 条（滑动窗口，攒满后只留最新的）\n");
            sb.append("沉默标记：").append(config.getSilentToken())
                    .append("（模型输出它就表示这次不插话）\n");
            sb.append("已跟踪　：").append(hotChatBuffer.getConversationCount()).append(" 个会话的上下文\n");
        }

        // 这一行是用户最关心的：换了身份之后热聊会用谁来说话
        sb.append("当前身份：").append(currentIdentityHint()).append('\n');
        sb.append("搭话提示词：SysPrompt/").append(config.getPromptFile()).append(".md\n");
        sb.append("━━━━━━━━━━━━━━━━\n");

        if (on) {
            sb.append("关闭用 " + CommandNames.withSlash(CommandNames.HOT_CHAT) + " off");
        } else {
            sb.append("开启用 " + CommandNames.withSlash(CommandNames.HOT_CHAT) + " on");
        }

        return sb.toString();
    }

    /**
     * 说明"热聊时用哪个人设"，让管理员知道换 {@code /model-p} 会影响到这里。
     *
     * <p>这里需要 {@code SystemPromptManager} 才能给出准确文件名，
     * 但那会多一个依赖。权衡之后选择不注入它——因为本命令的输出
     * 只需要告诉用户"去用 /model-p 看"，具体是哪个文件
     * {@code /model-p} 自己会显示，重复一遍反而容易两边不一致。
     *
     * @return 提示文本
     */
    private String currentIdentityHint() {
        return "由 " + CommandNames.withSlash(CommandNames.MODEL_PROMPT)
                + " 决定（发 " + CommandNames.withSlash(CommandNames.MODEL_PROMPT)
                + " 可查看当前是哪个）";
    }
}
