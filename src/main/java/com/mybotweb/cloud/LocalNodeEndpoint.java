package com.mybotweb.cloud;

import lombok.extern.slf4j.Slf4j;

/**
 * 【留空待实现】本地节点接入端点 —— 云端用来接收本地算力节点连入的 WebSocket。
 *
 * <p><b>路径规划</b>：{@code /ws/local-agent}
 *
 * <p><b>它是干什么的？</b> 本地程序启动后<b>主动</b>连到云端这个端点，
 * 连上之后这条连接就变成双向通道：
 * <ul>
 *   <li>云端 → 本地：派发聊天任务（"这句闲聊你算一下"）</li>
 *   <li>本地 → 云端：回传结果、发送心跳、请求切换状态、应答控制命令</li>
 * </ul>
 *
 * <p><b>为什么是本地主动连云端，而不是云端连本地？</b>
 * 因为本地电脑没有公网 IP，云端根本连不进来。
 * "本地主动连出去"这一下，就把方向问题解决了——
 * 连上之后 WebSocket 是全双工的，云端照样能主动推消息给本地，
 * 所以功能上没有任何损失。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>实现时的技术要点</b>
 *
 * <p><b>1. 依赖选择</b>
 * 需要 {@code spring-boot-starter-websocket}。
 * 当前 pom 里<b>还没有引入</b>这个依赖（现在本地直连模式用不到），
 * 实现时先加上。
 *
 * <p><b>2. 端点类结构</b>
 * 用 Spring 的 STOMP 之外的原生方式（{@code TextWebSocketHandler}）更合适，
 * 因为我们要自己定义消息协议，不需要 STOMP 那套订阅模型：
 * <pre>{@code
 * @Component
 * public class LocalNodeEndpoint extends TextWebSocketHandler {
 *
 *     @Override
 *     public void afterConnectionEstablished(WebSocketSession session) {
 *         // 本地节点连上了
 *     }
 *
 *     @Override
 *     protected void handleTextMessage(WebSocketSession session, TextMessage message) {
 *         // 收到心跳 / 结果回传 / 切换请求，按消息类型分派
 *     }
 *
 *     @Override
 *     public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
 *         // 本地节点断开了，通知 LocalNodeManager
 *     }
 * }
 * }</pre>
 *
 * <p><b>3. 鉴权（不能省）</b>
 * 这个端点如果裸奔，任何人都能连上来说"我是你的本地节点"，
 * 然后就能接收派发给本地的聊天内容（用户的私聊！）。
 * 至少要做两件事：
 * <ul>
 *   <li>连接时校验一个预共享密钥（放在 URL 参数或第一条消息里），
 *       密钥从配置读，两边配置一致</li>
 *   <li>云端做来源 IP 限制</li>
 * </ul>
 * 规格第十三节把这条列为安全设计要求。
 *
 * <p><b>4. 消息协议要自己定</b>
 * WebSocket 是裸通道，消息格式由你决定。建议用 JSON，
 * 统一成 {@code {"type": "...", "payload": {...}}} 的结构，
 * 用 {@code type} 区分：{@code heartbeat} / {@code chat_request} /
 * {@code chat_response} / {@code switch_command} / {@code switch_ack}。
 * 定好之后写进文档，因为本地程序那边要照着实现。
 *
 * <p><b>5. 断线重连由谁负责？</b>
 * <b>由本地端负责</b>，云端不需要主动重连。
 * 本地端要做的：检测到断开后按退避策略重连
 * （1 秒、2 秒、4 秒……最多退避到 30 秒），
 * 避免云端重启时几百个节点同时冲上来。
 * 云端这边只需要在连接建立/断开时更新 {@link LocalNodeManager} 的状态。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>与"状态 B（本地接管腾讯连接）"的关系</b>
 *
 * <p>这条通道不只在"云端派发算力"时用。按我们讨论的三状态设计，
 * 状态 B（本地直接连腾讯收消息）时，本地仍然要保持这条通道连着，
 * 因为：
 * <ul>
 *   <li>业务命令（{@code /search} 等）需要转给云端查数据库</li>
 *   <li>切回状态 A 的指令要通过它通知云端</li>
 * </ul>
 * 所以它是<b>三个状态共用的地基</b>，设计时不能按"只在派发算力时用"来做。
 *
 * <p>本类现在故意不加 {@code @Component}，避免引入多余依赖和启动开销。
 * 实现时再打开。
 *
 * @author mybotweb
 */
@Slf4j
public class LocalNodeEndpoint {

    /**
     * WebSocket 路径常量。
     *
     * <p>先定下来是为了让本地程序那边能提前对齐，避免两边写不一致。
     */
    public static final String ENDPOINT_PATH = "/ws/local-agent";

    /**
     * 私有构造器：本类目前只是一个占位说明，不允许被实例化。
     *
     * <p>真正实现时删掉这个构造器，改成 Spring 组件。
     */
    private LocalNodeEndpoint() {
        throw new UnsupportedOperationException(
                "本地节点接入端点尚未实现。实现步骤与要点见本类的类注释。");
    }
}
