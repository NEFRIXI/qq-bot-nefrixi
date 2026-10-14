package com.mybotweb.channel;

import com.mybotweb.channel.model.QqReply;

/**
 * 消息通道 —— 负责"把回复送出去"。
 *
 * <p><b>这是整个项目里最重要的一个抽象。</b> 它的存在，是为了让
 * "消息从哪来、往哪回"这件事可以整体替换，而下游代码完全不用改。
 *
 * <p>目前规划的实现：
 * <table border="1">
 *   <tr><th>实现类</th><th>用途</th><th>状态</th></tr>
 *   <tr>
 *     <td>{@code MockMessageChannel}</td>
 *     <td>把回复打到控制台日志，配合本地 HTTP 调试入口使用</td>
 *     <td>已实现</td>
 *   </tr>
 *   <tr>
 *     <td>{@code QqMessageChannel}</td>
 *     <td>调腾讯 REST 接口真的把消息发出去（本地直连模式）</td>
 *     <td>第一步实现</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CloudMessageChannel}</td>
 *     <td>云服务器模式下的发送实现</td>
 *     <td>留空待填</td>
 *   </tr>
 * </table>
 *
 * <p>切换实现在 {@code application.yml} 的 {@code app.channel.type} 里改一个值即可，
 * 靠 {@code @ConditionalOnProperty} 在启动时决定装配哪个 Bean，
 * 不需要改 Java 代码、也不需要注释掉谁。
 *
 * @author mybotweb
 */
public interface MessageChannel {

    /**
     * 把一条回复送出去。
     *
     * <p><b>实现约定</b>：
     * <ul>
     *   <li>这是个<b>尽力而为</b>的动作。发送失败不应该把异常抛回给消息处理链路，
     *       因为那时候"处理逻辑"已经跑完了，抛异常只会让用户看到一条报错，
     *       而不会让消息发出去。实现里应该自己 try-catch 并记日志。</li>
     *   <li>实现不应该阻塞太久。腾讯接口超时给几秒就够，本地小模型那种
     *       几分钟的等待发生在<b>调用模型</b>那一步，不在发送这一步。</li>
     * </ul>
     *
     * @param reply 要发送的回复
     */
    void send(QqReply reply);

    /**
     * 返回通道类型标识，用于日志和状态查询。
     *
     * <p>取值应当与配置项 {@code app.channel.type} 保持一致，
     * 例如 {@code "mock"}、{@code "qq"}。这样 {@code /status} 命令
     * 能直接告诉用户"现在用的是哪个通道"。
     *
     * @return 通道类型标识
     */
    String getType();
}
