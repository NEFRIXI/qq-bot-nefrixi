package com.mybotweb.command;

import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令路由 —— 负责把 {@code "/search 大模型"} 这样的文本，
 * 变成"找到 search 处理器 → 检查权限 → 执行 → 拿到文本"。
 *
 * <p><b>它属于哪一层？</b> 消息进来后的第一步判断是"是不是斜杠命令"，
 * 如果是，就交给本类。所以它在 {@code MessageDispatcher} 的下游。
 *
 * <p><b>本类只做三件事</b>：解析、查表、鉴权。具体每条命令怎么干活，
 * 由各个 {@link CommandHandler} 实现类负责。这样加新命令时
 * 完全不用碰这个文件。
 *
 * <p><b>{@code List<CommandHandler>} 是怎么来的？</b>
 * Spring 在创建本 Bean 时，发现构造器需要一个 {@code List<CommandHandler>}，
 * 它会自动把所有实现了该接口的 Bean（也就是所有标了 {@code @Component}
 * 的命令类）收集成一个列表注入进来。这叫"集合注入"，
 * 是本项目里唯一一处"不用改代码就能加功能"的地方。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class CommandRouter {

    /**
     * 命令名（含别名）到处理器的索引。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code HashMap}，是为了让
     * 遍历顺序等于注册顺序——{@code /help} 列出来的命令顺序才是稳定的，
     * 而不是每次启动都换一个样。
     */
    private final Map<String, CommandHandler> handlerIndex = new LinkedHashMap<>();

    /**
     * 所有命令处理器，保持注入顺序，供 {@code /help} 展示。
     */
    private final List<CommandHandler> handlers;

    /**
     * 构造器注入。
     *
     * <p>Spring 推荐构造器注入而不是字段注入（{@code @Autowired} 标在字段上）：
     * <ul>
     *   <li>字段可以是 {@code final}，对象一旦创建就不可变</li>
     *   <li>依赖关系一眼可见，不会藏着一堆字段</li>
     *   <li>单元测试时可以直接 {@code new CommandRouter(list)}，不需要启动 Spring</li>
     * </ul>
     *
     * <p>构造器里顺手做了两件校验，让配错的情况在<b>启动时</b>就暴露，
     * 而不是等用户敲了命令才发现。
     *
     * @param handlers Spring 收集到的所有命令实现
     */
    public CommandRouter(List<CommandHandler> handlers) {
        this.handlers = List.copyOf(handlers);

        for (CommandHandler handler : this.handlers) {
            String name = handler.getName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                        "命令处理器 " + handler.getClass().getSimpleName() + " 没有返回命令名");
            }

            // 命令名统一按小写建索引，这样用户打 /HELP 也能命中 /help
            register(name.toLowerCase(), handler, handler.getClass().getSimpleName());

            // 别名同样建索引，且别名与命令名、别名与别名之间都不能撞车
            for (String alias : handler.getAliases()) {
                register(alias.toLowerCase(), handler, handler.getClass().getSimpleName());
            }
        }

        log.info("命令路由初始化完成，共注册 {} 个命令，索引 {} 个名称：{}",
                this.handlers.size(), this.handlerIndex.size(), this.handlerIndex.keySet());
    }

    /**
     * 把一个名称登记进索引，重复则直接启动失败。
     *
     * @param name           命令名或别名（已转小写）
     * @param handler        对应的处理器
     * @param handlerClassName 处理器类名，只用于报错信息
     */
    private void register(String name, CommandHandler handler, String handlerClassName) {
        CommandHandler exists = this.handlerIndex.putIfAbsent(name, handler);
        if (exists != null) {
            // 重名必须直接失败：否则两个命令抢一个名字，行为取决于加载顺序，很难查
            throw new IllegalStateException(String.format(
                    "命令名冲突：'%s' 被 %s 和 %s 同时注册",
                    name, exists.getClass().getSimpleName(), handlerClassName));
        }
    }

    /**
     * 处理一条斜杠命令消息。
     *
     * <p>执行顺序固定为：<b>解析 → 查命令 → 判权限 → 执行</b>。
     * 权限判断必须在执行之前，这是规格第 12 节的关键约束之一。
     *
     * @param message 入站消息，内容必须以 {@code /} 开头
     * @param user    当前用户上下文
     * @return 要发给用户的文本。任何情况下都返回一句人话，不会返回 {@code null}
     */
    public String dispatch(QqMessage message, UserContext user) {
        String raw = message.normalizedContent();

        // 切掉开头的 "/"，得到 "help" 或 "search 大模型"
        String body = raw.substring(1).strip();
        if (body.isEmpty()) {
            return "你想执行什么命令？发 "
                    + CommandNames.withSlash(CommandNames.HELP) + " 可以看到全部命令。";
        }

        String[] parts = splitArguments(body);
        String commandName = parts[0].toLowerCase();

        // 参数是除去命令名之后的部分。/help 时会得到长度为 0 的数组
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        CommandHandler handler = handlerIndex.get(commandName);
        if (handler == null) {
            log.debug("未识别的命令：{}（来自 {}）", commandName, user.toLogTag());
            return buildUnknownCommandMessage(commandName);
        }

        // ---- 权限校验：这是防越权的关键一步，必须在这里，不能交给命令自己 ----
        if (handler.isAdminOnly() && !user.isAdmin()) {
            log.warn("权限拒绝：{} 尝试执行管理员命令 /{}", user.toLogTag(), commandName);
            // 提示语必须给出"怎么才能用"，而不只是"你不能用"。
            //
            // 这里踩过一个坑：最初的提示只有"这个命令只有管理员可以使用",
            // 结果第一次部署时直接卡死——那时没有 /add_admin 也没有 /admin，
            // "我的标识是多少"没有地方能查到，于是永远配不上白名单。
            // 现在有了申请制和自举，提示语要指向它们，而不是指向配置文件。
            return """
                    这个命令只有管理员可以使用。
                    
                    想成为管理员？发送 %s 申请，由现有管理员批准。
                    
                    （如果你本来就是管理员却被拦了，可能是名单被清过：
                     让其他管理员发 %s add 你的标识 重新加上；
                     或者若名单里一个人都没有，你自己发 %s 即可重新初始化）"""
                    .formatted(
                            CommandNames.withSlash(CommandNames.ADD_ADMIN),
                            CommandNames.withSlash(CommandNames.ADMIN),
                            CommandNames.withSlash(CommandNames.ADMIN));
        }

        try {
            log.debug("执行命令 /{}，参数 {} 个，来自 {}", commandName, args.length, user.toLogTag());
            String result = handler.execute(message, user, args);

            // 兜住"实现类返回 null"的情况，避免把 null 发给用户
            if (result == null || result.isBlank()) {
                log.warn("命令 /{} 返回了空内容", commandName);
                return "命令执行完成，但没有输出内容。";
            }
            return result;

        } catch (Exception e) {
            // 命令内部出错不能把异常抛到消息链路上去——那样用户只能看到机器人没反应。
            // 这里兜住，给用户一句能看懂的话，同时把堆栈完整记进日志供排查。
            log.error("命令 /{} 执行失败，用户 {}，参数 {}", commandName,
                    user.toLogTag(), String.join(" ", args), e);
            return "命令执行出错了，请联系管理员查看日志。（命令：" + commandName + "）";
        }
    }

    /**
     * 判断某个命令名是否存在（含别名）。
     *
     * @param commandName 命令名，不带斜杠
     * @return 存在返回 {@code true}
     */
    public boolean supports(String commandName) {
        return commandName != null && handlerIndex.containsKey(commandName.toLowerCase());
    }

    /**
     * 取所有命令处理器，供 {@code /help} 展示。
     *
     * <p>返回的是不可变列表，调用方拿到后不能修改——避免某处不小心把命令删掉了。
     *
     * @return 所有命令处理器
     */
    public List<CommandHandler> getAllHandlers() {
        return this.handlers;
    }

    /**
     * 生成"没有这个命令"的提示，并尽量猜一下用户想打什么。
     *
     * <p><b>为什么要猜？</b> 命令名都是英文，打错一两个字母是常事
     * （{@code /whomi} 少个 a、{@code /modle} 字母顺序颠倒）。
     * 只回一句"没有这个命令"，用户得自己去 {@code /help} 里一个个对照着找。
     * 而如果那个命令恰好是他救急要用的（比如 {@code /whoami}），这一下就卡住了
     * ——第一次部署时正是这么卡住的。
     *
     * @param commandName 用户输入的命令名
     * @return 提示文本
     */
    private String buildUnknownCommandMessage(String commandName) {
        StringBuilder sb = new StringBuilder();
        sb.append("没有这个命令：").append(commandName).append('\n');

        String suggestion = findSimilarCommandName(commandName);
        if (suggestion != null) {
            sb.append("\n你是不是想输入 /").append(suggestion).append(" ？\n");
        }

        sb.append("\n发 /help 可以看到全部命令。");
        return sb.toString();
    }

    /**
     * 在所有已注册的名字（含别名）里，找一个和输入最接近的。
     *
     * @param input 用户输入的命令名
     * @return 最接近的名字；差得太远时返回 {@code null}（宁可不说，也不瞎猜）
     */
    private String findSimilarCommandName(String input) {
        String lowerInput = input.toLowerCase();
        String best = null;
        int bestDistance = Integer.MAX_VALUE;

        for (String name : handlerIndex.keySet()) {
            int distance = levenshteinDistance(lowerInput, name.toLowerCase());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = name;
            }
        }

        // 只在"足够接近"时才建议。阈值随名字长度放宽：
        // 短名字（model 只有 5 个字母）差 1 个就已经是另一个词了，
        // 长名字（whoami）差 2 个还算能接受
        int threshold = lowerInput.length() <= 4 ? 1 : 2;
        return (best != null && bestDistance <= threshold) ? best : null;
    }

    /**
     * 计算两个字符串的编辑距离（Levenshtein distance）。
     *
     * <p>含义是"把 A 改成 B 最少要几步"（每步可以是插入、删除、替换一个字符），
     * 距离越小说明两个词越像。这是拼写纠错最常用的算法，
     * 实现也就十几行，不值得为它引一个依赖库进来。
     *
     * @param a 字符串一
     * @param b 字符串二
     * @return 编辑距离
     */
    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        // 边界：把 a 的前 i 个字符全删掉要 i 步；把 b 的前 j 个字符全插入要 j 步
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int substituteCost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1,          // 从 a 删掉一个字符
                                 dp[i][j - 1] + 1),         // 往 a 插入一个字符
                        dp[i - 1][j - 1] + substituteCost   // 替换一个字符
                );
            }
        }
        return dp[a.length()][b.length()];
    }

    /**
     * 把命令正文按空白拆成"命令名 + 参数"。
     *
     * <p>例如 {@code search 大模型} → {@code ["search", "大模型"]}。
     *
     * <p><b>当前实现的局限</b>：只按空白拆，不支持引号包裹带空格的内容。
     * 所以后面做 {@code /add} 这类多参数命令时，如果标题里想带空格，
     * 用户得改用下划线或短横线。等真正需要时，把这里换成
     * "识别双引号"的版本即可，改动只在本方法内部。
     *
     * <p>连续多个空格会被自动忽略（正则 {@code \\s+} 的效果），
     * 用户在手机上多打了空格也不影响。
     *
     * @param body 去掉斜杠之后的内容
     * @return 拆分结果，第一个元素一定是命令名
     */
    private String[] splitArguments(String body) {
        List<String> tokens = new ArrayList<>();
        for (String token : body.split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        // 理论上 body 非空时 tokens 至少有一个元素；这里做个兜底防止数组越界
        if (tokens.isEmpty()) {
            tokens.add("");
        }
        return tokens.toArray(new String[0]);
    }
}
