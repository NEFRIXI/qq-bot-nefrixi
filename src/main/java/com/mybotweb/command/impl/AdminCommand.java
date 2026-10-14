package com.mybotweb.command.impl;

import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.command.CommandHandler;
import com.mybotweb.command.CommandNames;
import com.mybotweb.security.AdminWhitelist;
import com.mybotweb.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * {@code /admin} —— 管理员管理：初始化、增删管理员、查看名单。
 *
 * <p>用法：
 * <pre>
 *   /admin                    → 名单为空时：把自己设为管理员（初始化）
 *                               名单非空时：显示名单和用法
 *   /admin add 用户标识        → 添加一个管理员
 *   /admin remove 用户标识     → 移除一个管理员
 *   /admin list               → 只看名单
 * </pre>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>★ 一条必须说清楚的约束：自举只在"名单为空"时有效</b>
 *
 * <p>本命令有一个特殊行为：<b>管理员名单为空时，任何人发 {@code /admin}
 * 都会把自己设为管理员。</b> 这是为了给第一次部署一条出路——
 * 否则一个管理员都没有，谁也没权限添加第一个管理员。
 *
 * <p><b>这个行为安全的前提是"只有机器人主人能给它发消息"。</b>
 * 在沙箱阶段这是成立的：只有你在沙箱配置里加进去的成员才能用。
 * 但<b>机器人一旦公开，任何 QQ 用户都能私聊它</b>——
 * 那时如果名单恰好是空的，第一个发 {@code /admin} 的陌生人就成了管理员。
 *
 * <p>所以有两条保险：
 * <ol>
 *   <li>自举只在名单<b>完全为空</b>时触发，一旦名单里有人就永久失效</li>
 *   <li><b>不允许移除最后一个管理员</b>（见 {@link #removeAdmin}）——
 *       否则管理员把自己删掉之后名单又空了，自举窗口会重新打开</li>
 * </ol>
 *
 * <p>上线之前，请确认名单里至少有你自己的标识。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么 {@code isAdminOnly()} 返回 false？</b>
 *
 * <p>因为它要支持自举——如果标成"仅管理员"，名单为空时就没人能用了，
 * 又是一个鸡生蛋。权限判断改在 {@link #execute} 里手动做：
 * 名单为空时放行自举，名单非空时必须已是管理员。
 *
 * <p>这是本命令和其他管理员命令（{@code /status}、{@code /model}）的区别，
 * 那两条走的是框架统一的权限拦截。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminCommand implements CommandHandler {

    /** 管理员白名单 */
    private final AdminWhitelist adminWhitelist;

    @Override
    public String getName() {
        return CommandNames.ADMIN;
    }

    @Override
    public List<String> getAliases() {
        return CommandNames.ADMIN_ALIASES;
    }

    @Override
    public String getDescription() {
        return "管理员管理（名单为空时可初始化）";
    }

    @Override
    public String getUsage() {
        return CommandNames.withSlash(CommandNames.ADMIN) + " [add|remove|list] [用户标识]";
    }

    /**
     * 刻意返回 {@code false}，理由见类注释：自举需要它在名单为空时人人可用。
     * 真正的权限判断在 {@link #execute} 里。
     *
     * @return 恒为 {@code false}
     */
    @Override
    public boolean isAdminOnly() {
        return false;
    }

    /**
     * 从普通用户的 {@code /help} 里隐藏。
     *
     * <p>理由：这条命令的权限是<b>动态</b>的——名单为空时人人可用（自举），
     * 名单非空时只有管理员能用。所以 {@link #isAdminOnly()} 必须是
     * {@code false}（否则自举就废了），但那会让它出现在所有人的
     * {@code /help} 里。
     *
     * <p>而普通用户看到"管理员管理（名单为空时可初始化）"这种条目
     * 只会困惑——他既不该管管理员，也看不懂"名单为空"是什么意思。
     *
     * <p>所以执行层面照旧（名单为空时他确实能用），只是不列出来。
     * 需要它的人（管理员）会在 {@code /help_admin} 里看到。
     *
     * @return 恒为 {@code true}
     */
    @Override
    public boolean isHiddenFromHelp() {
        return true;
    }

    @Override
    public String execute(QqMessage message, UserContext user, String[] args) {
        String userId = user.getUserId();

        // ============ 情况一：名单为空 —— 走自举 ============
        if (adminWhitelist.size() == 0) {
            return bootstrap(userId, args);
        }

        // ============ 情况二：名单非空 —— 必须是管理员 ============
        if (!user.isAdmin()) {
            // 提示语要考虑到一种真实处境：名单里确实"有人"，但那个人不是他，
            // 而且可能是个再也不会出现的标识（比如开发期留下的测试数据）。
            // 那种情况下 /add_admin 申请也没人能批准，人就彻底卡死了 ——
            // 所以必须给出"手动清空名单"这条最后的出路。
            return """
                    这个命令只有管理员可以使用。
                    
                    想成为管理员？发送 %s 向现有管理员申请。
                    
                    （名单里现在有 %d 人。如果这些标识你一个都不认识，
                     说明名单可能被脏数据占住了——常见于开发期用测试实例
                     往同一个文件里写过东西。
                     那种情况下申请也没人能批准，只能手动清空：
                       %s
                     把它清空后重启应用，再发 %s 就能重新初始化。）""".formatted(
                    CommandNames.withSlash(CommandNames.ADD_ADMIN),
                    CommandNames.withSlash(CommandNames.ADMIN),
                    adminWhitelist.size(), adminWhitelist.getStorageFilePath());
        }

        // ---- 分发子命令 ----
        if (args.length == 0 || "list".equalsIgnoreCase(args[0])) {
            return describeAdminList();
        }

        String subCommand = args[0].toLowerCase();
        return switch (subCommand) {
            case "add" -> addAdmin(args);
            case "remove", "del", "delete" -> removeAdmin(args, userId);
            default -> """
                    没有这个子命令：%s
                    
                    用法：
                      %s              看管理员名单
                      %s add 用户标识   添加管理员
                      %s remove 用户标识 移除管理员
                    
                    （用户标识让本人发 %s 获取）""".formatted(
                    args[0],
                    CommandNames.withSlash(CommandNames.ADMIN),
                    CommandNames.withSlash(CommandNames.ADMIN),
                    CommandNames.withSlash(CommandNames.ADMIN),
                    CommandNames.withSlash(CommandNames.WHO_AM_I));
        };
    }

    /**
     * 名单为空时的初始化。
     *
     * <p>默认把<b>发起命令的人自己</b>设为管理员。也支持
     * {@code /admin add 某个标识} 指定别人，但要提醒一句：
     * 那时你还没法验证那个标识是谁的。
     *
     * @param userId 发起命令的人
     * @param args   命令参数
     * @return 提示文本
     */
    private String bootstrap(String userId, String[] args) {
        if (args.length >= 2 && "add".equalsIgnoreCase(args[0])) {
            String target = args[1].strip();
            adminWhitelist.addAdmin(target, "通过 /admin 初始化指定");
            log.warn("管理员初始化：名单为空，{} 手动指定了 {}", mask(userId), mask(target));
            return """
                    已把下面这个标识设为管理员：
                    %s
                    
                    【提醒】现在还没有任何管理员，所以没法制止"指定一个错误的标识"。
                    如果那个标识打错了，你可能会把自己锁在外面——
                    那种情况下只能去 data/admin-ids.txt 手动改。
                    
                    更稳妥的做法是让本人发 %s，把标识复制过来。""".formatted(
                    target, CommandNames.withSlash(CommandNames.WHO_AM_I));
        }

        adminWhitelist.addAdmin(userId, "通过 /admin 初始化（名单为空）");

        log.warn("""
                
                ================================================================
                管理员初始化完成
                ----------------------------------------------------------------
                用户标识：{}
                方式    ：名单为空时发送 /admin 自举
                
                已写入：{}
                
                【重要】自举窗口现在已经关闭 —— 名单里有人了，
                之后再发 /admin 就必须是管理员才行。
                
                接下来可以：
                  /admin list           看名单
                  /admin add 标识        加别人（让本人发 /whoami 拿标识）
                  /add_admin            别人申请用的命令
                ================================================================""",
                userId, adminWhitelist.getStorageFilePath());

        return """
                已把你设为管理员（之前一个管理员都没有，所以走了初始化）。
                
                这个身份已经存进 data/admin-ids.txt，重启不丢。
                
                接下来：
                  %s    看系统状态
                  %s     看和切换本地模型
                  %s     管理其他管理员
                
                【注意】初始化窗口已经关闭——名单里有你了，
                之后别人发 %s 不会再有这个效果，
                他们得走 %s 申请，由你批准。""".formatted(
                CommandNames.withSlash(CommandNames.STATUS),
                CommandNames.withSlash(CommandNames.MODEL),
                CommandNames.withSlash(CommandNames.ADMIN),
                CommandNames.withSlash(CommandNames.ADMIN),
                CommandNames.withSlash(CommandNames.ADD_ADMIN));
    }

    /**
     * 添加一个管理员。
     *
     * @param args 参数，{@code args[1]} 是目标标识
     * @return 提示文本
     */
    private String addAdmin(String[] args) {
        if (args.length < 2 || args[1].isBlank()) {
            return """
                    用法：%s add 用户标识
                    
                    用户标识让本人发 %s 获取（是一长串字母数字，不是 QQ 号）。""".formatted(
                    CommandNames.withSlash(CommandNames.ADMIN),
                    CommandNames.withSlash(CommandNames.WHO_AM_I));
        }

        String target = args[1].strip();

        if (adminWhitelist.isAdmin(target)) {
            return "这个标识已经是管理员了，不用重复添加。";
        }

        adminWhitelist.addAdmin(target,
                "由管理员通过 " + CommandNames.withSlash(CommandNames.ADMIN) + " add 添加");

        return """
                已添加管理员：
                %s
                
                当前共 %d 人。发 %s list 可以看完整名单。""".formatted(
                target, adminWhitelist.size(), CommandNames.withSlash(CommandNames.ADMIN));
    }

    /**
     * 移除一个管理员。
     *
     * <p><b>不允许移除最后一个管理员</b>，这是刻意设的保险：
     * 名单一旦被清空，自举窗口就重新打开了——那时任何人发
     * {@code /admin} 都能把自己设成管理员。
     *
     * @param args      参数，{@code args[1]} 是目标标识
     * @param operatorId 操作者自己的标识（用于判断"是不是在删自己"）
     * @return 提示文本
     */
    private String removeAdmin(String[] args, String operatorId) {
        if (args.length < 2 || args[1].isBlank()) {
            return """
                    用法：%s remove 用户标识
                    
                    标识可以从 %s list 里复制。""".formatted(
                    CommandNames.withSlash(CommandNames.ADMIN),
                    CommandNames.withSlash(CommandNames.ADMIN));
        }

        String target = args[1].strip();

        if (!adminWhitelist.isAdmin(target)) {
            return "这个标识不在管理员名单里。发 "
                    + CommandNames.withSlash(CommandNames.ADMIN) + " list 看当前名单。";
        }

        // ---- 保险：不允许把名单清空 ----
        if (adminWhitelist.size() <= 1) {
            return """
                    不能移除最后一个管理员。
                    
                    原因：名单一旦清空，"初始化窗口"就会重新打开——
                    那时任何能给机器人发消息的人，发一句 %s 就能把自己设成管理员。
                    
                    如果你确实要换人，请先 %s add 加上新的，再移除旧的。""".formatted(
                    CommandNames.withSlash(CommandNames.ADMIN),
                    CommandNames.withSlash(CommandNames.ADMIN));
        }

        adminWhitelist.removeAdmin(target);

        String selfNote = target.equals(operatorId)
                ? "\n\n（你移除的是自己，现在你的管理员权限已经没有了）"
                : "";

        return """
                已移除管理员：
                %s
                
                当前还剩 %d 人。%s""".formatted(target, adminWhitelist.size(), selfNote);
    }

    /**
     * 展示管理员名单。
     *
     * <p>名单会完整列出标识（不做脱敏），因为管理员本来就需要
     * 看到完整标识才能执行移除操作——看到一半没法用。
     *
     * @return 名单文本
     */
    private String describeAdminList() {
        Set<String> admins = adminWhitelist.getAllAdminIds();

        StringBuilder sb = new StringBuilder();
        sb.append("管理员名单（").append(admins.size()).append(" 人）：\n");

        int index = 1;
        for (String adminId : admins.stream().sorted().toList()) {
            sb.append('\n').append(index++).append(". ").append(adminId);
        }

        sb.append("\n\n用法：");
        sb.append("\n  " + CommandNames.withSlash(CommandNames.ADMIN)
                + " add 用户标识      添加");
        sb.append("\n  " + CommandNames.withSlash(CommandNames.ADMIN)
                + " remove 用户标识   移除");
        sb.append("\n\n用户标识让本人发 "
                + CommandNames.withSlash(CommandNames.WHO_AM_I) + " 获取。");
        sb.append("\n名单存在 data/admin-ids.txt，重启不丢。");

        return sb.toString();
    }

    /**
     * 日志里用的脱敏标识。
     *
     * @param userId 完整标识
     * @return 前 6 位加省略号
     */
    private String mask(String userId) {
        if (userId == null) {
            return "null";
        }
        return userId.length() > 6 ? userId.substring(0, 6) + "…" : userId;
    }
}
