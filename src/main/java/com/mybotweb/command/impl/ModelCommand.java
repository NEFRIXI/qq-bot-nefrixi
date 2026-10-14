package com.mybotweb.command.impl;

import com.mybotweb.channel.model.QqMessage;
import com.mybotweb.command.CommandHandler;
import com.mybotweb.command.CommandNames;
import com.mybotweb.config.AppProperties;
import com.mybotweb.llm.ChatModelClient;
import com.mybotweb.llm.ollama.OllamaModelSelector;
import com.mybotweb.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /model} —— 查看和切换本地模型。仅管理员可用。
 *
 * <p>用法：
 * <pre>
 *   /model                          → 看当前用的哪个、本机有哪些可以切
 *   /model q7b                      → 用简写切换
 *   /model qwen2.5:7b               → 用完整名切换
 *   /model minicpm5-1b:q4_k_m       → 只写后半段也能认出来
 * </pre>
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>★ 三种"名字"，以及为什么要容忍这么多写法</b>
 *
 * <p>本地模型的完整名字可能长得离谱：
 * <pre>
 *   R4C3R/minicpm5-1b-fable5-heretic:q4_k_m
 * </pre>
 * 这一串要在手机 QQ 里打对，基本不可能。但切换模型又是高频动作
 * （调效果、省显存、对比速度）。所以本命令容忍三种输入：
 *
 * <ol>
 *   <li><b>简写别名</b> —— 在 {@code application.yml} 的
 *       {@code app.llm.ollama.model-aliases} 里配，比如 {@code q7b}</li>
 *   <li><b>完整名</b> —— 从 {@code /model} 列表里复制粘贴</li>
 *   <li><b>后半段</b> —— 只打 {@code minicpm5-1b:q4_k_m}，自动补上前缀
 *       （见 {@link #findInstalledModel} 的后缀匹配）</li>
 * </ol>
 *
 * <p>这三种都指向同一个模型，所以匹配出来之后统一用<b>本机实际的完整名</b>
 * 去切换——避免出现"命令说切了 q7b，但记录里存的是 q7b 这个不存在的名字"。
 *
 * <p><b>为什么只给管理员？</b> 它改的是全局状态：所有用户的聊天都会用新模型。
 *
 * <p><b>它原来带 {@code @ConditionalOnProperty(app.role = local)}，现在移除了。</b>
 * 那个条件的理由是"本类依赖 {@link OllamaModelSelector}，而后者只在本地模式存在"。
 * 但两个算力客户端现在改成同时装配（云端要当路由器、要保留"派给本地"的选项），
 * {@code OllamaModelSelector} 也随之常驻，所以本类不再有"依赖缺失"的问题。
 *
 * <p>保留它在云端可用的实际价值：{@code /setlocal} 把路由切到本地之后，
 * 用户紧接着就要用 {@code /model} 挑一个本地模型。如果这条命令在云端模式下
 * 被条件掉，那条路径就断了。</p>
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class ModelCommand implements CommandHandler {

    /** 模型选择器，负责记录和切换当前模型 */
    private final OllamaModelSelector modelSelector;

    /**
     * 本地算力客户端。
     *
     * <p>用它两个能力：{@code listAvailableModels()} 问本机实际有哪些模型，
     * {@code isAvailable()} 在切换后立刻验证新模型能不能用。
     *
     * <p><b>它指的是"本地"那一个，必须用 {@code @Qualifier} 指定</b>：
     * 现在按类型注入 {@code ChatModelClient} 会撞上多个候选 Bean
     * （本地、云端、mock）。而本命令的语义本来就是操作本地模型，
     * 所以限定到本地实现既解决了歧义，也让意图更明确。
     */
    private final ChatModelClient chatModelClient;

    /** Ollama 配置，用来读简写别名表 */
    private final AppProperties.Llm.Ollama ollamaConfig;

    public ModelCommand(OllamaModelSelector modelSelector,
                        @Qualifier("ollamaChatModelClient") ChatModelClient chatModelClient,
                        AppProperties appProperties) {
        this.modelSelector = modelSelector;
        this.chatModelClient = chatModelClient;
        this.ollamaConfig = appProperties.getLlm().getOllama();
    }

    @Override
    public String getName() {
        return CommandNames.MODEL;
    }

    @Override
    public List<String> getAliases() {
        return CommandNames.MODEL_ALIASES;
    }

    @Override
    public String getDescription() {
        return "查看或切换本地模型";
    }

    @Override
    public String getUsage() {
        return CommandNames.withSlash(CommandNames.MODEL) + " [模型名或简写]";
    }

    /** 仅管理员 —— 它改的是全局状态，影响所有用户 */
    @Override
    public boolean isAdminOnly() {
        return true;
    }

    @Override
    public String execute(QqMessage message, UserContext user, String[] args) {
        if (args.length == 0) {
            return describeCurrent();
        }
        return switchModel(args[0]);
    }

    /**
     * 展示当前模型和本机可选的模型。
     *
     * @return 状态文本
     */
    private String describeCurrent() {
        String current = modelSelector.getCurrentModel();
        List<String> installed = chatModelClient.listAvailableModels();

        StringBuilder sb = new StringBuilder();
        sb.append("当前模型：").append(displayOrUnknown(current));
        String currentAlias = findAliasFor(current);
        if (currentAlias != null) {
            sb.append("（").append(currentAlias).append("）");
        }
        sb.append('\n');

        // 探测可用性。可能耗时一两秒（有连接超时），但"当前模型能不能用"
        // 是看这条命令最想知道的事，值得等
        boolean available = chatModelClient.isAvailable();
        sb.append("状态　　：").append(available ? "正常" : "不可用").append('\n');

        if (!available) {
            sb.append("　　　　　不可用的原因见服务端日志（会列出本机已下载的模型）\n");
        }

        if (installed.isEmpty()) {
            // 拿不到列表 = Ollama 不可达。这时要说清"是拿不到，不是没有"
            sb.append("\n拿不到本机模型列表 —— Ollama 可能没启动。\n");
            sb.append("在 WSL 里执行 ollama list 可以看本机有哪些模型。");
            return sb.toString();
        }

        sb.append("\n本机已下载的模型（共 ").append(installed.size()).append(" 个）：\n");
        for (String model : installed) {
            sb.append("  ").append(model);

            // 有简写就标出来，让用户知道可以少打字
            String alias = findAliasFor(model);
            if (alias != null) {
                sb.append("　→ ").append(alias);
            }
            if (model.equals(current)) {
                sb.append("　← 当前在用");
            }
            sb.append('\n');
        }

        sb.append("\n切换：/model 简写或完整名（两种都行，只打后半段也能认出来）");

        if (!hasAnyAlias()) {
            // 没配别名时提示一下，否则用户不知道有这个能力
            sb.append("\n想给模型配简写？改 application.yml 的 app.llm.ollama.model-aliases");
        }

        return sb.toString();
    }

    /**
     * 切换到指定模型。
     *
     * <p>解析顺序很重要：<b>先认简写，再找本机实际模型</b>。
     * 因为用户可能输入的是简写（配置里定义的），也可能是完整名，
     * 还可能是只记得的后半段。
     *
     * @param rawName 用户输入的模型名（简写 / 完整名 / 后半段）
     * @return 执行结果文本
     */
    private String switchModel(String rawName) {
        String input = rawName.strip();

        // ---- 第一步：如果是简写，先翻译成完整名 ----
        String expanded = resolveAlias(input);
        boolean usedAlias = !expanded.equals(input);

        // ---- 第二步：确认本机到底有没有这个模型 ----
        List<String> installed = chatModelClient.listAvailableModels();
        String target = expanded;

        if (!installed.isEmpty()) {
            String matched = findInstalledModel(installed, expanded);
            if (matched == null) {
                return buildNotFoundMessage(input, installed);
            }
            // 统一用本机实际的完整名 —— 避免把简写或半截名字存进去
            target = matched;
        }

        // ---- 第三步：配置白名单校验（只有配了清单才生效）----
        String before = modelSelector.getCurrentModel();
        if (!modelSelector.switchTo(target)) {
            StringBuilder sb = new StringBuilder();
            sb.append("切换被拒绝：").append(target);
            sb.append(" 不在配置的 app.llm.ollama.available-models 清单里。\n");
            sb.append("清单：").append(String.join("、", modelSelector.getSelectableModels()));
            sb.append("\n\n如果确实想用它，把配置里的清单改掉或者清空（清空就是不限制）。");
            return sb.toString();
        }

        // 已经就是当前模型
        if (before != null && before.equals(modelSelector.getCurrentModel())) {
            return "当前已经在使用 " + modelSelector.getCurrentModel() + "，没有变化。";
        }

        // ---- 第四步：切换成功，立刻探测新模型能不能真跑起来 ----
        boolean available = chatModelClient.isAvailable();

        StringBuilder sb = new StringBuilder();
        sb.append("模型已切换：").append(displayOrUnknown(before))
                .append(" → ").append(modelSelector.getCurrentModel());
        if (usedAlias) {
            sb.append("（你输入的简写 ").append(input).append("）");
        }
        sb.append('\n');
        sb.append("可用性：").append(available ? "正常，下一条消息就会用新模型" : "不可用");
        if (!available) {
            sb.append("\n　　　　模型文件在，但服务可能有问题。服务端日志里有详细原因。");
        }
        return sb.toString();
    }

    /**
     * 在本机已下载的列表里找匹配项。
     *
     * <p>容忍四种输入差异，按优先级依次尝试：
     * <ol>
     *   <li><b>完全一致</b>（忽略大小写）</li>
     *   <li><b>差一个 {@code :latest} 后缀</b> —— Ollama 对没写标签的模型会补 {@code :latest}，
     *       所以用户写 {@code qwen2.5} 而实际叫 {@code qwen2.5:latest} 是常事</li>
     *   <li><b>只写了后半段</b> —— 比如输入 {@code minicpm5-1b-fable5-heretic:q4_k_m}，
     *       而完整名是 {@code R4C3R/minicpm5-1b-fable5-heretic:q4_k_m}。
     *       这是实际最常发生的情况：用户记不住那个 {@code R4C3R/} 前缀</li>
     *   <li><b>包含匹配</b> —— 更宽松，但只在<b>唯一命中</b>时才采用，
     *       否则会有歧义</li>
     * </ol>
     *
     * @param installed 本机已下载的模型
     * @param target    用户输入（可能已经被简写翻译过）
     * @return 匹配到的实际模型名；没匹配上返回 {@code null}
     */
    private String findInstalledModel(List<String> installed, String target) {
        String lowerTarget = target.toLowerCase();

        // 1. 完全一致
        for (String model : installed) {
            if (model.equalsIgnoreCase(target)) {
                return model;
            }
        }

        // 2. :latest 后缀差异
        for (String model : installed) {
            String lowerModel = model.toLowerCase();
            if (lowerModel.equals(lowerTarget + ":latest")) {
                return model;
            }
            if (lowerTarget.endsWith(":latest")
                    && lowerModel.equals(lowerTarget.substring(0, lowerTarget.length() - 7))) {
                return model;
            }
        }

        // 3. 只写了后半段（缺命名空间前缀）
        for (String model : installed) {
            if (model.toLowerCase().endsWith("/" + lowerTarget)) {
                return model;
            }
        }

        // 4. 包含匹配 —— 只在唯一命中时采用，否则有歧义
        List<String> contains = new ArrayList<>();
        for (String model : installed) {
            if (model.toLowerCase().contains(lowerTarget)) {
                contains.add(model);
            }
        }
        if (contains.size() == 1) {
            return contains.get(0);
        }

        return null;
    }

    /**
     * 生成"本机没有这个模型"的提示。
     *
     * <p>把可选模型连同简写一起列出来，让用户照着复制——
     * 模型名手打几乎必然出错，与其让人对着"切换失败"发呆，
     * 不如把能用的直接摆出来。
     *
     * @param input     用户输入
     * @param installed 本机实际有的模型
     * @return 提示文本
     */
    private String buildNotFoundMessage(String input, List<String> installed) {
        StringBuilder sb = new StringBuilder();
        sb.append("本机没有这个模型：").append(input).append('\n');
        sb.append("\n本机已下载的模型（用简写或完整名都行）：\n");
        for (String model : installed) {
            sb.append("  ").append(model);
            String alias = findAliasFor(model);
            if (alias != null) {
                sb.append("　→ ").append(alias);
            }
            sb.append('\n');
        }
        sb.append("\n用法：/model 模型名或简写");
        return sb.toString();
    }

    /**
     * 把简写翻译成完整模型名。
     *
     * <p>忽略大小写：用户打 {@code Q7B} 和 {@code q7b} 都一样。
     *
     * @param input 用户输入
     * @return 完整模型名；不是简写时原样返回
     */
    private String resolveAlias(String input) {
        for (Map.Entry<String, String> entry : ollamaConfig.getModelAliases().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(input)) {
                return entry.getValue();
            }
        }
        return input;
    }

    /**
     * 反查某个完整模型名对应的简写（展示用）。
     *
     * @param fullName 完整模型名
     * @return 简写；没配则返回 {@code null}
     */
    private String findAliasFor(String fullName) {
        if (fullName == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : ollamaConfig.getModelAliases().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(fullName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 是否配置了任何简写。
     *
     * @return 配了至少一个返回 {@code true}
     */
    private boolean hasAnyAlias() {
        return !ollamaConfig.getModelAliases().isEmpty();
    }

    /**
     * 处理模型名为空的情况。
     *
     * @param model 模型名
     * @return 可直接展示的文本
     */
    private String displayOrUnknown(String model) {
        return (model == null || model.isBlank()) ? "（未配置）" : model;
    }
}
