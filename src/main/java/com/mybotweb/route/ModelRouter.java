package com.mybotweb.route;

import com.mybotweb.llm.ChatModelClient;

/**
 * 算力路由器 —— 回答"这一条消息交给谁算"。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>它是"云端当路由器"这个设计的落点</b>
 *
 * <p>按讨论的方案，云端服务器不只是"另一台算力机器"，它更是一台<b>路由器</b>：
 * QQ 消息进来后，它可以
 * <ul>
 *   <li>自己算（调云端 API）</li>
 *   <li>或者转给<b>本地节点</b>算（本地节点连回云端的那条通道）</li>
 * </ul>
 * 本接口就表达这个选择动作。
 *
 * <p><b>它和 {@code app.role} 的分工，这是最容易搞混的一处</b>：
 * <table border="1">
 *   <tr><th>配置</th><th>回答什么问题</th><th>什么时候定</th></tr>
 *   <tr>
 *     <td>{@code app.role}</td>
 *     <td>这台机器<b>自己</b>用哪套算力</td>
 *     <td>启动时（部署事实：云上没有 Ollama）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code app.route.strategy}</td>
 *     <td>这<b>一条</b>消息交给谁</td>
 *     <td>随时可改（管理员发 {@code /strategy} 命令）</td>
 *   </tr>
 * </table>
 *
 * <p>两者组合出"云端当路由器"的形态：{@code role: cloud}（云端自己会调 API）
 * ＋ {@code strategy: LOCAL_FIRST}（本地在线就派给本地）。本地不在线时
 * 自动降级到云端，机器人照常工作——这就是它比"{@code role} 一刀切"更好的地方。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>本阶段实现到哪一步</b>
 *
 * <p>接口、策略解析、本地算力可用判定、降级逻辑<b>都已实现且可用</b>。
 *
 * <p><b>注意"本地可用"的判据已经改过</b>：现在看的是
 * {@code 本机模型服务能连上 OR 本地节点上报过心跳}（{@code isLocalAvailable()}），
 * 不再只看心跳。所以本机装着 Ollama 时聊天会真的走本地。
 * 这段注释以前写的是"本地永远判定为离线、所有策略都收敛到走云端"，那已经不对了。
 *
 * <p>唯一还缺的是"{@code LOCAL_FIRST} 时怎么把任务真的送到<b>远端</b>本地节点"——
 * 那需要 {@code LocalNodeEndpoint}（{@code /ws/local-agent}）和本机侧的程序，
 * 也就是"云端当路由器"那种形态。本机单机跑并不需要它。
 *
 * <p>将来接上本地节点后，{@link #chatModelClient(java.lang.String)}
 * 在 {@code LOCAL_FIRST} 分支返回的仍然是同一个 {@code ollama} 客户端实例——
 * 区别在于<b>那个实例跑在本地机器上</b>。届时需要改成"把请求经 WebSocket
 * 发给本地节点、等它回结果"的形式，也就是本接口要多一个异步的派发方法。
 * 这一步等本地节点程序就绪时再做，现在不预留空方法，避免留下没人调用的死代码。
 *
 * @author mybotweb
 */
public interface ModelRouter {

    /**
     * 挑出这次该用的算力客户端。
     *
     * @param userId 请求者标识，仅用于日志和后续"按用户指定路由"的扩展
     * @return 选中的客户端，不会是 {@code null}
     * @throws NoAvailableModelException 当策略不允许降级（{@code LOCAL_ONLY}）
     *                                   且本地离线时抛出
     */
    ChatModelClient chatModelClient(String userId);

    /**
     * 取当前生效的路由策略。
     *
     * @return 当前策略
     */
    RouteStrategy currentStrategy();

    /**
     * 修改路由策略（运行期生效，重启后回到配置文件里的值）。
     *
     * <p>给 {@code /strategy} 命令用。
     *
     * @param strategy 新策略，不能为 {@code null}
     */
    void setStrategy(RouteStrategy strategy);

    /**
     * 取本次会真正使用的算力名称，用于日志和回复文案。
     *
     * <p>和 {@code ChatModelClient.getProviderName()} 的区别：后者回答
     * "这个客户端是谁"（{@code ollama} / {@code cloud}），
     * 本方法回答"这次用谁"，并且考虑降级之后的实际结果。
     *
     * @return {@code ollama} 或 {@code cloud}
     */
    String activeProviderName();

    /**
     * 根据当前策略与本地节点在线状态，一句话说明这次怎么路由的。
     *
     * <p>用途是让 {@code /status} 和日志能解释"为什么走了云端"——
     * 没有这句话，管理员看到账单涨了却不知道是哪条策略导致的。
     *
     * @return 形如 {@code LOCAL_FIRST → 云端（本地节点离线，已降级）}
     */
    String describeDecision();

    /**
     * 本地节点是否在线（透传状态中心的结果）。
     *
     * @return 在线返回 {@code true}
     */
    boolean isLocalNodeOnline();
}
