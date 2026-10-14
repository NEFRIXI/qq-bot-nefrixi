package com.mybotweb.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人设加载器 —— 从 {@code SysPrompt} 目录读取 System Prompt 文件。
 *
 * <p>目录结构：
 * <pre>
 *   src/main/resources/SysPrompt/
 *   ├── chat_default.md    普通用户的聊天人设
 *   ├── chat_admin.md      管理员的聊天人设
 *   └── （你可以随意加更多 .md 文件）
 * </pre>
 *
 * <p><b>为什么做成文件，而不是把提示词写在 Java 代码里？</b>
 * 因为改人设是最频繁的操作——调语气、加规则、改称呼。
 * 写在 md 文件里改完就能生效（见下面的缓存说明），不用碰代码、
 * 也不用重新理解一个 Java 类。这让"人设"和"逻辑"彻底分离：
 * 代码只管怎么调模型，md 只管模型该像谁。
 *
 * <p><b>加一个新提示词的完整步骤</b>（不用改任何 Java 代码）：
 * <ol>
 *   <li>在 {@code src/main/resources/SysPrompt/} 下新建一个 {@code .md} 文件</li>
 *   <li>把提示词内容写进去</li>
 *   <li>重启应用（新文件才会被扫描到）</li>
 *   <li>在 QQ 里发 {@code /model-prompt-list} 确认它出现了</li>
 *   <li>发 {@code /model-prompt-select 文件名} 切过去</li>
 * </ol>
 *
 * <p><b>缓存策略</b>：读到的内容缓存在内存里，避免每条消息都读一次磁盘。
 * 代价是<b>改了 md 内容需要重启才生效</b>。
 *
 * <p>注意这里和"切换提示词"是两件事：
 * <ul>
 *   <li><b>切换</b>（{@code /model-prompt-select}）换的是"用哪个文件"，立即生效</li>
 *   <li><b>改内容</b>（编辑 md 文件）改的是"文件里写了什么"，要重启才生效</li>
 * </ul>
 * 前者是运行时状态，后者是磁盘上的文件内容。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class PromptLoader {

    /**
     * 提示词文件所在目录（相对于 classpath）。
     *
     * <p><b>大小写要完全一致。</b> Windows 的文件系统不区分大小写，
     * 但 Linux 区分——而这个项目最终要部署到 Linux 云服务器上。
     * 写错大小写的表现是"在 Windows 上跑得好好的，上服务器就找不到文件"，
     * 属于最难查的一类问题。
     */
    private static final String PROMPT_DIRECTORY = "SysPrompt";

    /** 提示词文件后缀 */
    private static final String PROMPT_SUFFIX = ".md";

    /**
     * 读不到文件时使用的兜底人设。
     *
     * <p>为什么不直接抛异常？因为"少了一个 md 文件"不该让机器人完全不能用。
     * 用一句最朴素的人设先撑着，同时在日志里明确报错，比整个聊天功能挂掉好得多。
     */
    private static final String FALLBACK_PROMPT =
            "你是一个运行在 QQ 上的聊天助手。回答要简洁、友好、口语化，"
                    + "一般不超过三句话，不要使用 Markdown 格式，因为 QQ 不渲染它。";

    /**
     * 资源解析器。
     *
     * <p>这里注入的是 {@link ResourcePatternResolver} 而不是普通的
     * {@code ResourceLoader}，因为我们需要<b>按通配符扫描目录</b>
     * （{@code SysPrompt/*.md}），而不只是读取一个已知路径的文件。
     */
    private final ResourcePatternResolver resourceResolver;

    /** 人设内容缓存：文件名（不含后缀） → 内容 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptLoader(ResourcePatternResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    /**
     * 加载指定人设文件的内容。
     *
     * @param promptName 人设名，不含路径和后缀，例如 {@code "chat_default"}
     * @return 人设内容；文件不存在时返回兜底人设
     */
    public String load(String promptName) {
        // computeIfAbsent 保证同一个 key 只真正读一次，多线程同时调用也安全
        return cache.computeIfAbsent(promptName, this::readPromptFile);
    }

    /**
     * 列出所有可用的提示词文件名（不含 {@code .md} 后缀）。
     *
     * <p>用途：{@code /model-prompt-list} 命令要展示可选清单。
     * <b>直接扫描磁盘目录</b>，而不是读配置——这样你新加一个 md 文件，
     * 重启后它就会自动出现在列表里，不用改配置。
     *
     * <p>用 {@code classpath*:} 前缀（注意那个星号）而不是 {@code classpath:}：
     * 前者会扫描所有 classpath 位置，后者只找第一个匹配。
     * 项目打成 jar 之后，只有 {@code classpath*:} 才能可靠地列出目录内容。
     *
     * @return 文件名列表（已排序）；扫描失败时返回空列表，<b>不会是 null</b>
     */
    public List<String> listPromptNames() {
        String pattern = "classpath*:" + PROMPT_DIRECTORY + "/*" + PROMPT_SUFFIX;
        try {
            Resource[] resources = this.resourceResolver.getResources(pattern);
            return Arrays.stream(resources)
                    .map(Resource::getFilename)
                    .filter(Objects::nonNull)
                    .filter(name -> name.toLowerCase().endsWith(PROMPT_SUFFIX))
                    // 去掉 .md 后缀：命令里让用户打完整文件名太啰嗦
                    .map(name -> name.substring(0, name.length() - PROMPT_SUFFIX.length()))
                    .sorted()
                    .toList();

        } catch (IOException e) {
            // 扫不到通常意味着目录名写错了，或者资源没被打进包。
            // 报 ERROR，因为它会让 /model-prompt-list 变空，用户会以为文件丢了
            log.error("扫描提示词目录失败，模式：{}。请检查 src/main/resources/{} 是否存在",
                    pattern, PROMPT_DIRECTORY, e);
            return List.of();
        }
    }

    /**
     * 判断某个提示词文件是否存在。
     *
     * @param promptName 人设名，不含后缀
     * @return 文件存在返回 {@code true}
     */
    public boolean exists(String promptName) {
        if (promptName == null || promptName.isBlank()) {
            return false;
        }
        return this.resourceResolver
                .getResource("classpath:" + PROMPT_DIRECTORY + "/" + promptName + PROMPT_SUFFIX)
                .exists();
    }

    /**
     * 真正从 classpath 读取人设文件。
     *
     * @param promptName 人设名
     * @return 文件内容，读不到则返回兜底人设
     */
    private String readPromptFile(String promptName) {
        String location = "classpath:" + PROMPT_DIRECTORY + "/" + promptName + PROMPT_SUFFIX;
        Resource resource = this.resourceResolver.getResource(location);

        if (!resource.exists()) {
            log.error("人设文件不存在：{}。将使用兜底人设。请检查 src/main/resources/{}/{}{}",
                    location, PROMPT_DIRECTORY, promptName, PROMPT_SUFFIX);
            return FALLBACK_PROMPT;
        }

        try {
            // 必须显式指定 UTF-8：人设是中文，用平台默认编码在 Windows 上
            // 可能读成乱码，而乱码的提示词会让模型输出莫名其妙的内容
            String content = StreamUtils.copyToString(
                    resource.getInputStream(), StandardCharsets.UTF_8);

            if (content.isBlank()) {
                log.error("人设文件内容为空：{}。将使用兜底人设。", location);
                return FALLBACK_PROMPT;
            }

            log.info("已加载人设文件 {}，{} 字", location, content.length());
            return content;

        } catch (IOException e) {
            log.error("读取人设文件失败：{}，将使用兜底人设", location, e);
            return FALLBACK_PROMPT;
        }
    }

    /**
     * 清空缓存，让下次读取重新从文件加载。
     *
     * <p>目前没有地方调用它，保留是为了将来做"管理员发命令让人设热更新"的功能。
     * 那时调这个方法 + 重新 {@code load()}，就能在不重启的情况下换掉提示词内容。
     *
     * @param promptName 要清除的人设名；传 {@code null} 则清空全部
     */
    public void evictCache(String promptName) {
        if (promptName == null) {
            cache.clear();
            log.info("人设缓存已全部清空");
        } else {
            cache.remove(promptName);
            log.info("人设缓存已清空：{}", promptName);
        }
    }
}
