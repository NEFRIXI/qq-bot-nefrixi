package com.mybotweb.agent.prompt;

import com.mybotweb.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * System Prompt 管理器 —— 决定"这次聊天用哪个人设文件"。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>两种工作模式</b>
 *
 * <p><b>默认模式（按身份自动选）</b>：管理员的对话加载 {@code chat_admin.md}，
 * 普通用户加载 {@code chat_default.md}。这是规格第 4.3 节"双身份"的体现——
 * 同一套模型，靠不同人设表现出不同的说话方式。
 *
 * <p><b>全局覆盖模式（管理员手动指定）</b>：管理员用
 * {@code /model-prompt-select 文件名} 指定之后，<b>所有人</b>
 * （包括管理员自己）都用那一个文件，直到被重置。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么要做"全局覆盖"这一层？</b>
 *
 * <p>因为调人设是个反复试的过程。你写了一个新人设想看看效果，
 * 如果只能靠改文件+重启来切换，试五次就要重启五次；而且切换的粒度
 * 是"整个应用"，不是"这一轮对话"。
 *
 * <p>有了运行时切换，你可以：写好两个版本 → {@code /model-prompt-select}
 * 切过去聊两句 → 觉得不好再切回来。整个过程中机器人一直在线。
 *
 * <p><b>为什么是"全局"而不是"每个用户各用各的"？</b>
 * 因为切换的目的是<b>你自己做对比实验</b>，而不是给不同用户配不同人设。
 * 如果做成 per-user，你反而没法预期"另一个人会得到什么回复"，
 * 对比就失去意义了。真正的 per-user 人设需求应该通过
 * "按身份自动选"那一层实现（加文件 + 改默认映射），而不是靠这个开关。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>状态存在哪？</b>
 *
 * <p>只存在内存里，<b>重启就恢复默认</b>。这是有意的：
 * 它是个"实验开关"，不是配置。如果你试出某个提示词特别好，
 * 正确的做法是把它设为默认（改 {@link #DEFAULT_USER_PROMPT} 对应的文件内容，
 * 或者把映射改成你的文件），而不是指望这个开关记住你的选择。
 *
 * <p>将来如果要持久化（存数据库），改动点只有构造器和
 * {@link #selectGlobal} 两处。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class SystemPromptManager {

    /**
     * 普通用户默认加载的提示词文件名（不含 {@code .md}）。
     */
    public static final String DEFAULT_USER_PROMPT = "chat_default";

    /**
     * 管理员默认加载的提示词文件名（不含 {@code .md}）。
     */
    public static final String DEFAULT_ADMIN_PROMPT = "chat_admin";

    /** 人设加载器，负责真正读文件 */
    private final PromptLoader promptLoader;

    /**
     * 全局覆盖的提示词名。
     *
     * <p>{@code null} 表示"没有覆盖，按身份自动选"。
     *
     * <p>用 {@code volatile} 而不是加锁：它是个单一字段，
     * 要么是旧值要么是新值，没有"中间状态"需要保护。
     * {@code volatile} 保证的可见性正是这里需要的。
     */
    private volatile String globalPromptName;

    public SystemPromptManager(PromptLoader promptLoader) {
        this.promptLoader = promptLoader;
    }

    /**
     * 决定某个用户这次该用哪个提示词文件。
     *
     * <p>调用方（{@code MessageDispatcher}）只需要调这一个方法，
     * 不需要知道有没有覆盖、默认是什么。
     *
     * @param user 当前用户上下文
     * @return 提示词文件名（不含 {@code .md}）
     */
    public String resolvePromptName(UserContext user) {
        String global = this.globalPromptName;
        if (global != null) {
            return global;
        }
        return user.isAdmin() ? DEFAULT_ADMIN_PROMPT : DEFAULT_USER_PROMPT;
    }

    /**
     * 取该用户这次应该用的提示词<b>内容</b>。
     *
     * @param user 当前用户上下文
     * @return 提示词正文
     */
    public String loadPromptFor(UserContext user) {
        return this.promptLoader.load(resolvePromptName(user));
    }

    /**
     * 当前是否处于全局覆盖模式。
     *
     * @return 被覆盖返回 {@code true}
     */
    public boolean isGlobalOverride() {
        return this.globalPromptName != null;
    }

    /**
     * 取当前全局覆盖的提示词名。
     *
     * @return 文件名；未覆盖时返回 {@code null}
     */
    public String getGlobalPromptName() {
        return this.globalPromptName;
    }

    /**
     * 设置全局覆盖。
     *
     * <p>会先检查文件是否存在——切到一个不存在的文件，
     * 表现是"命令说切换成功了，但聊天用的还是兜底人设"，
     * 那种不一致很难查。所以这里提前拦住。
     *
     * @param promptName 提示词文件名（可带 {@code .md} 后缀，会自动去掉）
     * @return 切换成功返回 {@code true}；文件不存在返回 {@code false}
     */
    public boolean selectGlobal(String promptName) {
        String normalized = normalizeName(promptName);
        if (normalized == null) {
            return false;
        }
        if (!this.promptLoader.exists(normalized)) {
            log.warn("拒绝切换提示词：文件不存在 SysPrompt/{}.md", normalized);
            return false;
        }

        String previous = this.globalPromptName == null
                ? "（按身份自动选）"
                : this.globalPromptName;
        this.globalPromptName = normalized;

        log.info("System Prompt 已全局切换：{} → {}", previous, normalized);
        return true;
    }

    /**
     * 取消全局覆盖，恢复"按身份自动选"。
     */
    public void resetToDefault() {
        String previous = this.globalPromptName;
        this.globalPromptName = null;
        log.info("System Prompt 已恢复默认（按身份自动选），之前覆盖的是：{}", previous);
    }

    /**
     * 列出所有可选的提示词文件名。
     *
     * @return 文件名列表（不含后缀）
     */
    public List<String> listAvailable() {
        return this.promptLoader.listPromptNames();
    }

    /**
     * 把用户输入的文件名规整成内部使用的形式。
     *
     * <p>处理三种常见输入：带 {@code .md} 后缀、带 {@code SysPrompt/} 前缀、
     * 前后有空格。用户从文件管理器里复制文件名时很容易带上这些。
     *
     * @param raw 原始输入
     * @return 规整后的文件名；输入为空时返回 {@code null}
     */
    private String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.strip();

        // 去掉可能的路径前缀（用户可能复制了 SysPrompt/chat_default.md 这样的完整路径）
        int slashIndex = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slashIndex >= 0) {
            name = name.substring(slashIndex + 1);
        }

        // 去掉 .md 后缀（大小写都处理）
        if (name.toLowerCase().endsWith(".md")) {
            name = name.substring(0, name.length() - 3);
        }

        return name.isBlank() ? null : name;
    }
}
