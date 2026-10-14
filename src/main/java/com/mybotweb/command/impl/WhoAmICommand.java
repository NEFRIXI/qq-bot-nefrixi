package com.mybotweb.command.impl;

import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.command.CommandHandler;
import com.mybotweb.command.CommandNames;
import com.mybotweb.common.util.ProjectPaths;
import com.mybotweb.security.AdminWhitelist;
import com.mybotweb.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code /whoami} —— 告诉你"我在这个机器人眼里是谁"。<b>所有人可用。</b>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>★ 这条命令是为了解一个"鸡生蛋"问题而存在的</b>
 *
 * <p>权限系统的设计是：把管理员的标识配进白名单 → 成为管理员。但这里有个死结：
 * <pre>
 *   要知道自己的标识，得问机器人
 *   → 但能问机器人的命令是管理员命令
 *   → 但你得先是管理员
 *   → 但你得先知道自己的标识……
 * </pre>
 * 第一次部署的人一定会卡在这里。所以必须有一个<b>人人可用、
 * 且专门回答这个问题</b>的命令。它不泄露任何别人的信息——
 * 只把"你自己是谁"告诉你，而这个信息本来就是你自己的。
 *
 * <p><b>关于别名：为什么有这么多？</b>
 * 最初的别名只有 {@code me} 和 {@code id}，结果第一次用就撞上了：
 * 有人在 QQ 里打了 {@code /whomi}（漏了个字母），被回"没有这个命令"，
 * 然后就卡住了——拿不到标识就没法配白名单。
 * <b>一个用来救急的命令，不该让人因为少打一个字母就用不上。</b>
 * 所以现在把常见拼法都收进来了：{@code whoami}、{@code whomi}（漏字母）、
 * {@code who}（简写）、{@code bot-me}（另一种直觉叫法）、{@code myid}、{@code 我是谁}。
 * 另外 {@code CommandRouter} 里也加了拼写建议，打错时它会问
 * "你是不是想输入 /whoami？"
 *
 * <p><b>为什么标识不是 QQ 号？</b> 腾讯给的是 openid，是它按
 * "机器人 + 用户"这个组合生成的一串匿名 ID。同一个用户在不同机器人下
 * 拿到的 openid 完全不同——这是腾讯的隐私设计。
 * 所以别指望从这里看到自己的 QQ 号，也<b>不能用 QQ 号配白名单</b>。
 *
 * @author mybotweb
 */
@Component
@RequiredArgsConstructor
public class WhoAmICommand implements CommandHandler {

    /** 管理员白名单，用来显示当前名单人数 */
    private final AdminWhitelist adminWhitelist;

    @Override
    public String getName() {
        return CommandNames.WHO_AM_I;
    }

    @Override
    public List<String> getAliases() {
        // 别名清单也搬进了 CommandNames —— 在那里能一眼看出
        // "这个名字有没有被别的命令用掉"（撞车会在启动时报错）
        return CommandNames.WHO_AM_I_ALIASES;
    }

    @Override
    public String getDescription() {
        return "查看你自己的用户标识";
    }

    @Override
    public String getUsage() {
        return CommandNames.withSlash(CommandNames.WHO_AM_I);
    }

    /**
     * 注意这里<b>没有</b>重写 {@code isAdminOnly()}——用的就是接口默认的
     * {@code false}，也就是所有人可用。这是本类存在的全部意义，
     * 千万不要为了"减少暴露"给它加上管理员限制。
     */

    @Override
    public String execute(QqMessage message, UserContext user, String[] args) {
        String userId = user.getUserId();

        StringBuilder sb = new StringBuilder();

        // 标识单独占一行、前后留空行，方便在手机上长按选中复制
        sb.append("你的用户标识（openid）：\n\n");
        sb.append(userId).append("\n\n");
        sb.append("身份：").append(user.isAdmin() ? "管理员" : "普通用户").append('\n');
        sb.append("当前管理员名单：").append(adminWhitelist.size()).append(" 人\n");

        if (user.isAdmin()) {
            sb.append("\n你是管理员，/help 里带【管理员】的命令都能用。");
            return sb.toString();
        }

        // 普通用户：给出完整的操作步骤 + 绝对路径。
        // 路径用 System.getProperty("user.dir") 动态取，也就是"应用启动时所在的目录"，
        // 这里正好就是项目根目录。硬编码 D:\ds-work\... 换台机器就错了
        String configPath = ProjectPaths.localConfigFile();

        sb.append('\n');
        sb.append("想把自己设成管理员，按这四步做：\n\n");
        sb.append("1. 复制上面那串标识\n\n");
        sb.append("2. 打开这个文件（用记事本或 IDEA 都行）：\n");
        sb.append("   ").append(configPath).append("\n\n");
        sb.append("3. 找到 app.admin.user-ids，在下面加一行（注意前面的短横线和缩进）：\n");
        sb.append("       - \"").append(userId).append("\"\n\n");
        sb.append("4. 重启应用，然后发 /status 验证\n");

        sb.append('\n');
        sb.append("说明：这串标识不是你的 QQ 号。腾讯按「机器人+用户」生成，");
        sb.append("换个机器人就完全不同，所以白名单里不能填 QQ 号。");

        return sb.toString();
    }
}
