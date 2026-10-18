package com.mybotweb.blog.auth;

import com.mybotweb.common.exception.BusinessException;
import com.mybotweb.common.result.ResultCode;

/**
 * 当前登录普通用户的线程上下文。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>⚠ ThreadLocal 必须清理，理由和 {@link AdminContextHolder} 完全一样</b>
 *
 * <p>Tomcat 复用线程，不清理会导致上一个请求的用户身份残留给下一个请求 ——
 * 那是越权，不是"小概率问题"。清理动作固定在
 * {@code UserAuthInterceptor.afterCompletion} 里，那里一定会执行，
 * 等同于 try/finally 的 finally。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么不做成"一个 Holder 装两种身份"</b>
 *
 * <p>合并成一个 {@code ObjectHolder} 看似省事，但调用方每次都要
 * {@code if (principal instanceof UserPrincipal)} 判断，判断错了就是类型转换异常
 * 或者更糟 —— 把管理员当普通用户、把普通用户当管理员。
 * 两个独立的 Holder，各自只返回确定的类型，"拿错身份"在编译期就不可能。
 *
 * @author mybotweb
 */
public final class UserContextHolder {

    /** 每个线程一份的当前用户 */
    private static final ThreadLocal<UserPrincipal> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    /**
     * 绑定当前用户。只应该由拦截器调用。
     *
     * @param principal 登录用户
     */
    public static void set(UserPrincipal principal) {
        HOLDER.set(principal);
    }

    /**
     * 取当前用户，没登录返回 {@code null}。
     *
     * <p>需要"可匿名"的场景（比如文章详情页要显示"我点过赞没有"）用这个；
     * 要求必须登录的地方用 {@link #require()}。
     *
     * @return 登录用户，或 {@code null}
     */
    public static UserPrincipal get() {
        return HOLDER.get();
    }

    /**
     * 取当前用户，没登录直接抛 401。
     *
     * @return 登录用户，一定不为 {@code null}
     * @throws BusinessException 没有登录上下文时
     */
    public static UserPrincipal require() {
        UserPrincipal principal = HOLDER.get();
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return principal;
    }

    /**
     * 清掉本线程的用户上下文。
     *
     * <p>用 {@code remove()} 而不是 {@code set(null)} ——
     * 后者只是改值，Entry 还挂在线程上，内存泄漏的差别就在这一步。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
