package com.mybotweb.blog.entity;

/**
 * 普通用户状态常量。
 *
 * <p>和 {@link ArticleStatus} 同一个思路：有含义的值只在一处定义，
 * 别处引用常量，不直接写 0 和 1 —— 否则三个月后没人记得
 * {@code status == 1} 到底是"正常"还是"封禁"。
 *
 * <p><b>三档状态，从轻到重</b>：
 * <ul>
 *   <li>{@link #NORMAL} 正常</li>
 *   <li>{@link #MUTED} 禁言 —— 能登录、能看，但发不了帖也评不了论</li>
 *   <li>{@link #BANNED} 封禁 —— 连登录都不行</li>
 * </ul>
 *
 * <p><b>为什么禁言要单独一档，而不是直接用封禁</b>
 * 大多数违规是"话说得不对"而不是"这个人不该存在"。
 * 直接封禁会连他已有的评论一起看不见，处罚和过错不对等；
 * 禁言只掐掉"再说话"这个动作，历史内容原样保留。
 *
 * <p>三者都是可逆的：改回 {@link #NORMAL} 就恢复，数据不删。
 * 真正的"删除"走软删除（{@code app_user.deleted_at}），
 * 不塞进 status —— 删除语义是"这条记录没了"，
 * 和"人还在，只是暂时不让说话"是两件事，混在一起以后就分不开了。
 *
 * @author mybotweb
 */
public final class AppUserStatus {

    /** 正常：可以登录、发帖、评论 */
    public static final int NORMAL = 0;

    /** 已封禁：登录会被拒绝，已有令牌在下次请求时失效 */
    public static final int BANNED = 1;

    /** 禁言：能登录、能浏览，但发帖和评论都会被拒绝 */
    public static final int MUTED = 2;

    private AppUserStatus() {
    }

    /**
     * 判断是不是合法的状态值。
     *
     * <p>接前端传来的 status 时用它兜底：传个 7 进来要拦住，
     * 否则库里会出现第四个谁都不认识的状态。
     *
     * @param status 待校验的值，可为 {@code null}
     * @return 是 0、1、2 之一时返回 {@code true}
     */
    public static boolean isValid(Integer status) {
        return status != null
                && (status == NORMAL || status == BANNED || status == MUTED);
    }

    /**
     * 能不能发内容（发帖、评论）。
     *
     * <p>正常用户才可以；禁言和封禁都不行。
     *
     * @param status 状态值，可为 {@code null}
     * @return 可以发内容时返回 {@code true}
     */
    public static boolean canPublish(Integer status) {
        return status != null && status == NORMAL;
    }

    /**
     * 翻译成给人看的文字，用于日志和后台列表。
     *
     * @param status 状态值
     * @return "正常" / "已封禁" / "已禁言" / "未知(值)"
     */
    public static String describe(Integer status) {
        if (status == null) {
            return "未知(null)";
        }
        return switch (status) {
            case NORMAL -> "正常";
            case BANNED -> "已封禁";
            case MUTED -> "已禁言";
            default -> "未知(" + status + ")";
        };
    }
}
