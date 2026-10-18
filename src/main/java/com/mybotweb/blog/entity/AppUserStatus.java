package com.mybotweb.blog.entity;

/**
 * 普通用户状态常量。
 *
 * <p>和 {@link ArticleStatus} 同一个思路：有含义的值只在一处定义，
 * 别处引用常量，不直接写 0 和 1 —— 否则三个月后没人记得
 * {@code status == 1} 到底是"正常"还是"封禁"。
 *
 * <p><b>封禁是可逆的</b>：管理员把 status 改回 {@link #NORMAL} 就恢复，
 * 数据不删。所以这里没有"已删除"这个状态 —— 删除语义和封禁语义混在一起，
 * 以后想加"恢复"功能时会发现分不开。
 *
 * @author mybotweb
 */
public final class AppUserStatus {

    /** 正常：可以登录、发帖、评论 */
    public static final int NORMAL = 0;

    /** 已封禁：登录会被拒绝，已有令牌在下次请求时失效 */
    public static final int BANNED = 1;

    private AppUserStatus() {
    }

    /**
     * 判断是不是合法的状态值。
     *
     * <p>接前端传来的 status 时用它兜底：传个 7 进来要拦住，
     * 否则库里会出现第三个谁都不认识的状态。
     *
     * @param status 待校验的值，可为 {@code null}
     * @return 是 0 或 1 时返回 {@code true}
     */
    public static boolean isValid(Integer status) {
        return status != null && (status == NORMAL || status == BANNED);
    }

    /**
     * 翻译成给人看的文字，用于日志和后台列表。
     *
     * @param status 状态值
     * @return "正常" / "已封禁" / "未知(值)"
     */
    public static String describe(Integer status) {
        if (status == null) {
            return "未知(null)";
        }
        return switch (status) {
            case NORMAL -> "正常";
            case BANNED -> "已封禁";
            default -> "未知(" + status + ")";
        };
    }
}
