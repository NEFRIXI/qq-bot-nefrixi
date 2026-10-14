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
 * {@code /help_admin} —— 管理员视角的完整命令列表。仅管理员可用。
 *
 * <p>用法：
 * <pre>
 *   /help_admin            → 列出全部命令（含管理员命令）
 *   /help_admin 命令名      → 看某条命令的详情（同 /help 命令名）
 * </pre>
 *
 * <p><b>为什么要和 {@code /help} 分开？</b>
 *
 * <p>两条命令面向的是完全不同的人：
 * <ul>
 *   <li>{@code /help} 给普通用户看 —— 只列他能用的。
 *       列一堆自己用不了的命令只是噪音，而且会让人反复试探</li>
 *   <li>{@code /help_admin} 给管理员看 —— 列全部，包括只有他能用的。
 *       管理员需要的是"系统里一共有哪些能力"的全貌</li>
 * </ul>
 *
 * <p>中间有一段时间是把两者合在一起的（普通用户也能看到管理员命令，
 * 只是标上【管理员】）。那个方案的问题是：普通用户看到自己用不了的东西
 * 只会产生疑问，而真正需要全貌的管理员反而被混在一堆普通命令里。
 * 分开之后各看各的，都干净。
 *
 * <p><b>关于循环依赖</b>：本类注入 {@link CommandRouter}，
 * 而 {@code CommandRouter} 的构造器需要 {@code List<CommandHandler>}（包含本类），
 * 所以必须加 {@code @Lazy} 打断这个环——和 {@code HelpCommand} 是同样的处理，
 * 原因见那个类的注释。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class HelpAdminCommand implements CommandHandler {

    /**
     * 命令路由，用来拿全部命令。
     *
     * <p>{@code @Lazy} 是必须的，否则会和 {@code CommandRouter} 形成循环依赖
     * 导致启动失败。
     */
    private final CommandRouter commandRouter;

    public HelpAdminCommand(@Lazy CommandRouter commandRouter) {
        this.commandRouter = commandRouter;
    }

    @Override
    public String getName() {
        return CommandNames.HELP_ADMIN;
    }

    @Override
    public List<String> getAliases() {
        return CommandNames.HELP_ADMIN_ALIASES;
    }

    @Override
    public String getDescription() {
        return "查看全部命令（含管理员命令）";
    }

    @Override
    public String getUsage() {
        return CommandNames.withSlash(CommandNames.HELP_ADMIN) + " [命令名]";
    }

    /** 仅管理员 —— 它存在的意义就是把管理员命令列出来 */
    @Override
    public boolean isAdminOnly() {
        return true;
    }

    @Override
    public String execute(QqMessage message, UserContext user, String[] args) {
        List<CommandHandler> all = commandRouter.getAllHandlers();

        // ---- 带参数：看某条命令详情（含管理员命令） ----
        if (args.length > 0) {
            return describeCommand(args[0], all);
        }

        // ---- 不带参数：分两组列出全部命令 ----
        List<CommandHandler> normalCommands = all.stream()
                .filter(handler -> !handler.isAdminOnly())
                .sorted(Comparator.comparing(CommandHandler::getName))
                .toList();

        List<CommandHandler> adminCommands = all.stream()
                .filter(CommandHandler::isAdminOnly)
                .sorted(Comparator.comparing(CommandHandler::getName))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("全部命令（共 ").append(all.size()).append(" 个）\n");

        sb.append("\n【所有人可用】\n");
        appendCommandLines(sb, normalCommands);

        sb.append("\n【仅管理员】\n");
        appendCommandLines(sb, adminCommands);

        sb.append("\n发 " + CommandNames.withSlash(CommandNames.HELP_ADMIN)
                + " 命令名 可以看某条命令的详细说明。");
        sb.append("\n（普通用户在 " + CommandNames.withSlash(CommandNames.HELP)
                + " 里只能看到第一组）");

        return sb.toString();
    }

    /**
     * 把一批命令按统一格式追加到输出里。
     *
     * @param sb       输出缓冲
     * @param commands 要列出的命令
     */
    private void appendCommandLines(StringBuilder sb, List<CommandHandler> commands) {
        if (commands.isEmpty()) {
            sb.append("  （无）\n");
            return;
        }
        for (CommandHandler handler : commands) {
            sb.append("  ").append(handler.getUsage())
                    .append(" —— ").append(handler.getDescription())
                    .append('\n');
        }
    }

    /**
     * 输出单条命令的详细说明，<b>不过滤权限</b>。
     *
     * <p>这是本命令和 {@code /help 命令名} 的关键区别：
     * {@code /help} 对管理员命令回"没有这个命令"，
     * 而本命令如实展示——因为调用它的本来就是管理员。
     *
     * @param commandName 用户输入的命令名（可能带斜杠、大小写混杂）
     * @param all         全部命令
     * @return 详情文本
     */
    private String describeCommand(String commandName, List<CommandHandler> all) {
        String normalized = commandName.startsWith("/")
                ? commandName.substring(1)
                : commandName;

        CommandHandler found = all.stream()
                .filter(handler -> handler.getName().equalsIgnoreCase(normalized)
                        || handler.getAliases().stream()
                                .anyMatch(a -> a.equalsIgnoreCase(normalized)))
                .findFirst()
                .orElse(null);

        if (found == null) {
            return "没有这个命令：" + normalized;
        }

        StringBuilder sb = new StringBuilder();
        sb.append('/').append(found.getName()).append('\n');
        sb.append("说明：").append(found.getDescription()).append('\n');
        sb.append("用法：").append(found.getUsage());
        sb.append("\n权限：").append(found.isAdminOnly() ? "仅管理员" : "所有人");

        if (!found.getAliases().isEmpty()) {
            sb.append("\n别名：/").append(String.join("、/", found.getAliases()));
        }

        return sb.toString();
    }
}
