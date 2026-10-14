package com.mybotweb.security;

import com.mybotweb.channel.MessageChannel;
import com.mybotweb.command.CommandNames;
import com.mybotweb.channel.model.QqReply;
import com.mybotweb.channel.model.QqScene;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管理员认领服务 —— 处理"普通用户申请成为管理员"这件事。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>它在整个体系里的位置</b>
 *
 * <p>成为管理员有三条路，本类负责其中第三条：
 * <table border="1">
 *   <tr><th>路径</th><th>谁发起</th><th>实现位置</th></tr>
 *   <tr>
 *     <td><b>初始化自举</b></td>
 *     <td>机器人主人，名单为空时发 {@code /admin}</td>
 *     <td>{@code AdminCommand} + {@link AdminWhitelist#addAdmin}</td>
 *   </tr>
 *   <tr>
 *     <td><b>管理员直接添加</b></td>
 *     <td>管理员发 {@code /admin add 标识}</td>
 *     <td>{@code AdminCommand} + {@link AdminWhitelist#addAdmin}</td>
 *   </tr>
 *   <tr>
 *     <td><b>申请 → 批准</b></td>
 *     <td>普通用户发 {@code /add_admin}</td>
 *     <td><b>本类</b></td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么要有第三条？</b> 前两条都要求"管理员知道申请人的标识"。
 * 但现实里申请人往往不认识管理员，或者管理员懒得去查标识。
 * 申请制把这件事变成：<b>申请人发起 → 管理员收到通知做决定</b>，
 * 双方只需要传递一个 6 位验证码，不用来回对标识。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>完整流程</b>
 * <pre>
 *   普通用户: /add_admin
 *       ↓
 *   本类生成验证码，私聊推给所有管理员
 *       ↓
 *   管理员决定给不给。给，就把验证码转告申请人
 *       ↓
 *   申请人: /add_admin 123456
 *       ↓
 *   成为管理员 ✅
 * </pre>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>几个关键的安全设计</b>
 *
 * <ul>
 *   <li><b>验证码只发给管理员，不给申请人</b> —— 他拿到验证码的前提是
 *       管理员主动给了他，没人能从他这儿把码套出来</li>
 *   <li><b>验证码一次性</b> —— 用掉立刻失效</li>
 *   <li><b>验证码 10 分钟有效</b> —— 压缩泄露窗口</li>
 *   <li><b>申请 60 秒冷却</b> —— 防止有人反复申请把管理员刷烦</li>
 *   <li><b>验证码最多试 3 次</b> —— 6 位数字 100 万种组合，3 次尝试等于零概率</li>
 *   <li><b>纯数字验证码</b> —— 它要在两处 QQ 消息之间被转发，全数字最不容易抄错</li>
 * </ul>
 *
 * <p><b>一个必须知道的限制</b>：机器人给管理员发通知用的是<b>主动消息</b>。
 * 腾讯对主动消息有配额（单聊每天 1000 条/用户，够用），
 * 但<b>用户可以在 QQ 客户端里关闭"允许主动发送"</b>——
 * 管理员关了这个开关，通知就发不出去。
 * 表现是"申请人说申请了，但管理员什么也没收到"，
 * 日志里会有发送失败的记录。去 QQ 机器人的资料卡把开关打开即可。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class AdminClaimService {

    /**
     * 验证码长度。
     *
     * <p>用纯数字而不是字母数字混合：这个码要由管理员从一个地方
     * 抄到另一个地方（两处 QQ 消息之间转发），全数字最不容易抄错。
     */
    private static final int CODE_LENGTH = 6;

    /** 验证码有效期（分钟） */
    private static final int CODE_TTL_MINUTES = 10;

    /** 同一个用户的申请冷却时间（秒） */
    private static final int REQUEST_COOLDOWN_SECONDS = 60;

    /** 一份申请最多允许输错几次验证码 */
    private static final int MAX_CODE_FAILURES = 3;

    /** 管理员白名单 */
    private final AdminWhitelist adminWhitelist;

    /** 消息通道，用来把申请通知推给管理员 */
    private final MessageChannel messageChannel;

    /** 安全随机源。验证码属于安全相关，不能用可预测的 Random */
    private final SecureRandom random = new SecureRandom();

    /**
     * 待批准的申请：申请人标识 → 申请信息。
     *
     * <p>用 {@link ConcurrentHashMap} 是因为可能有多个人同时申请，
     * 每个人的申请互相独立。
     */
    private final Map<String, PendingClaim> pendingClaims = new ConcurrentHashMap<>();

    /** 每个用户上次申请的时间戳，用于冷却判断 */
    private final Map<String, Long> lastRequestAtMillis = new ConcurrentHashMap<>();

    public AdminClaimService(AdminWhitelist adminWhitelist, MessageChannel messageChannel) {
        this.adminWhitelist = adminWhitelist;
        this.messageChannel = messageChannel;
    }

    /**
     * 提交一份管理员申请：生成验证码，私聊通知所有管理员。
     *
     * @param applicantId 申请人的用户标识
     * @return 处理结果
     */
    public ClaimResult requestClaim(String applicantId) {
        if (applicantId == null || applicantId.isBlank()) {
            return ClaimResult.INVALID_USER;
        }
        if (this.adminWhitelist.isAdmin(applicantId)) {
            return ClaimResult.ALREADY_ADMIN;
        }
        if (this.adminWhitelist.size() == 0) {
            // 一个管理员都没有，没人能批准。这种情况正确的做法是
            // 让机器人主人先用 /admin 初始化，而不是在这里生成一份
            // 永远没人处理的申请
            return ClaimResult.NO_ADMIN_TO_APPROVE;
        }

        // ---- 冷却检查：防止反复申请把管理员刷烦 ----
        long now = System.currentTimeMillis();
        Long lastAt = this.lastRequestAtMillis.get(applicantId);
        if (lastAt != null && now - lastAt < REQUEST_COOLDOWN_SECONDS * 1000L) {
            long remainSeconds = (REQUEST_COOLDOWN_SECONDS * 1000L - (now - lastAt)) / 1000;
            log.debug("用户 {} 申请过于频繁，还需等待 {} 秒", maskUserId(applicantId), remainSeconds);
            return ClaimResult.TOO_SOON;
        }

        // ---- 生成验证码并记录申请 ----
        String code = generateNumericCode();
        long expiresAt = now + CODE_TTL_MINUTES * 60_000L;
        this.pendingClaims.put(applicantId, new PendingClaim(code, expiresAt));
        this.lastRequestAtMillis.put(applicantId, now);

        // ---- 通知所有管理员 ----
        notifyAdmins(applicantId, code);

        log.info("已受理管理员申请（申请人 {}），验证码已推送给 {} 位管理员",
                maskUserId(applicantId), this.adminWhitelist.size());

        return ClaimResult.REQUESTED;
    }

    /**
     * 用验证码完成认领。
     *
     * @param applicantId 申请人的用户标识
     * @param code        用户提交的验证码
     * @return 处理结果
     */
    public ClaimResult submitCode(String applicantId, String code) {
        PendingClaim pending = this.pendingClaims.get(applicantId);
        if (pending == null) {
            return ClaimResult.NO_PENDING_REQUEST;
        }

        // 先查过期，再验码：过期作废的申请不该还能用
        if (System.currentTimeMillis() > pending.expiresAtMillis()) {
            this.pendingClaims.remove(applicantId);
            log.info("申请已过期（申请人 {}）", maskUserId(applicantId));
            return ClaimResult.CODE_EXPIRED;
        }

        if (pending.failures().get() >= MAX_CODE_FAILURES) {
            this.pendingClaims.remove(applicantId);
            log.warn("验证码输错次数过多，申请作废（申请人 {}）", maskUserId(applicantId));
            return ClaimResult.TOO_MANY_CODE_FAILURES;
        }

        String input = code == null ? "" : code.strip();
        if (!pending.code().equals(input)) {
            int failures = pending.failures().incrementAndGet();
            log.warn("验证码错误（申请人 {}，第 {}/{} 次）",
                    maskUserId(applicantId), failures, MAX_CODE_FAILURES);
            return ClaimResult.WRONG_CODE;
        }

        // ---- 验证通过 ----
        boolean added = this.adminWhitelist.addAdmin(applicantId, "申请通过（验证码认领）");
        this.pendingClaims.remove(applicantId);

        log.warn("申请通过：{} 已成为管理员（本次新增：{}）", maskUserId(applicantId), added);
        return ClaimResult.SUCCESS;
    }

    /**
     * 把申请通知推给所有管理员。
     *
     * <p>通知里带完整申请人标识和验证码。看起来像在泄露信息，
     * 但收件人本来就是管理员（已有全部权限），没有额外风险；
     * 而带上标识是为了让管理员判断"这个人我认不认识"。
     *
     * @param applicantId 申请人标识
     * @param code        验证码
     */
    private void notifyAdmins(String applicantId, String code) {
        Set<String> admins = this.adminWhitelist.getAllAdminIds();
        if (admins.isEmpty()) {
            log.error("有人申请管理员权限，但名单里没有任何管理员可以通知");
            return;
        }

        String text = String.format("""
                【有人申请管理员权限】
                
                申请人标识：
                %s
                
                如果确认要给他权限，把下面这个验证码转告他，
                让他给机器人发送：
                
                    %s %s
                
                有效期 %d 分钟，用过一次就失效，过期后他要重新申请。
                
                —— 不认识这个人？忽略这条消息就行，什么都不会发生。""",
                applicantId,
                CommandNames.withSlash(CommandNames.ADD_ADMIN), code,
                CODE_TTL_MINUTES);

        for (String adminId : admins) {
            QqReply reply = new QqReply();
            reply.setUserId(adminId);
            reply.setContent(text);
            reply.setScene(QqScene.C2C);
            // 不设 msgId 和 msgSeq —— 这是主动消息（不是对某条消息的回复）。
            // 主动消息受腾讯每日配额和"用户是否允许主动发送"限制，见类注释
            try {
                this.messageChannel.send(reply);
            } catch (Exception e) {
                log.error("向管理员 {} 推送申请通知失败：{}", maskUserId(adminId), e.getMessage());
            }
        }
    }

    /**
     * 生成纯数字验证码。
     *
     * <p>用 {@code nextInt(1000000)} 生成 0~999999，再用前导零补齐到 6 位。
     * 这样每一档数值等概率；如果按位逐个生成数字，逻辑上也行，
     * 但补零的写法更不容易出错。
     *
     * @return 6 位数字验证码，可能以 0 开头
     */
    private String generateNumericCode() {
        int max = (int) Math.pow(10, CODE_LENGTH);
        return String.format("%0" + CODE_LENGTH + "d", this.random.nextInt(max));
    }

    /**
     * 日志里用的脱敏标识。
     *
     * @param userId 完整标识
     * @return 前 6 位加省略号
     */
    private String maskUserId(String userId) {
        if (userId == null) {
            return "null";
        }
        return userId.length() > 6 ? userId.substring(0, 6) + "…" : userId;
    }

    /**
     * 一份待批准的管理员申请。
     *
     * <p>用 record 是因为它就是"一组数据"——只有 {@code failures}
     * 需要可变（每次输错要累加），所以那一项用 {@link AtomicInteger} 包一层。
     *
     * @param code            验证码
     * @param expiresAtMillis 过期时间戳
     * @param failures        已失败次数
     */
    private record PendingClaim(String code, long expiresAtMillis, AtomicInteger failures) {

        /**
         * 构造一份新申请，失败次数从 0 开始。
         *
         * @param code            验证码
         * @param expiresAtMillis 过期时间戳
         */
        PendingClaim(String code, long expiresAtMillis) {
            this(code, expiresAtMillis, new AtomicInteger(0));
        }
    }

    /**
     * 申请的处理结果。
     *
     * <p>做成枚举而不是布尔值，是因为每种情况要跟用户说的话都不一样。
     * 用布尔值就只能回一句笼统的"失败了"，而用户最需要的恰恰是
     * "现在什么情况、我该做什么"。
     */
    @Getter
    public enum ClaimResult {

        /** 申请通过，已经是管理员 */
        SUCCESS("申请通过"),

        /** 申请已提交，等待管理员批准 */
        REQUESTED("申请已提交"),

        /** 当前没有待处理的申请（没申请过，或申请已用掉/作废） */
        NO_PENDING_REQUEST("没有待处理的申请"),

        /** 验证码错误 */
        WRONG_CODE("验证码错误"),

        /** 验证码已过期 */
        CODE_EXPIRED("验证码已过期"),

        /** 验证码输错次数过多，申请已作废 */
        TOO_MANY_CODE_FAILURES("输错次数过多"),

        /** 申请太频繁，被冷却挡住 */
        TOO_SOON("申请太频繁"),

        /** 已经是管理员了 */
        ALREADY_ADMIN("已经是管理员"),

        /** 名单里一个管理员都没有，没人能批准 */
        NO_ADMIN_TO_APPROVE("没有管理员可以批准"),

        /** 用户标识不合法 */
        INVALID_USER("用户标识不合法");

        private final String description;

        ClaimResult(String description) {
            this.description = description;
        }
    }
}
