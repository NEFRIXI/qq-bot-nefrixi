package com.mybotweb.blog.auth;

import com.mybotweb.common.exception.BusinessException;
import com.mybotweb.common.result.ResultCode;

import java.util.function.Supplier;

/**
 * 当前登录管理员的线程上下文。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>⚠ 用这个类之前，必须知道的一件事：ThreadLocal 必须清理</b>
 *
 * <p>Tomcat 处理请求用的是<b>线程池</b>，线程是反复复用的。
 * ThreadLocal 的值挂在线程上，不主动删就一直在。
 * 忘了清理会有两个后果，第二个是事故：
 * <ol>
 *   <li><b>内存泄漏</b>：每次登录留下的 {@link AdminPrincipal} 跟着线程一直活着，
 *       线程池有多少线程就残留多少个</li>
 *   <li><b>用户串号</b>：上一个请求（管理员 A）设的值没删，
 *       下一个请求由同一个线程处理时读到了它 ——
 *       于是未登录的人拿到了管理员身份</li>
 * </ol>
 * 第二条不是"可能有点问题"，是<b>越权</b>。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>这个类怎么保证清理</b>
 *
 * <p>正常请求走 {@code AdminAuthInterceptor}：
 * {@code preHandle} 里 {@code set}，{@code afterCompletion} 里 {@code clear}。
 * Spring MVC 保证 {@code afterCompletion} 一定会执行 —— 就算 Controller 抛异常，
 * 它的地位等同于 try/finally 里的 finally。
 *
 * <p>要在非 Web 线程里以某个身份执行代码（以后写定时任务时会遇到），
 * 用 {@link #runAs} —— 它内部就是 try/finally，把清理这件事写死了，
 * 不依赖调用方记得。
 *
 * @author mybotweb
 */
public final class AdminContextHolder {

    /**
     * 真正存值的地方。
     *
     * <p>{@code static final}：每个线程一份，JVM 里只有这一个入口。
     * 私有：外部只能通过下面的方法访问，这样"什么时候 set、什么时候 clear"
     * 就完全由本类控制，不会有人在别处 set 完忘了清。
     */
    private static final ThreadLocal<AdminPrincipal> HOLDER = new ThreadLocal<>();

    /** 工具类，不允许实例化 */
    private AdminContextHolder() {
    }

    /**
     * 把当前登录管理员绑定到本线程。
     *
     * <p><b>只应该由拦截器调用。</b> 别处调用会因为没人负责清理而留下串号隐患。
     *
     * @param principal 登录管理员
     */
    public static void set(AdminPrincipal principal) {
        HOLDER.set(principal);
    }

    /**
     * 取当前登录管理员，没登录时返回 {@code null}。
     *
     * <p>多数场景应该用 {@link #require()}：能走到需要身份的那一步，
     * 就说明本来就该已经登录了，返回 null 只会把问题推到后面变成空指针。
     *
     * @return 登录管理员，或 {@code null}
     */
    public static AdminPrincipal get() {
        return HOLDER.get();
    }

    /**
     * 取当前登录管理员，没登录直接抛 401。
     *
     * @return 登录管理员，一定不为 {@code null}
     * @throws BusinessException 当前线程没有登录上下文时
     */
    public static AdminPrincipal require() {
        AdminPrincipal principal = HOLDER.get();
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return principal;
    }

    /**
     * 清掉本线程的登录上下文。
     *
     * <p><b>用 {@code remove()} 而不是 {@code set(null)}</b>：
     * {@code set(null)} 只是把值设成 null，那个 ThreadLocalMap 里的
     * Entry 还挂在线程上；{@code remove()} 才是真正摘掉它。
     * 内存泄漏的差别就在这一步。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 以指定身份执行一段代码，执行完<b>一定</b>清理。
     *
     * <p>这是本类里唯一带 try/finally 的地方，也是最该复用的写法：
     * 清理动作写在 finally 里，不管业务代码是正常返回、抛异常还是提前 return，
     * 都会走到 —— 调用方不需要记得做任何事。
     *
     * <p><b>为什么要保存并恢复旧值，而不是直接清空</b>
     * 支持嵌套调用。如果外层已经是"管理员 A"，内层又用 {@code runAs} 切到 B，
     * 内层结束时应该回到 A，而不是变成"没登录"。
     * 直接 clear 在嵌套场景下会把外层的身份弄丢。
     *
     * @param principal 要切换成的身份
     * @param action    要执行的代码
     * @param <T>       返回值类型
     * @return {@code action} 的返回值
     */
    public static <T> T runAs(AdminPrincipal principal, Supplier<T> action) {
        AdminPrincipal previous = HOLDER.get();
        HOLDER.set(principal);
        try {
            return action.get();
        } finally {
            // 这一行是整段代码的重点：不管上面发生什么，它都会执行
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    /**
     * {@link #runAs} 的无返回值版本。
     *
     * <p>单独写一个是为了让调用处读起来更自然：
     * {@code runAs(principal, () -> doSomething())} 比
     * {@code runAs(principal, () -> { doSomething(); return null; })} 干净。
     *
     * @param principal 要切换成的身份
     * @param action    要执行的代码
     */
    public static void runAs(AdminPrincipal principal, Runnable action) {
        runAs(principal, () -> {
            action.run();
            return null;
        });
    }
}
