package com.mybotweb.command.impl;

import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.command.CommandHandler;
import com.mybotweb.command.CommandNames;
import com.mybotweb.command.CommandRouter;
import com.mybotweb.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * {@code /help} —— 列出命令，或查看单条命令的详细用法。
 *
 * <p>用法：
 * <pre>
 *   /help            → 列出你能用的全部命令
 *   /help search     → 看 search 命令的详细用法
 * </pre>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>★ 本类踩到了一个循环依赖，值得花两分钟看懂</b>
 *
 * <p>最直觉的写法是注入 {@code CommandRouter} 来拿全部命令。但那样会启动失败：
 * <pre>
 *   CommandRouter  的构造器需要 List&lt;CommandHandler&gt;
 *         ↓  集合里包含
 *   HelpCommand    的构造器需要 CommandRouter
 *         ↓  于是
 *   Spring 在造 A 的时候需要 B，造 B 的时候又需要 A
 * </pre>
 * 启动时报的错是：
 * <pre>
 *   The dependencies of some of the beans in the application context form a cycle
 * </pre>
 * Spring Boot 2.6 之后<b>默认禁止循环依赖</b>，这个默认行为是好事——
 * 它逼你在设计阶段就把纠缠解开，而不是留到线上出现
 * "某个 Bean 的字段莫名其妙是半成品"这种极难查的问题。
 *
 * <p><b>解法：{@code @Lazy} —— 把"立刻要"改成"用的时候再要"。</b>
 *
 * <p>加上 {@code @Lazy} 之后，Spring 注入的不是 {@code CommandRouter} 本身，
 * 而是一个<b>代理对象</b>。构造阶段拿到的只是这个代理（不需要真的创建
 * CommandRouter），等到真正调用 {@code commandRouter.getAllHandlers()} 时，
 * 代理才去容器里取真正的对象——那时所有 Bean 早已创建完毕。
 * 构造期的环因此断开。
 *
 * <p><b>我试过的另一种写法，以及为什么没用它：</b>
 * 先试的是注入 {@code ObjectProvider<CommandHandler>} 再在 {@code execute()} 里
 * {@code stream()} 取全部命令。理论上这也能延迟解析，但实际运行时
 * {@code stream()} 返回的是<b>空流</b>——因为 {@code HelpCommand} 本身
 * 是在 {@code CommandRouter} 解析那个集合的过程中被创建的，
 * 那一轮里它自己被排除在候选之外。这个问题很隐蔽：
 * 不报错，只是 {@code /help} 静静地列出 0 个命令。
 * {@code @Lazy} 没有这个坑，因为它延迟的是"取某个已命名的 Bean"，
 * 而不是"解析一批候选"，不依赖创建顺序。
 *
 * <p><b>还有第三种做法，别用</b>：把
 * {@code spring.main.allow-circular-references} 设成 {@code true}。
 * 那是把问题盖住而不是解开，以后依赖关系再复杂一点就会出更难查的问题。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>普通用户和管理员看到的是两份不同的帮助</b>
 *
 * <p>本命令是<b>普通用户视角</b>：只列出他们能用的命令。
 * 管理员命令完全不出现——看到了也用不了，只是噪音。
 *
 * <p>管理员要看完整列表，用 {@code /help_admin}。
 *
 * <p>这个设计改过一次。中间有段时间是把管理员命令也显示出来的
 * （标上【管理员】），理由是"让人知道系统有什么能力"。
 * 但实际用起来是反效果：普通用户看到一堆自己用不了的东西，
 * 而真正需要"知道有什么管理员命令"的只有管理员本人——
 * 那让他用 {@code /help_admin} 就好了。
 *
 * <p>真正的权限拦截在 {@code CommandRouter.dispatch} 里做，
 * 这里只是<b>展示层面的过滤</b>，从来不是安全措施——
 * 用户完全可以手打 {@code /status} 试一下。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class HelpCommand implements CommandHandler {

    /**
     * 命令路由，用来拿全部已注册的命令。
     *
     * <p>{@code @Lazy} 是这里的关键：没有它就会循环依赖启动失败，
     * 原因见类注释。</p>
     */
    private final CommandRouter commandRouter;

    /**
     * 构造器注入。
     *
     * <p>{@code @Lazy} 标在参数上（而不是字段上）时，
     * 效果是"为这一个依赖创建代理"。标在字段上也是可以的，
     * 但标在构造器参数上语义更明确：就是这个依赖需要延迟。
     *
     * @param commandRouter 命令路由（Spring 会注入一个延迟代理）
     */
    public HelpCommand(@Lazy CommandRouter commandRouter) {
        this.commandRouter = commandRouter;
    }

    @Override
    public String getName() {
        return CommandNames.HELP;
    }

    /** 别名：{@code /?} 是很多人的习惯打法 */
    @Override
    public List<String> getAliases() {
        return CommandNames.HELP_ALIASES;
    }

    @Override
    public String getDescription() {
        return "查看命令列表";
    }

    @Override
    public String getUsage() {
        return CommandNames.withSlash(CommandNames.HELP) + " [命令名]";
    }

    /**
     * 执行帮助命令。
     *
     * @param message 入站消息
     * @param user    当前用户
     * @param args    参数：有值时视为要查看详情的命令名
     * @return 帮助文本
     */
    @Override
    public String execute(QqMessage message, UserContext user, String[] args) {

        // 走到这里时代理会去容器取真正的 CommandRouter，
        // 它构造时已经把全部命令建好索引了
        List<CommandHandler> handlers = commandRouter.getAllHandlers();

        // ---- 带参数：查看某条命令的详细用法 ----
        if (args.length > 0) {
            return describeCommand(args[0], user, handlers);
        }

        // ---- 不带参数：列出普通用户能用的命令 ----
        List<CommandHandler> visible = handlers.stream()
                // 过滤掉管理员命令：普通用户看不到，管理员走 /help_admin
                .filter(handler -> !handler.isAdminOnly())
                // 再过滤掉"标记为隐藏"的命令。
                // 这类命令的代表是 /admin —— 它权限是动态的（名单为空时人人可用），
                // 所以不能标 isAdminOnly，但也不该出现在这里
                .filter(handler -> !handler.isHiddenFromHelp())
                // 按命令名排序，保证输出顺序稳定（不然重启一次顺序就变）
                .sorted(Comparator.comparing(CommandHandler::getName))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("命令列表（").append(visible.size()).append(" 个）：\n");

        for (CommandHandler handler : visible) {
            sb.append(handler.getUsage())
                    .append(" —— ")
                    .append(handler.getDescription());
            if (handler.isAdminOnly()) {
                sb.append("【管理员】");
            }
            sb.append('\n');
        }

        sb.append("\n发 " + CommandNames.withSlash(CommandNames.HELP)
                + " 命令名 可以看某条命令的详细说明。");

        // 顺便告诉普通用户有闲聊能力，否则他可能以为机器人只有命令
        if (!user.isAdmin()) {
            sb.append("\n也可以直接跟我聊天，不用加斜杠。");
            sb.append("\n\n想要管理员权限？发送 "
                    + CommandNames.withSlash(CommandNames.ADD_ADMIN) + " 申请。");
        } else {
            // 管理员的话，提示他还有一份完整列表
            sb.append("\n想看包含管理员命令的完整列表：/help_admin");
        }

        return sb.toString();
    }

    /**
     * 输出单条命令的详细说明。
     *
     * @param commandName 用户输入的命令名（可能带斜杠，也可能大小写混杂）
     * @param user        当前用户，决定能不能看到管理员命令的详情
     * @param handlers    全部命令
     * @return 详情文本，或"没有这个命令"的提示
     */
    private String describeCommand(String commandName, UserContext user, List<CommandHandler> handlers) {
        // 用户可能打成 "/help /search" 或 "/help Search"，这里统一清理
        String normalized = commandName.startsWith("/")
                ? commandName.substring(1)
                : commandName;

        CommandHandler found = handlers.stream()
                .filter(handler -> handler.getName().equalsIgnoreCase(normalized)
                        || handler.getAliases().stream().anyMatch(a -> a.equalsIgnoreCase(normalized)))
                .findFirst()
                .orElse(null);

        if (found == null) {
            return "没有这个命令：" + normalized;
        }

        // 管理员命令和标记为隐藏的命令，对普通用户一律回答"没有这个命令" ——
        // 和上面的列表过滤保持一致。列表里看不到、单独查也说没有，
        // 这样才不会出现"列表里没有，但手打却能查出来"这种自相矛盾
        if (found.isHiddenFromHelp() || (found.isAdminOnly() && !user.isAdmin())) {
            return "没有这个命令：" + normalized;
        }

        StringBuilder sb = new StringBuilder();
        sb.append('/').append(found.getName()).append('\n');
        sb.append("说明：").append(found.getDescription()).append('\n');
        sb.append("用法：").append(found.getUsage());

        if (!found.getAliases().isEmpty()) {
            sb.append("\n别名：/").append(String.join("、/", found.getAliases()));
        }

        return sb.toString();
    }
}
