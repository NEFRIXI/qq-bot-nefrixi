package com.mybotweb.command;

import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.security.UserContext;

import java.util.List;

/**
 * 斜杠命令处理器 —— 一个实现类负责一条命令（如 {@code /help}）。
 *
 * <p><b>为什么不走大模型？</b> 这是规格第 5.2 节的核心约定，也是整个项目
 * 最重要的一条性能与确定性约束。理由有三：
 * <ol>
 *   <li><b>慢</b>：用户已经明确说了要干什么（"查"、"删"），再让模型"理解"一遍纯属浪费几秒</li>
 *   <li><b>贵</b>：每次都要烧 token，而命令调用可能很频繁</li>
 *   <li><b>不确定</b>：模型可能把参数理解歪，甚至编造一条不存在的记录返回给用户</li>
 * </ol>
 * 命令的本质是"确定性的字符串映射"：{@code /search 大模型}
 * → 命令名 {@code search}、参数 {@code [大模型]} → 调搜索接口 → 格式化输出。
 * 全程是可预测的代码路径。
 *
 * <p><b>新增一条命令的做法</b>：写一个类实现本接口，加上 {@code @Component} 注解，
 * 什么都不用注册——{@code CommandRouter} 会自动收集所有实现类。
 * 这是 Spring 的"依赖注入集合"用法：
 * 构造器里声明 {@code List<CommandHandler>}，Spring 会把所有实现都塞进来。
 *
 * @author mybotweb
 */
public interface CommandHandler {

    /**
     * 命令名，不带斜杠。例如 {@code "help"} 对应用户输入的 {@code /help}。
     *
     * <p>约定：全小写英文，多个单词用下划线连接（{@code local_status}）。
     * 这个名字也用于权限校验和日志，必须唯一。
     *
     * @return 命令名
     */
    String getName();

    /**
     * 命令别名，用户可以拿别名调用。
     *
     * <p>例如 {@code /help} 和 {@code /?} 都能触发帮助。
     * 没有别名时返回空列表即可，不要返回 {@code null}——
     * 调用方会直接遍历，返回 null 会导致空指针。
     *
     * @return 别名列表，没有则返回空列表
     */
    default List<String> getAliases() {
        return List.of();
    }

    /**
     * 命令用途的一句话说明，会出现在 {@code /help} 的输出里。
     *
     * <p>写"给用户看的说明"，不是给开发者看的注释。
     * 例如"查看系统当前状态"，而不是"调用 status 服务返回状态对象"。
     *
     * @return 命令说明
     */
    String getDescription();

    /**
     * 用法示例，会出现在 {@code /help 命令名} 的详细输出里。
     *
     * @return 形如 {@code /search 关键词} 的用法串
     */
    default String getUsage() {
        return "/" + getName();
    }

    /**
     * 这条命令是否只允许管理员使用。
     *
     * <p>对应规格第 4.2 节的权限差异表：
     * <ul>
     *   <li>{@code false} —— 查询类命令，所有人可用（{@code /help}、{@code /search}）</li>
     *   <li>{@code true} —— 管理类命令，只有管理员可用（{@code /delete}、{@code /strategy}）</li>
     * </ul>
     *
     * <p><b>为什么让命令自己声明权限，而不是在 Router 里写一张表？</b>
     * 因为声明和实现放在一起，新增命令时不容易漏掉权限设置；
     * 如果写成 Router 里的一张清单，加命令忘了加清单，就出现越权漏洞。
     *
     * @return 仅管理员可用返回 {@code true}
     */
    default boolean isAdminOnly() {
        return false;
    }

    /**
     * 这条命令是否<b>从帮助列表里隐藏</b>。
     *
     * <p><b>它和 {@link #isAdminOnly()} 是两件不同的事</b>，别搞混：
     * <ul>
     *   <li>{@code isAdminOnly} —— 决定<b>能不能执行</b>（会被 CommandRouter 拦）</li>
     *   <li>{@code isHiddenFromHelp} —— 只决定<b>列不列出来</b>（不影响执行）</li>
     * </ul>
     *
     * <p><b>什么时候需要这个？</b> 当一条命令的权限是<b>动态的</b>，
     * 没法用 {@code isAdminOnly} 这种静态标记表达的时候。
     *
     * <p>典型例子是 {@code /admin}：它在"管理员名单为空"时对所有人开放
     * （为了能初始化第一个管理员），名单非空时又只有管理员能用。
     * 如果给它标 {@code isAdminOnly = true}，自举就没法用了；
     * 标 {@code false} 又会让它出现在普通用户的 {@code /help} 里，
     * 而普通用户看到"管理员管理"这种命令只会觉得莫名其妙。
     *
     * <p>所以拆成两个维度：<b>执行时按实际状态判断，展示时直接隐藏</b>。
     * 管理员仍然能在 {@code /help_admin} 里看到它。
     *
     * @return 在普通用户的帮助里隐藏返回 {@code true}
     */
    default boolean isHiddenFromHelp() {
        return false;
    }

    /**
     * 执行命令。
     *
     * <p><b>实现约定</b>：
     * <ul>
     *   <li>返回值就是直接发给用户的文本，实现类负责把结果整理成人能读的格式，
     *       不要返回 JSON 或调试信息</li>
     *   <li>不要在这里做权限判断——{@code CommandRouter} 已经根据
     *       {@link #isAdminOnly()} 统一判过了，重复判断容易两处不一致</li>
     *   <li>可以抛异常，{@code CommandRouter} 会兜住并给用户一句友好的提示，
     *       不会让机器人卡死</li>
     *   <li>返回的文本要控制长度：QQ 单条消息有长度上限，太长的结果要截断
     *       或提示用户"结果过多，请缩小范围"</li>
     * </ul>
     *
     * @param message 原始的入站消息，需要时可以从里面取群号等上下文
     * @param user    当前用户上下文（已确认权限符合 {@link #isAdminOnly()} 的要求）
     * @param args    命令参数。{@code /search 大模型} 会得到 {@code ["大模型"]}；
     *                没有参数时是长度为 0 的数组，<b>不会是 null</b>
     * @return 要发给用户的文本
     */
    String execute(QqMessage message, UserContext user, String[] args);
}
