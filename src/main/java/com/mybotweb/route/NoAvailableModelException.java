package com.mybotweb.route;

/**
 * 算力不可用异常 —— 没有任何一个客户端能满足当前策略时抛出。
 *
 * <p>它只在一种情况下出现：策略是 {@code LOCAL_ONLY}（坚决不花钱、
 * 只用本地），而此时本地节点又离线。这时<b>不能降级、也没有可用的算力</b>，
 * 只能报错。
 *
 * <p><b>为什么不直接回一句"稍后再试"了事？</b> 因为这两件事对用户的意义不同：
 * <ul>
 *   <li>"本地模型没跑起来" —— 用户重启一下本地服务就能好，
 *       或者管理员发 {@code /setlocal} 拉起</li>
 *   <li>"当前策略不允许降级" —— 这是配置问题，用户自己做什么都没用，
 *       得管理员改策略</li>
 * </ul>
 * 用单独的异常把后者区分出来，{@code MessageDispatcher} 才能给出
 * 指向正确方向的提示，而不是笼统地道歉。
 *
 * @author mybotweb
 */
public class NoAvailableModelException extends RuntimeException {

    /**
     * @param message 给用户看的原因说明（会被写进回复文案，别写堆栈术语）
     */
    public NoAvailableModelException(String message) {
        super(message);
    }
}
