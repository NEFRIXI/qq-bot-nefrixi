package com.mybotweb.llm.ollama;

import com.mybotweb.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地模型选择器 —— 记住"现在用哪个模型"，支持运行中切换。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么要把这件事单独抽一个类？</b>
 *
 * <p>最省事的写法是在 {@code OllamaChatModelClient} 里放一个字段，
 * 切换时直接改它。但那样有两个问题：
 * <ul>
 *   <li>切换动作就藏在"调用模型"的类里，将来别的地方（比如命令、网站后台）
 *       想切模型，就得去依赖那个类，而它是个重量级的、管着 HTTP 客户端的类</li>
 *   <li>"当前用哪个模型"是个<b>状态</b>，将来要持久化到数据库（重启后不丢），
 *       状态和调用逻辑混在一起，迁移时就得动那个大类</li>
 * </ul>
 * 抽出来之后职责很清楚：<b>本类只管"是哪个"，{@code OllamaChatModelClient}
 * 只管"怎么调"</b>。切换只需改本类的一个字段，调用方每轮读一次最新值。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么用 {@code volatile} 而不是加锁？</b>
 *
 * <p>{@code currentModel} 是"要么是旧值、要么是新值"的单一字段——
 * 不需要保证"切换时别的线程看不到中间状态"，因为根本没有中间状态。
 * {@code volatile} 保证的是<b>可见性</b>（一个线程改了，别的线程立刻看到），
 * 这正是这里需要的；加 {@code synchronized} 是多余的。
 *
 * <p>对比一下：如果是"同时改多个字段"，就必须加锁了，
 * 否则别的线程可能看到"新模型名配着旧超时时间"这种半成品状态。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>将来可以往哪里长（预留的扩展方向）</b>
 *
 * <ol>
 *   <li><b>按场景自动选模型</b> —— 比如闲聊用 4B（快），
 *       让机器人"帮我分析一下这段代码"用 7B（聪明）。
 *       做法：把 {@link #getCurrentModel()} 换成
 *       {@code resolveModel(ChatScene scene)}，由调用方传入场景。
 *       本类的其余部分不用动。</li>
 *
 *   <li><b>和路由策略联动</b> —— 现在 {@code route/RouteStrategy} 决定的是
 *       "本地还是云端"。将来可以细化成"LOCAL_SMALL / LOCAL_LARGE"，
 *       让管理员用 {@code /strategy} 一次性决定算力和模型档位。</li>
 *
 *   <li><b>持久化选择</b> —— 把 {@code currentModel} 存到数据库
 *       （规格里的 {@code sys_route_config} 表可以顺带存），
 *       重启后保持上次的选择。改动只在构造器和 {@link #switchTo} 里，
 *       加一次读写即可。</li>
 *
 *   <li><b>效果对比</b> —— 记录每个模型的调用次数和平均耗时，
 *       供 {@code /status} 展示"7B 平均 8 秒，4B 平均 3 秒"，
 *       帮助决定该用哪个。</li>
 * </ol>
 *
 * <p><b>装配条件：始终装配</b>。
 *
 * <p>它原来和 {@code OllamaChatModelClient} 一样标着
 * {@code @ConditionalOnProperty(app.role = local)}，理由是"用云端模型时
 * 不该有个 Ollama 模型选择器"。但那个理由现在不成立了：两个算力客户端
 * 已经改成同时装配（因为云端要当路由器，需要保留"派给本地"这个选项），
 * 而 {@code OllamaChatModelClient} 的构造器需要注入本类——
 * 本类若被条件掉，本地客户端就创建不出来，整个应用启动失败。
 *
 * <p>同时装配不会带来副作用：本类构造器只读配置、记一行日志，
 * 没有任何网络请求，所以在云服务器上存在一个"当前模型 qwen2.5:7b"
 * 的选择器完全无害。真正决定用不用本地算力的是 {@code ModelRouter}。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class OllamaModelSelector {

    /** 配置引用，用来取可选清单 */
    private final AppProperties.Llm.Ollama config;

    /**
     * 当前使用的模型名。
     *
     * <p>初始值来自配置文件的 {@code app.llm.ollama.model}，
     * 之后可能被 {@link #switchTo(String)} 改掉。
     */
    private volatile String currentModel;

    public OllamaModelSelector(AppProperties appProperties) {
        this.config = appProperties.getLlm().getOllama();
        this.currentModel = this.config.getModel();

        if (this.currentModel == null || this.currentModel.isBlank()) {
            // 配置漏了也要能给出个明确信号，而不是等到调用时才报"模型名为空"
            log.warn("没有配置 app.llm.ollama.model，本地模型将无法使用。"
                    + "请用 ollama list 查看本机模型，然后填进配置。");
        } else {
            log.info("本地模型选择器已初始化，当前模型：{}", this.currentModel);
        }
    }

    /**
     * 取当前应当使用的模型名。
     *
     * <p>会被每条聊天消息调用一次，实现是纯内存读取，没有任何 IO。
     *
     * @return 模型名
     */
    public String getCurrentModel() {
        return this.currentModel;
    }

    /**
     * 切换到指定模型。
     *
     * <p>只改内存里的状态，<b>不会去校验模型是否真的存在</b>——
     * 真实校验在下一次调用时由 Ollama 完成（或者由 {@code /status} 探测）。
     * 这样切换是瞬时的，不需要等一次网络往返。
     *
     * <p>但如果配置里写了 {@code available-models} 清单，会先按清单校验，
     * 打错字能立刻被拒绝。
     *
     * @param modelName 目标模型名
     * @return 切换成功返回 {@code true}；目标不在清单里返回 {@code false}
     */
    public boolean switchTo(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String target = modelName.strip();

        List<String> allowed = this.config.getAvailableModels();
        // 配了清单就按清单校验；没配清单则放行任意名字
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(target)) {
            log.warn("拒绝切换到 '{}'：不在 available-models 清单里。可选：{}", target, allowed);
            return false;
        }

        String previous = this.currentModel;
        if (target.equals(previous)) {
            log.info("当前已经在使用模型 '{}'，无需切换", target);
            return true;
        }

        this.currentModel = target;
        log.info("本地模型已切换：{} → {}", previous, target);
        return true;
    }

    /**
     * 取可切换的模型清单。
     *
     * <p>用途是给 {@code /model} 命令展示。配置里没写清单时，
     * 会把"当前模型"作为唯一一项返回，避免命令输出一片空白。
     *
     * @return 模型名列表，<b>不会是 null</b>
     */
    public List<String> getSelectableModels() {
        List<String> allowed = this.config.getAvailableModels();
        if (allowed == null || allowed.isEmpty()) {
            return this.currentModel == null || this.currentModel.isBlank()
                    ? List.of()
                    : List.of(this.currentModel);
        }
        return List.copyOf(allowed);
    }

    /**
     * 配置里是否显式声明了可选清单。
     *
     * <p>用途：{@code /model} 命令据此决定提示语——
     * 有清单就说"可选：a、b、c"，没清单就说"可以切到任意已下载的模型"。
     *
     * @return 配置了清单返回 {@code true}
     */
    public boolean hasConfiguredModelList() {
        List<String> allowed = this.config.getAvailableModels();
        return allowed != null && !allowed.isEmpty();
    }
}
