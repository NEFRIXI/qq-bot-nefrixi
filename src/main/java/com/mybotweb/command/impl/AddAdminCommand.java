package com.mybotweb.command.impl;

import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.command.CommandHandler;
import com.mybotweb.command.CommandNames;
import com.mybotweb.security.AdminClaimService;
import com.mybotweb.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code /add_admin} —— 向管理员申请管理员权限。<b>所有人可用。</b>
 *
 * <p>用法：
 * <pre>
 *   /add_admin            → 发起申请，机器人把申请连同验证码推给所有管理员
 *   /add_admin 123456     → 拿到管理员给的验证码后，用它完成认领
 * </pre>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>和 {@code /admin} 的分工，别搞混</b>
 *
 * <table border="1">
 *   <tr><th>命令</th><th>谁的活</th><th>干什么</th></tr>
 *   <tr>
 *     <td>{@code /admin}</td>
 *     <td><b>管理员</b>（名单为空时人人可用，用于初始化）</td>
 *     <td>加人、删人、看名单</td>
 *   </tr>
 *   <tr>
 *     <td>{@code /add_admin}</td>
 *     <td><b>普通用户</b></td>
 *     <td>申请，然后等管理员批准</td>
 *   </tr>
 * </table>
 *
 * <p>简单记：<b>{@code /admin} 是管理员在发号施令，{@code /add_admin} 是普通人递申请书。</b>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>★ 这条命令改过两次名字和形态，值得说一下</b>
 *
 * <p><b>第一版</b>：{@code /claim 口令}，口令打印在服务器控制台。
 * 问题是<b>申请人根本碰不到服务器</b>——他不可能跑去机房看日志。
 * 结果他只能来找管理员，管理员再去翻日志、再把口令转给他。
 * 绕一圈，决定权还是管理员手上，却凭空多了一堆无效动作。
 *
 * <p><b>第二版</b>：无参数就是<b>申请</b>，机器人把验证码推给管理员。
 * 这个流程是对的，但名字还叫 {@code /claim}（认领）——
 * 那是实现者视角的词，对用户来说这件事就是"申请"。
 *
 * <p><b>第三版（现在）</b>：改名叫 {@code /add_admin}。
 * 虽然严格说它做的是"申请"而不是"添加"，但这个名字和
 * 管理员用的 {@code /admin} 形成了一组对称的词汇，用户看到
 * {@code /admin} 和 {@code /add_admin} 能立刻猜出两者有关系。
 * 命名的一致性有时候比字面准确性更重要。
 *
 * <p><b>顺带修掉一个 bug</b>：早期版本在"没带参数"时无条件提示
 * "口令在控制台日志里"。但自动认领开启时根本不会生成口令，
 * 于是第二个来申请的人被告知去一个不存在的地方找一个不存在的东西。
 * 现在无参数分支会先判断当前处于哪种状态，再给出对应提示。
 *
 * <p><b>为什么本命令不能设成仅管理员？</b>
 * 因为它存在的意义就是"从普通用户变成管理员"。
 * 如果它自己是管理员命令，就又是一个死结——和 {@code /whoami}
 * 犯过的错一样：<b>用来解死锁的命令，绝对不能自己也上锁。</b>
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AddAdminCommand implements CommandHandler {

    /** 认领服务：申请、验证码校验、控制台口令校验都在它里面 */
    private final AdminClaimService claimService;

    @Override
    public String getName() {
        return CommandNames.ADD_ADMIN;
    }

    @Override
    public List<String> getAliases() {
        return CommandNames.ADD_ADMIN_ALIASES;
    }

    @Override
    public String getDescription() {
        return "申请成为管理员";
    }

    @Override
    public String getUsage() {
        return CommandNames.withSlash(CommandNames.ADD_ADMIN) + " [验证码]";
    }

    @Override
    public String execute(QqMessage message, UserContext user, String[] args) {
        String userId = user.getUserId();

        // 已经是管理员了，没什么可申请的
        if (user.isAdmin()) {
            return "你已经是管理员了，不需要申请。\n发 "
                    + CommandNames.withSlash(CommandNames.HELP) + " 看有哪些命令可以用。";
        }

        // ---- 不带参数：发起申请 ----
        if (args.length == 0) {
            return describeRequestResult(claimService.requestClaim(userId));
        }

        // ---- 带参数：提交管理员给你的验证码 ----
        return describeCodeResult(claimService.submitCode(userId, args[0]));
    }

    /**
     * 把"发起申请"的结果翻译成给用户看的话。
     *
     * @param result 服务返回的结果
     * @return 提示文本
     */
    private String describeRequestResult(AdminClaimService.ClaimResult result) {
        return switch (result) {
            case REQUESTED -> """
                    已把你的申请转给管理员了。
                    
                    管理员会收到一条通知，里面有 6 位数字验证码。
                    他把验证码给你之后，你发送：
                    
                        /add_admin 验证码
                    
                    验证码 10 分钟内有效，用过一次就失效。
                    
                    （一直没收到？问一下管理员是不是没看到通知）""";

            case TOO_SOON -> """
                    你刚申请过，请稍等一下再试。
                    
                    申请有 1 分钟冷却时间，避免反复申请打扰管理员。""";

            case ALREADY_ADMIN -> "你已经是管理员了，不需要申请。";

            case NO_ADMIN_TO_APPROVE -> """
                    现在还没有任何管理员，没人能批准你的申请。
                    
                    请让机器人主人先发送 %s 把自己设为第一个管理员。""".formatted(
                    CommandNames.withSlash(CommandNames.ADMIN));

            case INVALID_USER -> "没法识别你的用户标识，申请失败。请稍后再试。";

            default -> "申请没能提交（" + result.name() + "）。请稍后再试。";
        };
    }

    /**
     * 把"提交验证码"的结果翻译成给用户看的话。
     *
     * @param result 服务返回的结果
     * @return 提示文本
     */
    private String describeCodeResult(AdminClaimService.ClaimResult result) {
        return switch (result) {
            case SUCCESS -> """
                    申请通过，你已经是管理员了。
                    
                    可以试试：
                      /status   查看系统状态
                      /model    查看和切换本地模型
                      /admin    管理其他管理员
                      /help     看全部命令""";

            case NO_PENDING_REQUEST -> """
                    你还没有提交过申请，或者之前那份已经用掉、作废了。
                    
                    先发送 %s（不带任何参数）来发起申请。""".formatted(
                    CommandNames.withSlash(CommandNames.ADD_ADMIN));

            case WRONG_CODE -> """
                    验证码不正确。
                    
                    请确认管理员给你的那 6 位数字没有抄错。
                    一份申请最多可以试 3 次，超过就要重新申请了。""";

            case CODE_EXPIRED -> """
                    这份申请已经过期了（验证码有效期 10 分钟）。
                    
                    重新发送 %s 可以再申请一次。""".formatted(
                    CommandNames.withSlash(CommandNames.ADD_ADMIN));

            case TOO_MANY_CODE_FAILURES -> """
                    验证码输错次数过多，这份申请已作废。
                    
                    重新发送 %s 可以再申请一次。""".formatted(
                    CommandNames.withSlash(CommandNames.ADD_ADMIN));

            default -> "认领没能完成（" + result.name() + "）。请稍后再试。";
        };
    }
}
