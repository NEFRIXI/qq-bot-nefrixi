package com.mybotweb.command.impl;

import com.mybotweb.agent.prompt.SystemPromptManager;
import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.command.CommandHandler;
import com.mybotweb.command.CommandNames;
import com.mybotweb.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code /model-p} —— 查看和切换 System Prompt。仅管理员可用。
 *
 * <p>用法：
 * <pre>
 *   /model-p                     → 列出全部提示词文件、当前用的是哪个
 *   /model-p chat_default        → 切换（后缀可带可不带）
 *   /model-p reset               → 恢复"按身份自动选"
 * </pre>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>★ 为什么把原来两条命令合并成一条</b>
 *
 * <p>最初是两条：{@code /model-prompt-list} 和 {@code /model-prompt-select 文件名}。
 * 实际用起来才发现两个问题：
 *
 * <ol>
 *   <li><b>名字太长。</b> 这两条是高频命令（调人设要反复切），
 *       每次打 18 个字符不现实，而手机输入法还会自动纠错</li>
 *   <li><b>"列表"和"切换"本来就是一回事的两面。</b>
 *       想看列表的时候往往就是想切换，而切换之前又一定需要知道有哪些可选。
 *       拆成两条命令，用户得记"我要先 list 再 select"这个流程</li>
 * </ol>
 *
 * <p>合并之后的规则很自然：<b>不带参数就列出来，带参数就切过去</b>。
 * 和 {@code /model} 自己（无参数看状态、带参数切换）保持一致的形态，
 * 记一个就够。
 *
 * <p>旧的两个长名字保留成别名，主要是怕你已经形成肌肉记忆。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>切换是"全局生效"的</b>
 *
 * <p>切了之后<b>所有人</b>（包括管理员自己）都用那一个文件，不再按身份区分。
 * 这是刻意的——切换的目的是做对比实验，如果只影响一部分人，
 * 你就没法预期"别人会得到什么回复"了。用 {@code reset} 恢复按身份区分。
 *
 * <p>另外注意两个"生效时机"的区别：
 * <ul>
 *   <li><b>切换文件名</b>（本命令）—— <b>立即生效</b>，不用重启</li>
 *   <li><b>改文件内容</b>（编辑 md）—— <b>要重启</b>，因为内容缓存在内存里</li>
 * </ul>
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelPromptCommand implements CommandHandler {

    /** 用于恢复默认的关键字 */
    private static final List<String> RESET_KEYWORDS =
            List.of("reset", "default", "auto", "默认", "自动");

    /** 提示词管理器 */
    private final SystemPromptManager promptManager;

    @Override
    public String getName() {
        return CommandNames.MODEL_PROMPT;
    }

    @Override
    public List<String> getAliases() {
        // 前两个是旧的命令名，保留是为了兼容已经形成的使用习惯
        return CommandNames.MODEL_PROMPT_ALIASES;
    }

    @Override
    public String getDescription() {
        return "查看或切换 System Prompt";
    }

    @Override
    public String getUsage() {
        return CommandNames.withSlash(CommandNames.MODEL_PROMPT) + " [提示词文件名]";
    }

    /** 仅管理员 */
    @Override
    public boolean isAdminOnly() {
        return true;
    }

    @Override
    public String execute(QqMessage message, UserContext user, String[] args) {
        // 不带参数 → 列出文件；带参数 → 切换
        if (args.length == 0) {
            return listPrompts();
        }
        return selectPrompt(args[0]);
    }

    /**
     * 列出所有可用的提示词文件，并显示当前用的是哪个。
     *
     * @return 列表文本
     */
    private String listPrompts() {
        List<String> files = promptManager.listAvailable();

        StringBuilder sb = new StringBuilder();

        if (files.isEmpty()) {
            // 扫不到文件通常意味着目录名大小写不对，或者资源没被打进包
            sb.append("没有找到任何 System Prompt 文件。\n\n");
            sb.append("请检查这个目录是否存在、里面有没有 .md 文件：\n");
            sb.append("  src/main/resources/SysPrompt/\n\n");
            sb.append("注意目录名大小写必须完全一致（Linux 上区分大小写）。");
            return sb.toString();
        }

        sb.append("System Prompt 文件（共 ").append(files.size()).append(" 个）：\n");
        for (String file : files) {
            sb.append("  ").append(file).append(".md\n");
        }

        sb.append("\n当前使用：");
        if (promptManager.isGlobalOverride()) {
            sb.append("\n  所有人 → ").append(promptManager.getGlobalPromptName()).append(".md");
            sb.append("\n  （全局覆盖中，不区分身份）");
        } else {
            sb.append("\n  普通用户 → ").append(SystemPromptManager.DEFAULT_USER_PROMPT).append(".md");
            sb.append("\n  管理员　 → ").append(SystemPromptManager.DEFAULT_ADMIN_PROMPT).append(".md");
            sb.append("\n  （按身份自动选）");
        }

        sb.append("\n\n切换：/model-p 文件名（后缀 .md 可带可不带）");
        sb.append("\n恢复自动选择：/model-p reset");
        sb.append("\n\n加新人设：在 SysPrompt/ 目录下新建 .md 文件，然后重启应用。");

        return sb.toString();
    }

    /**
     * 切换提示词，或恢复默认。
     *
     * @param input 用户输入的文件名，或 reset 关键字
     * @return 执行结果文本
     */
    private String selectPrompt(String input) {
        String value = input.strip();

        // ---- 恢复默认 ----
        if (RESET_KEYWORDS.stream().anyMatch(k -> k.equalsIgnoreCase(value))) {
            boolean wasOverridden = promptManager.isGlobalOverride();
            promptManager.resetToDefault();

            if (!wasOverridden) {
                return "本来就是按身份自动选的，没有需要恢复的覆盖。";
            }
            return """
                    已恢复"按身份自动选"：
                      普通用户 → %s.md
                      管理员　 → %s.md""".formatted(
                    SystemPromptManager.DEFAULT_USER_PROMPT,
                    SystemPromptManager.DEFAULT_ADMIN_PROMPT);
        }

        // ---- 切换 ----
        if (!promptManager.selectGlobal(value)) {
            // 切不过去基本只有一种原因：文件不存在。把可选清单列出来，
            // 让用户照着复制——文件名打错的概率远高于文件真的不存在
            StringBuilder sb = new StringBuilder();
            sb.append("切换失败：找不到这个提示词文件\n  ").append(value).append(".md\n");

            List<String> files = promptManager.listAvailable();
            if (files.isEmpty()) {
                sb.append("\nSysPrompt 目录里一个文件都没有。请检查：")
                        .append("\n  src/main/resources/SysPrompt/");
            } else {
                sb.append("\n可用的文件（照着复制即可）：\n");
                for (String file : files) {
                    sb.append("  ").append(file).append('\n');
                }
            }
            sb.append("\n发 /model-p 可以看详细信息。");
            return sb.toString();
        }

        String selected = promptManager.getGlobalPromptName();

        return """
                已切换 System Prompt：%s.md
                
                下一条消息就会用这个新人设（不用重启）。
                
                注意：这会影响【所有用户】，包括你自己——这是刻意的，
                方便你做对比实验。想恢复按身份区分：/model-p reset
                
                想改这个文件的内容？去编辑：
                  src/main/resources/SysPrompt/%s.md
                （改内容需要重启应用才生效，切换文件名不需要）""".formatted(selected, selected);
    }
}
