package com.mybotweb.dispatcher;

import com.mybotweb.agent.ConversationMemory;
import com.mybotweb.agent.HotChatBuffer;
import com.mybotweb.agent.HotChatMode;
import com.mybotweb.agent.prompt.PromptLoader;
import com.mybotweb.agent.prompt.SystemPromptManager;
import com.mybotweb.channel.MessageChannel;
import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.channel.model.QqReply;
import com.mybotweb.channel.model.QqScene;
import com.mybotweb.command.CommandNames;
import com.mybotweb.command.CommandRouter;
import com.mybotweb.config.AppProperties;
import com.mybotweb.llm.ChatModelClient;
import com.mybotweb.llm.ModelCallException;
import com.mybotweb.llm.model.ChatMessage;
import com.mybotweb.route.ModelRouter;
import com.mybotweb.route.NoAvailableModelException;
import com.mybotweb.route.RouteStrategy;
import com.mybotweb.security.AdminWhitelist;
import com.mybotweb.security.RateLimiter;
import com.mybotweb.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 消息分发器 —— 所有 QQ 消息的统一入口。
 *
 * <p>本类是整个项目的枢纽，实现的是规格第 5.1 节的判定流程：
 * <pre>
 *   ① 消息以 "/" 开头？
 *        → 是：走斜杠命令处理，【不走大模型】，结束
 *        → 否：继续
 *   ② 是管理员？
 *        → 是：走云端 Agent（完整工具 + 管理员人设）
 *        → 否：继续
 *   ③ 普通用户 → 按路由策略分发
 *        → LOCAL_FIRST：本地在线走本地，离线走云端
 *        → CLOUD_FIRST：直接走云端
 *        → LOCAL_ONLY：本地离线则报错
 *        → CLOUD_ONLY：直接走云端
 * </pre>
 *
 * <p><b>判定顺序不能调换</b>。特别是第 ① 步必须在最前面：
 * 它是"斜杠命令不消耗大模型 token"这条硬约束的<b>唯一执行点</b>，
 * 一旦被挪到后面或者加个"顺便让模型润色一下"，约束就废了。
 *
 * <p><b>本阶段的实现范围</b>：第 ① 步完整实现；第 ② ③ 步合并为"聊天"，
 * 统一交给当前配置的大模型（本地 Ollama）。原因是现在还没有云服务器，
 * 没有"两台机器"可选，路由策略暂时无从谈起。
 * 将来接入云端的插入点已在 {@link #handleChat} 里用注释标出。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageDispatcher {

    /**
     * 单条回复的最大长度。
     *
     * <p>QQ 对单条消息有长度上限，超了会被接口拒绝，表现为"机器人不回话"。
     * 这里取一个保守值并在超长时主动截断，附上省略提示。
     * 确切的上限值需要等真实接入后实测（阶段二），届时按实测值调整。
     */
    private static final int MAX_REPLY_LENGTH = 800;

    /** 日志里打印用户消息时最多保留多少字符（避免长消息刷屏，也减少敏感内容落盘） */
    private static final int LOG_CONTENT_MAX_LENGTH = 60;

    /** 斜杠命令路由 */
    private final CommandRouter commandRouter;

    /** 消息通道，负责把回复送出去 */
    private final MessageChannel messageChannel;

    /**
     * 算力路由器。注入的是路由接口而不是某一个大模型客户端，
     * 因为"这次交给谁算"是运行期决定的（取决于路由策略 + 本地节点是否在线）。
     *
     * <p>改造前这里注入的是 {@code ChatModelClient}，由 {@code app.role}
     * 在启动时二选一。现在两个客户端同时装配，选择动作收敛到 ModelRouter 里，
     * 本类不需要知道有几个后端、也不需要知道降级规则。
     */
    private final ModelRouter modelRouter;

    /**
     * System Prompt 管理器。
     *
     * <p>它决定这次聊天用哪个人设文件——默认按身份选
     * （管理员 {@code chat_admin.md}、普通用户 {@code chat_default.md}），
     * 但管理员可以用 {@code /model-prompt-select} 全局覆盖掉这个规则。
     *
     * <p>注意这里注入的是管理器而不是加载器：加载器只管"读文件"，
     * 管理器管"该读哪个文件"。分发器需要的是后者。
     */
    private final SystemPromptManager promptManager;

    /**
     * 提示词加载器。
     *
     * <p>热聊模式需要它来读<b>第二段提示词</b>（{@code SysPrompt/chat_hot.md}）。
     * 为什么不走 {@code SystemPromptManager}：那个管理器的职责是
     * "决定用哪个人设"（按身份选 / 全局覆盖），而热聊附加的这段
     * 不是人设、跟身份也无关，它是"场合规则"。
     * 硬塞进管理器只会让"人设"这个概念变得含糊。
     */
    private final PromptLoader promptLoader;

    /** 多轮对话记忆 */
    private final ConversationMemory conversationMemory;

    /** 管理员白名单 */
    private final AdminWhitelist adminWhitelist;

    /** 限流器 */
    private final RateLimiter rateLimiter;

    /**
     * 热聊模式状态。
     *
     * <p>它只管两件事：模式开没开、这个群现在该不该搭话（节流判断）。
     * 与"上下文从哪来"（那是 {@link HotChatBuffer} 的事）刻意分开——
     * 这样依赖方向是单向的：分发器 → 模式、分发器 → 缓冲，
     * 两者互不认识。
     */
    private final HotChatMode hotChatMode;

    /**
     * 热聊上下文缓冲。
     *
     * <p>热聊模式要"读懂群里几个人在聊什么"，而 {@link ConversationMemory}
     * 存的是"某个人和机器人的一对一历史"，两者不是一回事——
     * 群里大多数发言根本没对机器人说话，那些话不会进 ConversationMemory。
     * 所以需要这个单独的缓冲来保存多方发言。
     */
    private final HotChatBuffer hotChatBuffer;

    /** 应用配置，读热聊的提示词文件名与沉默标记 */
    private final AppProperties appProperties;

    /**
     * "已经提醒过降级到云端了"的标记。
     *
     * <p>本地模型起不来时会自动降级到云端。这件事<b>必须让用户知道</b> ——
     * 否则他会以为一直在用免费的本机模型，其实在花 API 的钱。
     *
     * <p>但也不能每条回复都挂一句提示：本地要是长期起不来，
     * 每句话后面都跟着一句"本机模型没起来"，比不提醒还烦。
     * 所以折中：<b>一个进程里只在回复里挂一次</b>，之后靠日志说话
     * （{@link #noticeIfFellBackToCloud} 每次降级都打 WARN）。
     *
     * <p>这里带初始值是故意的：{@code @RequiredArgsConstructor} 只会把
     * <b>没有初始值的</b> final 字段放进构造参数，所以它不会被 Spring 误当成依赖。
     */
    private final AtomicBoolean fallbackNotified = new AtomicBoolean(false);

    /**
     * 处理一条入站消息 —— 这是全项目唯一的消息处理入口。
     *
     * <p>无论消息来自真实的腾讯网关，还是来自本地调试接口，
     * 最终都会走到这里。这就是为什么调试入口能验证真实链路：
     * <b>它和真消息走的是同一段代码</b>，只是把"进门"的方式换了一下。
     *
     * @param message 入站消息
     * @return 处理结果。调用方应当先检查 {@code hasReply()} 再取文本——
     *         因为"没有回复"可能是被限流、内容为空、结果为空等多种情况，
     *         具体原因在 {@link DispatchOutcome} 里。别再用返回 {@code null}
     *         表示"没回复"了，那样调用方只能靠猜。
     */
    public DispatchOutcome handle(QqMessage message) {

        // ============ 步骤 0：前置校验 ============
        String userId = message.getUserId();
        if (userId == null || userId.isBlank()) {
            // 没有用户标识就不知道该回复给谁，只能丢弃。
            // 出现这种情况通常意味着通道层解析出了问题，所以用 WARN 级别
            log.warn("丢弃消息：缺少 userId。原始内容：{}", forLog(message.getContent()));
            return DispatchOutcome.ignored(DispatchOutcome.IgnoreReason.NO_USER_ID);
        }

        String content = message.normalizedContent();
        if (content.isEmpty()) {
            // 空消息（用户误发、或只发了空格）直接忽略，不回复也不报错
            log.debug("忽略空消息，来自 {}", shortId(userId));
            return DispatchOutcome.ignored(DispatchOutcome.IgnoreReason.EMPTY_CONTENT);
        }

        // ============ 步骤 1：限流检查 ============
        if (rateLimiter.shouldLimit(userId)) {
            // 刻意"静默丢弃"而不是回复一句"你发得太快了"：
            // 如果每次都回复提示，用户连发 10 条就会收到 10 条提示，反而变成刷屏。
            // 这里只记日志，让管理员能从日志看出有人在刷。
            log.warn("用户 {} 触发限流，消息已被丢弃：{}", shortId(userId), forLog(content));
            return DispatchOutcome.ignored(DispatchOutcome.IgnoreReason.RATE_LIMITED);
        }

        // ============ 步骤 2：构建用户上下文（权限判断）============
        // 权限来自服务端查白名单，与用户说了什么完全无关。
        // 这一点很关键：不能让模型或用户输入影响权限判断。
        UserContext user = adminWhitelist.buildContext(userId);

        QqScene scene = message.getScene() == null ? QqScene.UNKNOWN : message.getScene();
        log.info("收到消息 [{}] {}：{}", scene.getLabel(), user.toLogTag(), forLog(content));

        // ============ 步骤 2.5：群聊消息的分流 ============
        // 判定顺序很关键：它在"斜杠命令"之前，但在"@机器人"之后。
        //
        // 判据是 mentionedBot —— 它区分了两种形态几乎一样、处理方式却完全相反的事件：
        //   GROUP_AT_MESSAGE_CREATE（@了机器人）→ 必须回
        //   GROUP_MESSAGE_CREATE  （群里的闲聊）→ 不能直接回
        String hotChatKey = conversationKey(message);
        boolean isSlash = message.isSlashCommand();
        boolean isGroupChatWithoutMention = !message.isMentionedBot() && hotChatKey != null;

        if (!isSlash && isGroupChatWithoutMention) {
            if (hotChatMode.isEnabled()) {
                // 热聊开启：进缓冲，由节流 + 模型判断要不要插一句
                return handleHotChat(message, user, hotChatKey, content);
            }

            // 热聊关闭：群里没 @ 机器人的话，收下但不回应。
            //
            // 【这一支是整个改动里最要紧的地方】
            // 没有它的话，开了「接收所有消息」之后，群里每一条闲聊
            // 都会被当成"对我说的"，机器人会把整个群刷爆。
            //
            // 参考实现（OpenClaw 的 group messages）把这个行为叫
            // "store for context only" —— 消息收下但只用于理解上下文，不回复。
            // 它比"完全不接收"更好：将来开了热聊，这些消息就是现成的上下文。
            hotChatBuffer.record(hotChatKey, message.getUserId(),
                    shortId(message.getUserId()), content, false);
            log.debug("群消息未@机器人且热聊未开启，只存上下文不回复：{}", forLog(content));
            return DispatchOutcome.ignored(DispatchOutcome.IgnoreReason.NOT_MENTIONED);
        }

        // ============ 步骤 3：判定类型并处理 ============
        String replyText;
        if (message.isSlashCommand()) {
            // ---- 分支 A：斜杠命令，不走大模型（规格第 5.1 节第 ① 步）----
            log.info("路由判定 → 斜杠命令，不调用大模型");
            replyText = commandRouter.dispatch(message, user);
        } else {
            // ---- 分支 B：普通聊天（本阶段不论管理员还是普通用户都走这里）----
            // 这里只报"当前路由会走谁"，真正的选择发生在 handleChat 里。
            // 用一个带降级说明的文本，是为了让日志能解释"为什么走了云端"——
            // 本地离线时降级是常态，但看日志的人需要一眼看出这次是降级还是本来就走云端
            log.info("路由判定 → 聊天，{}", modelRouter.describeDecision());
            replyText = handleChat(message, user);
        }

        if (replyText == null || replyText.isBlank()) {
            log.warn("处理结果是空内容，不发送，来自 {}", user.toLogTag());
            return DispatchOutcome.ignored(DispatchOutcome.IgnoreReason.EMPTY_REPLY);
        }

        // ============ 步骤 4：发送回复 ============
        String finalText = truncateForReply(replyText);
        sendReply(message, finalText);

        return DispatchOutcome.replied(finalText);
    }

    /**
     * 计算这条消息属于哪个"会话"——也就是热聊上下文按什么分组。
     *
     * <p><b>群聊用群号、单聊返回 null</b>：
     * <ul>
     *   <li>群聊必须按群分组。腾讯网关是<b>一条连接收所有群的消息</b>，
     *       如果所有群的发言混进一个列表，模型会看到 A 群和 B 群交替出现的
     *       "对话"，回复必然牛头不对马嘴</li>
     *   <li>单聊返回 {@code null}，表示"这个会话不参与热聊"。
     *       单聊里不存在"多方发言再决定插不插嘴"这件事——
     *       就是两个人在对话，机器人主动说话只会显得莫名其妙。
     *       配置 {@code app.hot-chat.group-only} 为 false 时才会放行单聊</li>
     * </ul>
     *
     * @param message 入站消息
     * @return 会话标识；不参与热聊的会话返回 {@code null}
     */
    private String conversationKey(QqMessage message) {
        String groupId = message.getGroupId();
        if (groupId != null && !groupId.isBlank()) {
            return "group:" + groupId;
        }
        if (appProperties.getHotChat().isGroupOnly()) {
            return null;
        }
        // 放行单聊时，用发送者标识做会话键
        return "c2c:" + message.getUserId();
    }

    /**
     * 热聊模式下的消息处理 —— 缓冲上下文，判断是否该搭话。
     *
     * <p>═══════════════════════════════════════════════════════════
     * <p><b>最要紧的一点：这里的返回值经常是"不回复"</b>
     *
     * <p>被动模式下，走到聊天分支就一定要给个回复。但热聊不是——
     * 群里绝大多数消息都<b>不需要机器人插嘴</b>。所以本方法有三条
     * "不回复"的出路，而且它们都是正常结果，不是故障：
     *
     * <ol>
     *   <li>还没攒够消息 / 距上次搭话太近（节流）→ {@code HOT_CHAT_BUFFERED}</li>
     *   <li>模型自己选择沉默（输出 {@code [沉默]}）→ {@code HOT_CHAT_SILENT}</li>
     *   <li>模型返回了空内容 → {@code EMPTY_REPLY}（这个才是异常）</li>
     * </ol>
     *
     * <p>把前两种和第三种在 {@link DispatchOutcome} 里分开，
     * 是为了让日志和调试接口能区分"正常地不说话"和"出了意外"。
     *
     * <p>═══════════════════════════════════════════════════════════
     * <p><b>提示词是怎么拼的</b>
     *
     * <pre>
     *   System Prompt = 人设（/model-p 决定）
     *                 + "\n\n---\n\n"
     *                 + SysPrompt/chat_hot.md（怎么搭话的规则）
     * </pre>
     *
     * <p>两段各管一件事，所以可以独立更换：人设管"机器人是谁"，
     * 热聊提示词管"这个场合怎么说话"。换身份不需要碰搭话规则。
     *
     * @param message       入站消息
     * @param user          用户上下文
     * @param conversationKey 会话标识
     * @param content       消息正文
     * @return 处理结果
     */
    private DispatchOutcome handleHotChat(QqMessage message, UserContext user,
                                          String conversationKey, String content) {
        AppProperties.HotChat config = appProperties.getHotChat();

        // ---- 第一件事：把这条消息放进上下文 ----
        // 顺序上必须先记录再判断要不要搭话。因为"该不该搭话"的判断依据里
        // 包含"已经攒了多少条"，而当前这条也算一条。
        //
        // 关于 senderName：腾讯的消息事件里没有昵称字段，只有 openid。
        // 所以这里用标识的前几位当代号。模型不需要知道真实昵称，
        // 它只需要能分辨"这句和上句不是同一个人说的" —— 短标识足够做到。
        hotChatBuffer.record(conversationKey, message.getUserId(),
                shortId(message.getUserId()), content, false);

        // ---- 第二件事：节流判断 ----
        long lastSpokenAt = hotChatMode.secondsSinceLastSpeak(conversationKey) < 0
                ? 0L
                : System.currentTimeMillis() - hotChatMode.secondsSinceLastSpeak(conversationKey) * 1000L;
        int recentCount = hotChatBuffer.countSince(conversationKey, lastSpokenAt);

        if (!hotChatMode.shouldSpeak(conversationKey, recentCount)) {
            log.debug("热聊：已缓冲（本会话距上次搭话后累计 {} 条，未达触发条件）", recentCount);
            return DispatchOutcome.ignored(DispatchOutcome.IgnoreReason.HOT_CHAT_BUFFERED);
        }

        // ---- 第三件事：拼提示词并调模型 ----
        // 人设在前、搭话规则在后，中间用分隔线隔开。
        // 顺序不能反：人设是"你是谁"，必须先立住身份；
        // 搭话规则是"现在该怎么做"，跟在身份之后才读着自然。
        String persona = promptManager.loadPromptFor(user);
        String hotChatRule = promptLoader.load(config.getPromptFile());
        String systemPrompt = persona + "\n\n---\n\n" + hotChatRule;

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        // 上下文里不含当前这条（由 excludePending 排掉），它会作为最后一条单独加上，
        // 这样模型看到的最后一句就是"刚刚有人说的话"，才知道该接什么
        messages.addAll(hotChatBuffer.contextAsMessages(conversationKey, content));
        messages.add(ChatMessage.user("【" + shortId(message.getUserId()) + "】" + content));

        ChatModelClient target = modelRouter.chatModelClient(user.getUserId());
        String provider = target.getProviderName();

        String reply;
        try {
            log.info("热聊搭话（会话 {}，上下文 {} 条，提供方 {}）",
                    conversationKey, messages.size() - 1, provider);
            reply = target.chat(messages);
        } catch (Exception e) {
            // 热聊失败不发道歉消息。理由：这一条本来就不是用户问的，
            // 冒出一句"我这边出了点问题"对群里的人是纯打扰。
            // 只记日志，让管理员去查
            log.error("热聊调用模型失败，会话 {}，提供方 {}", conversationKey, provider, e);
            return DispatchOutcome.ignored(DispatchOutcome.IgnoreReason.EMPTY_REPLY);
        }

        // ---- 第四件事：判断模型是不是选择了沉默 ----
        if (reply == null || reply.isBlank()) {
            log.warn("热聊：模型返回空内容，会话 {}", conversationKey);
            return DispatchOutcome.ignored(DispatchOutcome.IgnoreReason.EMPTY_REPLY);
        }
        if (reply.contains(config.getSilentToken())) {
            // 模型主动选择不说话 —— 这是正常结果，而且要记 INFO 而不是 WARN，
            // 否则一个热闹的群会把日志刷满"警告"，掩盖真正的问题
            log.info("热聊：模型选择沉默（会话 {}），本次不发言", conversationKey);
            return DispatchOutcome.ignored(DispatchOutcome.IgnoreReason.HOT_CHAT_SILENT);
        }

        // ---- 第五件事：发言并记账 ----
        String finalText = truncateForReply(reply);

        // 先记"说过了"再发送，还是先发送再记？
        // 选择先记：万一 send 抛异常，至少节流计时是准的，
        // 不会出现"发送失败了但机器人立刻又抢着说一句"。
        hotChatMode.markSpoken(conversationKey);

        // 机器人自己的发言也要回到缓冲里。不回的话，下一轮模型
        // 看不到"我刚说过什么"，会在同一个话题上反复搭话
        hotChatBuffer.record(conversationKey, "bot", null, finalText, true);

        // 热聊的对话也写进该用户的记忆 —— 这样之后他和机器人单聊时，
        // 机器人还记得"这个人在群里聊过什么"
        conversationMemory.appendTurn(user.getUserId(), content, finalText);

        sendReply(message, finalText);
        log.info("热聊：已搭话（会话 {}，{} 字）", conversationKey, finalText.length());

        return DispatchOutcome.replied(finalText);
    }

    /**
     * 处理聊天消息：拼装上下文 → 调模型 → 记录历史。
     *
     * <p><b>本方法的上下文拼装顺序不能变</b>：
     * <ol>
     *   <li><b>人设</b>（{@code system} 角色）必须在最前面——它在给模型定身份，
     *       放后面会被模型当成"对话的一部分"而不是"指令"</li>
     *   <li><b>历史</b>在中间，从旧到新</li>
     *   <li><b>用户这一句</b>在最后——模型看到最后一条是用户说的，才知道该它接话</li>
     * </ol>
     *
     * @param message 入站消息
     * @param user    用户上下文
     * @return 模型生成的回复，或出错时的道歉文本
     */
    private String handleChat(QqMessage message, UserContext user) {

        // ---- 3.1 挑人设（"你是谁"）----
        // 默认按身份区分：管理员和普通用户加载不同的人设 md（规格第 4.3 节的双身份设计）。
        // 但管理员可以用 /model-p 全局覆盖这个规则——那时所有人
        //（包括管理员自己）都用被选中的那一个文件。
        String promptName = promptManager.resolvePromptName(user);
        String persona = promptManager.loadPromptFor(user);

        // ---- 3.2 拼上"场合规则"（"这个场合怎么说话"）----
        // 提示词分两层是刻意的：人设由 /model-p 决定（你会为不同角色写不同的 cos 文件），
        // 而场合规则跟角色无关，只跟"当前是被直接对话还是在群里插话"有关。
        //
        // 这里用的是普通对话的规则；热聊模式下用的是 chat_hot.md（在 handleHotChat 里拼）。
        // 分成两个文件的好处：加新角色时不用把行为约束再抄一遍。
        String sceneRule = promptLoader.load(appProperties.getChat().getNormalPromptFile());
        String systemPrompt = persona + "\n\n---\n\n" + sceneRule;

        // ---- 3.3 拼装上下文 ----
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.addAll(conversationMemory.history(user.getUserId()));
        messages.add(ChatMessage.user(message.normalizedContent()));

        int estimatedTokens = messages.stream().mapToInt(ChatMessage::estimateTokens).sum();
        log.debug("上下文拼装完成：共 {} 条消息，估算 {} tokens（人设 {}，{} 字）",
                messages.size(), estimatedTokens, promptName, systemPrompt.length());

        // ============================================================
        // ★★★ 算力路由：这次交给谁算 ★★★
        //
        // 选择动作本身在 ModelRouter 里完成，本类只负责问它要一个客户端。
        // 这样分层的好处是：将来"云端把任务派给本地节点"时（经
        // /ws/local-agent 通道），改动只发生在 ModelRouter 的实现里，
        // 消息分发这条主链路一行都不用动。
        // ============================================================
        ChatModelClient target = modelRouter.chatModelClient(user.getUserId());
        String provider = target.getProviderName();

        // 降级提醒：策略要求用本地、但本地起不来、于是这条走了云端。
        // 必须在回复里说一声，否则用户会以为在用免费的本机模型，其实在花钱。
        // 返回空串表示"这次不用提醒"（不是降级，或者已经提醒过了）
        String fallbackNotice = noticeIfFellBackToCloud(provider);

        try {
            String reply = target.chat(messages);

            // 只有成功时才写入历史。失败时写入的话，历史里会出现
            // "用户说了话，机器人回了一句道歉"，污染后续对话的上下文
            conversationMemory.appendTurn(
                    user.getUserId(), message.normalizedContent(), reply);

            // 提醒只挂在回复末尾，**不写进记忆** —— 它是运行状态说明，
            // 不是机器人"说过的话"，混进历史会污染后续对话
            return reply + fallbackNotice;

        } catch (NoAvailableModelException e) {
            // 这条分支专门处理「策略不允许降级、而本地又不在线」。
            // 它和下面的通用异常分开，是因为这两件事对用户的意义完全不同：
            // 前者是配置选择的结果（用户自己做什么都没用，得管理员改策略），
            // 后者是运行时故障（重启本地服务可能就好了）。
            // 用 WARN 而不是 ERROR：这不是故障，是策略生效的正常结果
            log.warn("无可用算力，用户 {}，策略 {}：{}",
                    user.toLogTag(), modelRouter.currentStrategy().name(), e.getMessage());
            return e.getMessage();

        } catch (ModelCallException e) {
            // ★ 模型调用失败，具体原因已由客户端分好类装进异常里。
            // 这里不再按"提供方是 ollama 还是 cloud"猜一句话 ——
            // 那样会把"余额不足"说成"连不上"，把用户引向完全错误的排查方向
            // （真发生过：账户欠费，却一直在查网络和防火墙）。
            // 现在直接转述异常带来的那句话，技术细节进日志
            log.error("调用大模型失败，用户 {}，提供方 {}：{}",
                    user.toLogTag(), provider, e.getMessage(), e);
            // 失败时也要带上降级提醒。理由：用户明明是在本机跑的，
            // 却收到一句"云端余额不足"，他会完全摸不着头脑 ——
            // 必须同时告诉他"为什么这次会用云端"。
            // （这个问题是实测时发现的：成功分支拼了提醒，失败分支漏了）
            return e.getUserMessage() + fallbackNotice;

        } catch (Exception e) {
            // 兜底：理论上走不到这里（两个客户端都该抛 ModelCallException）。
            // 留着是为了将来新增客户端时万一漏了转换，不至于把堆栈直接发给用户
            log.error("调用大模型失败（未分类异常），用户 {}，提供方 {}",
                    user.toLogTag(), provider, e);
            return "抱歉，我这边暂时出了点问题，稍后再试试。";
        }
    }

    /**
     * 如果这次是"降级到云端"，返回要附在回复后面的一句说明；否则返回空串。
     *
     * <p><b>为什么非要说一声</b>：本地模型起不来时，程序会默默改用云端。
     * 从"能不能用"的角度看这是好事（机器人照常回话），但从"花谁的钱"的角度
     * 看是坏事 —— 用户以为在用免费的本机模型，实际在按 token 付费。
     * 真实发生过一次：账户欠费导致所有回复失败，而用户完全不知道
     * 自己一直在走云端。
     *
     * <p><b>判定条件</b>（三条同时成立才算降级）：
     * <ol>
     *   <li>策略<b>要求</b>用本地（{@code LOCAL_FIRST} / {@code LOCAL_ONLY}）</li>
     *   <li>本地算力此刻<b>不可用</b></li>
     *   <li>实际拿到的是<b>云端</b>客户端</li>
     * </ol>
     * 第 3 条顺带排除了 {@code LOCAL_ONLY}：那个策略在本地不可用时直接抛
     * {@link NoAvailableModelException}，根本走不到这里。
     *
     * <p><b>提醒频率</b>：日志每次降级都打 WARN（排查时能看到完整轨迹），
     * 但回复里只挂一次（见 {@link #fallbackNotified} 的说明）。
     *
     * <p><b>文案是平台无关的</b>：只说"本机模型服务"，不提 WSL ——
     * 将来的云服务器是 Linux，那边没有 WSL 这个概念。
     * 只有"用 /setlocal 拉起"这条建议是 Windows 特有的，所以单独做了平台判断。
     *
     * @param provider 本次实际使用的算力名（{@code cloud} / {@code ollama}）
     * @return 要附在回复后面的一段话；不需要提醒时返回空串
     */
    private String noticeIfFellBackToCloud(String provider) {
        if (!"cloud".equalsIgnoreCase(provider)) {
            // 没走云端，谈不上降级
            return "";
        }

        RouteStrategy strategy = modelRouter.currentStrategy();
        if (!strategy.requiresLocal() || modelRouter.isLocalNodeOnline()) {
            // 两种情况都不算降级，不用提醒：
            //   1. 策略本来就不要求本地（CLOUD_FIRST / CLOUD_ONLY）——走云端是正常结果
            //   2. 本地此刻可用 —— 那 ModelRouter 会选本地，压根轮不到云端
            return "";
        }

        // 到这里说明：策略要本地、本地不可用、实际走了云端 —— 确实降级了
        String recoverHint = isWindows()
                ? "让本机模型服务跑起来（在本机发 "
                        + CommandNames.withSlash(CommandNames.SET_LOCAL) + " 可以试自动拉起）"
                : "让本机模型服务跑起来";

        log.warn("""
                
                ================================================================
                本地算力不可用，这条消息已降级到云端
                ----------------------------------------------------------------
                策略：{}（要求用本地）
                本地：不可用（本机模型服务探测失败）
                实际：云端，会按 token 计费
                
                恢复方法：{}
                ================================================================""",
                strategy.name(), recoverHint);

        // 只提醒一次：compareAndSet 保证并发下也只有一个线程能把它从 false 翻成 true
        if (this.fallbackNotified.compareAndSet(false, true)) {
            return "\n\n（本机模型服务没起来，这条先用云端回的；后面还这样的话就不重复说了）";
        }
        return "";
    }

    /**
     * 当前是不是 Windows。
     *
     * <p>只用来决定"要不要建议 {@code /setlocal}"—— 那条命令是拉起
     * WSL 里的本机模型服务的，只在 Windows 上有意义。
     * 将来部署到 Linux 云服务器时，那台机器上本来就没有模型服务可拉，
     * 也就给不出这个建议。
     *
     * @return Windows 返回 {@code true}
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * 把回复通过消息通道发出去。
     *
     * <p>发送失败只记日志、不抛异常：走到这一步时业务逻辑已经完成，
     * 抛异常不会让消息发出去，只会让调用方看到一条错误。
     *
     * @param message 原始入站消息（回复要带上它的 msgId 才能算被动回复）
     * @param text    回复文本
     */
    private void sendReply(QqMessage message, String text) {
        QqReply reply = QqReply.replyTo(message, text);

        // 检查是否还在被动回复的有效期内（单聊 60 分钟，群聊只有 5 分钟）。
        // 本地小模型算得慢，群聊场景有可能算完已经超时了。
        boolean withinWindow = message.getScene() == null
                || message.getScene().isWithinPassiveReplyWindow(message.getReceivedAtMillis());

        if (!withinWindow) {
            // 超时后如果还带 msgId 去发，腾讯会拒绝。这里把 msgId 清掉，
            // 降级成主动消息（代价是消耗主动消息配额，但至少用户能看到回复）。
            log.warn("被动回复已超时（场景 {}），降级为主动消息发送",
                    message.getScene() == null ? "未知" : message.getScene().getLabel());
            reply.setMsgId(null);
            reply.setMsgSeq(null);
        }

        try {
            messageChannel.send(reply);
            log.info("回复已发出（{} 字，通道 {}）", text.length(), messageChannel.getType());
        } catch (Exception e) {
            log.error("发送回复失败，通道 {}，收件人 {}",
                    messageChannel.getType(), shortId(reply.getUserId()), e);
        }
    }

    /**
     * 回复超长时截断。
     *
     * <p>宁可主动截断并告诉用户"内容过长"，也不要让腾讯接口因为超长而拒绝——
     * 后者表现为机器人完全不回话，用户根本不知道发生了什么。
     *
     * @param text 原始回复
     * @return 长度受限的回复
     */
    private String truncateForReply(String text) {
        if (text.length() <= MAX_REPLY_LENGTH) {
            return text;
        }
        String suffix = "\n…（内容过长已截断）";
        return text.substring(0, MAX_REPLY_LENGTH - suffix.length()) + suffix;
    }

    /**
     * 把文本压成单行并截断，用于日志输出。
     *
     * @param text 原始文本
     * @return 适合打日志的短文本
     */
    private String forLog(String text) {
        if (text == null) {
            return "null";
        }
        // 换行会破坏日志的可读性（一条日志被拆成几行），统一压成空格
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= LOG_CONTENT_MAX_LENGTH
                ? oneLine
                : oneLine.substring(0, LOG_CONTENT_MAX_LENGTH) + "…";
    }

    /**
     * 取用户标识的前几位，用于日志。
     *
     * @param userId 完整用户标识
     * @return 截断后的短标识
     */
    private String shortId(String userId) {
        if (userId == null) {
            return "null";
        }
        return userId.length() > 6 ? userId.substring(0, 6) + "…" : userId;
    }
}
