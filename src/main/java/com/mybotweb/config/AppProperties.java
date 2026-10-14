package com.mybotweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用配置类 —— 把 {@code application.yml} 里 {@code app:} 开头的配置，
 * 整体映射成一个 Java 对象。
 *
 * <p><b>为什么不用 {@code @Value}？</b> 三个理由：
 * <ol>
 *   <li>{@code @Value("${app.qq.app-id}")} 要一条条写，配置项一多就散落在各个类里，
 *       想找"这个配置在哪儿被用了"只能全局搜索</li>
 *   <li>{@code @Value} 不校验类型，写错了要到运行时才发现</li>
 *   <li>配置类能被 IDE 识别，在 yml 里有自动补全和跳转</li>
 * </ol>
 *
 * <p><b>它是怎么生效的？</b> 启动类上的 {@code @ConfigurationPropertiesScan}
 * 会自动扫描本类，把它注册成 Bean。字段名与 yml 的对应规则是"松散绑定"：
 * Java 的 {@code appId} 能匹配 yml 里的 {@code app-id}、{@code appId}、{@code APP_ID}。
 *
 * <p>本类自身没有 {@code @Component}，因为注册工作由
 * {@code @ConfigurationPropertiesScan} 完成，加了反而重复。
 *
 * @author mybotweb
 */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 角色标记：{@code local} = 本机，{@code cloud} = 云服务器。对应规格第八节。 */
    private String role = "local";

    /** 消息通道配置 */
    private Channel channel = new Channel();

    /** QQ 开放平台凭证配置 */
    private Qq qq = new Qq();

    /** 大模型配置 */
    private Llm llm = new Llm();

    /** 聊天行为配置 */
    private Chat chat = new Chat();

    /** 管理员白名单配置 */
    private Admin admin = new Admin();

    /** 本地 Ollama 进程管理配置（供 /setlocal 命令使用） */
    private LocalOllama localOllama = new LocalOllama();

    /** 算力路由配置（决定"这次交给谁算"） */
    private Route route = new Route();

    /** 热聊配置（主动搭话模式） */
    private HotChat hotChat = new HotChat();

    // ========================================================
    // 下面是各个配置分组。用静态内部类表示"嵌套的一层 yml"。
    // 字段上都给了默认值，这样即使 yml 里漏配，程序也不会拿到 null 而空指针。
    // ========================================================

    /**
     * 消息通道配置，对应 yml 里的 {@code app.channel.*}
     */
    @Data
    public static class Channel {

        /**
         * 通道类型：
         * <ul>
         *   <li>{@code mock} —— 本地 HTTP 调试入口，不需要腾讯凭证</li>
         *   <li>{@code qq} —— 真实的腾讯 QQ WebSocket 网关</li>
         * </ul>
         * 两个实现类通过 {@code @ConditionalOnProperty} 读这个值来决定谁生效。
         */
        private String type = "mock";
    }

    /**
     * QQ 开放平台凭证，对应 yml 里的 {@code app.qq.*}
     */
    @Data
    public static class Qq {

        /** 机器人的 AppID，在 QQ 开放平台管理端可见。不算秘密。 */
        private String appId;

        /** 机器人的 AppSecret。<b>这是密码</b>，只写在 application-local.yml 里。 */
        private String appSecret;

        /** OpenAPI 基础地址。官方文档里的"统一地址"。 */
        private String baseUrl = "https://api.bot.qq.com";

        /**
         * 是否使用沙箱环境。开发阶段为 {@code true}。
         *
         * <p><b>注意：当前代码里还没有用到这个值。</b>
         * 官方文档给的是"统一地址" {@code https://api.bot.qq.com}，
         * 并没有区分沙箱域名和正式域名——沙箱的隔离是靠
         * "机器人只对沙箱成员响应"实现的，不是靠地址区分。
         * 保留这个字段是为了将来万一出现沙箱专用地址时能直接用上，
         * 免得为了加个开关再去改一遍配置类。
         */
        private boolean sandbox = true;

        /**
         * 事件订阅掩码（intents），告诉腾讯要接收哪些事件类型。
         *
         * <p><b>这个值配错的表现是"机器人连上了但永远不理你"</b>，
         * 而且不会报错，所以特别值得留意。取值是位掩码，按位或组合：
         * <ul>
         *   <li>{@code 1 << 25} = {@code 33554432} —— {@code GROUP_AND_C2C_EVENT}。
         *       包含 {@code C2C_MESSAGE_CREATE}（用户单聊发消息）、
         *       {@code GROUP_AT_MESSAGE_CREATE}（用户在群里@机器人）等。
         *       <b>我们要的就是这一类</b>。</li>
         *   <li>{@code 1 << 0} = {@code 1} —— {@code GUILDS}，频道相关，我们不需要</li>
         *   <li>{@code 1 << 9} = {@code 512} —— {@code GUILD_MESSAGES}，频道消息</li>
         *   <li>{@code 1 << 30} = {@code 1073741824} —— {@code PUBLIC_GUILD_MESSAGES}，频道@消息</li>
         * </ul>
         * 注意 {@code 513}（{@code 1 | 512}）看着眼熟，那是官方文档里"接收频道 at 消息"
         * 的示例值，<b>跟我们无关</b>——用它就会收不到任何单聊消息。
         */
        private int intents = 33554432;
    }

    /**
     * 大模型配置，对应 yml 里的 {@code app.llm.*}
     */
    @Data
    public static class Llm {

        /**
         * 本机模型服务（Ollama）配置
         *
         * <p><b>用哪个后端不由本类决定，也不由 {@code app.role} 决定</b> ——
         * 两个客户端是同时装配的，每次聊天由 {@code route/ModelRouter}
         * 按 {@code app.route.strategy} 加上本地算力是否可用现算。
         *
         * <p>这里曾经写着"由 {@code app.role} 二选一"，那个设计已经取消了。
         * 现在 {@code app.role} 只影响 {@code /status} 的显示和 mock 模式的装配，
         * <b>改它不会让聊天从云端切到本地</b>。
         *
         * <p>想强制用本地，改 {@code app.route.strategy}：{@code LOCAL_ONLY}
         * 表示"本地不可用就报错，绝不降级到云端"。
         */
        private Ollama ollama = new Ollama();

        /** 云端大模型配置（DeepSeek 等 OpenAI 兼容服务） */
        private Cloud cloud = new Cloud();

        /**
         * 本地 Ollama（跑在 WSL 里）的配置
         */
        @Data
        public static class Ollama {

            /** Ollama 的 HTTP 接口地址。WSL2 会把端口转发到 Windows 的 localhost。 */
            private String baseUrl = "http://localhost:11434";

            /**
             * 启动时默认使用的模型名。
             *
             * <p><b>这里必须和 {@code ollama list} 里的名字完全一致。</b>
             * 名字写错不会导致启动失败，但每次聊天都会失败——程序探测时会
             * 把本机实际有哪些模型列在日志里，照那个改就行。
             *
             * <p>运行时可以用 {@code /model 模型名} 命令切换（仅管理员），
             * 所以这里填的只是"开机默认用哪个"。
             */
            private String model = "qwen2.5:7b";

            /**
             * 可切换的模型清单，供 {@code /model} 命令展示和校验。
             *
             * <p><b>怎么用</b>：把本机已经下载好的模型都列在这里，例如
             * <pre>{@code
             * app:
             *   llm:
             *     ollama:
             *       model: qwen2.5:7b
             *       available-models:
             *         - qwen2.5:7b
             *         - qwen2.5:3b
             *         - qwen3:4b
             * }</pre>
             *
             * <p><b>配了和没配的区别</b>：
             * <ul>
             *   <li><b>配了</b> —— {@code /model} 切换时只允许清单内的名字，
             *       打错字会被拒绝并提示候选列表。适合记不住模型名的情况。</li>
             *   <li><b>没配（默认空）</b> —— 允许切换到任意名字，Ollama 那边
             *       找不到就会报错。更灵活但更容易打错。</li>
             * </ul>
             * 建议配上：多打几个字，换来的是一句"没有这个模型，可选的是 xxx"，
             * 而不是一次失败的调试。
             */
            private List<String> availableModels = new ArrayList<>();

            /**
             * 模型简写别名：<b>key 是简写，value 是完整模型名</b>。
             *
             * <p><b>为什么需要这个？</b> 本地模型的完整名字可能长得离谱，比如
             * {@code R4C3R/minicpm5-1b-fable5-heretic:q4_k_m}——
             * 这一串要在手机上打出来，基本不可能不出错。
             * 而这个项目的模型切换又是"聊天时随手切一下"的高频动作，
             * 每次都要打 40 个字符显然不合理。
             *
             * <p>配好之后：{@code /model} 列表里会同时显示完整名和简写，
             * 你可以用任意一种来切换。
             *
             * <p>例子：
             * <pre>{@code
             * app:
             *   llm:
             *     ollama:
             *       model-aliases:
             *         q7b: qwen2.5:7b
             *         m1b-r: R4C3R/minicpm5-1b-fable5-heretic:q4_k_m
             * }</pre>
             *
             * <p>用 {@link java.util.LinkedHashMap} 而不是普通 HashMap，
             * 是为了让 {@code /model} 列出来的别名顺序和你在配置里写的顺序一致——
             * 顺序稳定，看着才不像随机排列的。
             */
            private Map<String, String> modelAliases = new LinkedHashMap<>();

            /** 单次请求超时秒数。本地小模型出字慢，给宽松些。 */
            private int timeoutSeconds = 120;
        }

        /**
         * 云端大模型配置（OpenAI 兼容接口）
         */
        @Data
        public static class Cloud {

            /** 接口基础地址，例如 {@code https://api.deepseek.com}。 */
            private String baseUrl;

            /** 云端大模型的 API Key，属于密钥，填在 application-local.yml。 */
            private String apiKey;

            /** 模型名，例如 {@code deepseek-flash}。 */
            private String model;

            /** 单次请求超时秒数。 */
            private int timeoutSeconds = 60;
        }
    }

    /**
     * 聊天行为配置，对应 yml 里的 {@code app.chat.*}
     */
    @Data
    public static class Chat {

        /**
         * 纯聊天模式下携带的历史轮数（一轮 = 用户一条 + 机器人一条）。
         * 对应规格第 6.5 节：纯聊天用滑动窗口，保留最近若干轮。
         */
        private int historyRounds = 10;

        /**
         * 同一用户两次发消息之间的最小间隔（毫秒），用来限流。
         * 对应规格第十三节：每个 QQ 用户限流，防止刷爆大模型。
         */
        private long minIntervalMillis = 1000;

        /**
         * 普通对话模式的<b>场合规则</b>文件（不带 {@code .md} 后缀）。
         *
         * <p>默认 {@code chat_normal}，对应 {@code SysPrompt/chat_normal.md}。
         *
         * <p>═══════════════════════════════════════════════════════════
         * <p><b>提示词现在分成两层，这是刻意分开的</b>
         *
         * <pre>
         *   最终 System Prompt
         *     = 人设（谁）      ← /model-p 决定，或按身份自动选（chat_default / chat_admin / kong …）
         *     + 场合规则（怎么做） ← 由当前模式决定：
         *                           普通对话模式 → 本字段（chat_normal）
         *                           热聊模式     → app.hot-chat.prompt-file（chat_hot）
         * </pre>
         *
         * <p><b>为什么要拆</b>：这两件事的变化频率完全不同。
         * <ul>
         *   <li><b>人设</b>是内容创作，你会为不同角色写不同的 cos 文件，改起来频繁</li>
         *   <li><b>场合规则</b>是行为约束，跟角色无关——孙悟空和猫娘在群里插话的
         *       时机判断是同一套逻辑</li>
         * </ul>
         * 混在一个文件里的话，每加一个角色都要把那些行为约束复制一遍，
         * 改一次规则要改 N 个文件，而且迟早会改漏。
         *
         * <p><b>换角色时不用碰这里</b>：用 {@code /model-p kong} 换人设，
         * 场合规则自动跟着当前模式走。
         */
        private String normalPromptFile = "chat_normal";
    }

    /**
     * 管理员白名单配置，对应 yml 里的 {@code app.admin.*}
     *
     * <p>对应规格第 4.1 节"管理员：数据库存 QQ 白名单"。
     * 本阶段先把名单放配置里，等阶段五建好数据库后，
     * 换成从表里查即可——权限判断收敛在 {@code AdminWhitelist} 一个类里，
     * 替换存储不会影响其他代码。
     */
    @Data
    public static class Admin {

        /**
         * 管理员用户标识列表（腾讯的 openid，不是 QQ 号）。
         *
         * <p>怎么拿到自己的 openid：在 QQ 里发 {@code /whoami}，
         * 它会把标识单独列成一行。
         *
         * <p>写在配置里的名单需要<b>重启</b>才生效。
         * 运行期通过 {@code /claim} 认领或自动认领产生的管理员会写进
         * {@code data/admin-ids.txt}，那份是立即生效且重启不丢的。
         */
        private List<String> userIds = new ArrayList<>();

        /**
         * 管理员名单为空时，是否自动把<b>第一个发消息的用户</b>设为管理员。
         *
         * <p><b>默认关闭。</b> 原因是这个机制的概念太松：它依赖
         * "名单为空时第一个说话的人一定是主人"这个假设，而这个假设
         * 在机器人公开的那一刻就不成立了——第一个陌生人会被静默设成管理员。
         *
         * <p><b>现在推荐的做法是显式的</b>：名单为空时，你在 QQ 里发
         * {@code /admin} 把自己设为管理员。同样是零配置，但
         * "谁发的这条命令"是你亲眼确认的，而不是程序替你猜的。
         *
         * <p>如果你确实想要全自动（比如懒得多打一条命令），把它设成
         * {@code true} 即可，但请记住上面那个风险。
         */
        private boolean autoClaimFirstUser = false;

        /**
         * 管理员名单的存储路径。<b>留空则用默认值</b> {@code data/admin-ids.txt}。
         *
         * <p><b>为什么需要它可以配置？</b> 因为踩过一个很实在的坑：
         * 开发时经常需要临时起一个测试实例（换个端口跑），
         * 而它和正式实例的<b>工作目录相同</b>，于是两边会操作同一个
         * 名单文件——测试实例往里面写测试 ID，正式实例重启后就读到了，
         * 结果是"名单里有一个管理员，但不是我"，人被锁在外面。
         *
         * <p>所以测试时用这个配置把路径指到别处，两边的数据就隔离开了：
         * <pre>{@code
         * # 测试实例用
         * app:
         *   admin:
         *     storage-path: data-test/admin-ids.txt
         * }</pre>
         *
         * <p>相对路径按项目根目录解析；也可以写绝对路径。
         */
        private String storagePath = "";
    }

    /**
     * 本地 Ollama 的进程管理配置，对应 yml 里的 {@code app.local-ollama.*}
     *
     * <p><b>为什么这部分配置单独拎出来？</b> 因为它管的是"进程"而不是"对话"：
     * {@code app.llm.ollama} 那边管的是"用哪个模型、超时多久"，
     * 本类管的是"那个跑模型的进程没了怎么办"。
     *
     * <p>它的消费者有<b>两个</b>：
     * <ul>
     *   <li>{@code /setlocal} 命令 —— 用户手动拉起</li>
     *   <li>{@code LocalModelSupervisor} —— 自动拉起：本地算力不可用时后台重试</li>
     * </ul>
     * 两者都只在本机（Windows + WSL 跑模型）的场景下才有意义 ——
     * 云服务器上没有本机模型服务，那边执行会明确报"当前环境不支持"。
     */
    @Data
    public static class LocalOllama {

        /**
         * 是否允许 {@code /setlocal} 真的去重启进程。
         *
         * <p><b>关掉之后那条命令降级为"只查看状态"</b>：仍然会探测
         * {@code /api/tags} 报告 Ollama 是否在线、有哪些模型，但不会杀进程、
         * 也不会起新进程。
         *
         * <p>什么时候该关：把机器人交给别人用、又不想让任何人的一次误触
         * 就把你的本地模型进程重启掉的时候。
         */
        private boolean manageEnabled = true;

        /**
         * WSL 发行版名称。
         *
         * <p>本机可能有多个发行版（{@code wsl -l -v} 可以列出），
         * 必须指定清楚是哪一个，否则 {@code wsl} 命令会用默认发行版——
         * 而默认发行版里不一定装了 Ollama，表现为"命令执行成功但服务没起来"。
         */
        private String wslDistro = "Ubuntu-20.04";

        /**
         * 在 WSL 里执行的重启命令。
         *
         * <p><b>{@code OLLAMA_HOST=0.0.0.0:11434} 这一段不能省。</b>
         * Ollama 默认只绑定 {@code 127.0.0.1}，而 WSL2 的端口转发
         * 只对"非回环绑定"生效——绑了回环，Windows 侧通过
         * {@code localhost:11434} 就访问不到，表现正是"服务明明起来了但连不上"。
         *
         * <p>命令里先 {@code pkill} 再重启，所以它是"重启"而不是"启动"：
         * 已经在跑的时候执行它也不会报端口被占用。
         */
        private String restartCommand =
                "export OLLAMA_HOST=0.0.0.0:11434; "
                        + "pkill -f 'ollama serve'; sleep 1; "
                        + "nohup ollama serve > /tmp/ollama.log 2>&1 & sleep 3; echo started";

        /**
         * 重启后等多久（秒）再去验证端口是否恢复。
         *
         * <p>Ollama 启动要加载模型索引，给它一点时间。太短会误判成"重启失败"，
         * 太长会让用户在 QQ 里干等。
         */
        private int verifyWaitSeconds = 5;

        /**
         * 发现本地模型不可用时，是否<b>自动尝试把它拉起来</b>。
         *
         * <p><b>默认开启，因为它解决的问题很实在</b>：本机 Ollama 跑在 WSL 里，
         * 而 WSL 不会一直在跑——电脑重启、睡眠唤醒、手动关掉之后它就不在了。
         * 这时如果只能靠 {@code /setlocal} 手动拉，结果就是
         * "机器人突然开始花钱调 API，而你不知道为什么"。
         *
         * <p>开启后的行为：路由判断发现本地不可用 → <b>后台异步</b>尝试拉起
         * （当前这条消息不等待，它照常走云端兜底）→ 拉起来之后，
         * 下一次请求就自动走本地了，全程不需要手动操作。
         *
         * <p><b>为什么可以放心默认开</b>：
         * <ul>
         *   <li>不会重复拉起 —— 有冷却时间 + "启动中"互斥标记</li>
         *   <li>不拖慢消息响应 —— 启动在后台线程里做</li>
         *   <li>只在 Windows 上生效 —— 云服务器（Linux）没有 WSL，
         *       这个开关在那边是空操作</li>
         *   <li>{@link #manageEnabled} 为 {@code false} 时它也不会动</li>
         * </ul>
         *
         * <p>想完全手动控制（绝不自动起服务）就设成 {@code false}。
         */
        private boolean autoStart = true;

        /**
         * 自动拉起的冷却时间（秒）：两次尝试启动之间至少隔多久。默认 60 秒。
         *
         * <p><b>为什么必须有这个</b>：没有冷却的话，本地一旦拉不起来
         * （比如 {@code ollama} 不在 WSL 的 PATH 上、发行版名写错了），
         * <b>每条消息都会触发一次启动尝试</b>。起一个进程要几百毫秒到几秒，
         * 消息一多就会把线程池占满，反而让整个机器人变慢——
         * 本来只是想省点 API 钱，结果把响应也拖垮了。
         *
         * <p>60 秒是折中：既不频繁重试，又能让"我手动修好了问题"
         * 在一分钟内自动恢复，不用重启应用。
         */
        private int autoStartCooldownSeconds = 60;
    }

    /**
     * 算力路由配置，对应 yml 里的 {@code app.route.*}
     *
     * <p><b>它管的是"这次交给谁算"</b>，和 {@code app.role} 是两件事，别混：
     * <ul>
     *   <li>{@code app.role} —— <b>启动时</b>决定"这台机器的主算力是谁"，
     *       是静态的部署事实（云服务器没有 Ollama，本机有）</li>
     *   <li>{@code app.route.strategy} —— <b>运行期</b>决定"这一条消息用谁"，
     *       是可以随时改的策略</li>
     * </ul>
     *
     * <p>举例：云服务器上 {@code role: cloud}（它自己只会调 API），
     * 但 {@code strategy: LOCAL_FIRST}（有本地节点在线时优先派给它算）——
     * 这两个配置组合起来才是"云端当路由器"的形态。
     */
    @Data
    public static class Route {

        /**
         * 当前路由策略，取值见 {@code com.mybotweb.route.RouteStrategy}。
         *
         * <p>用字符串而不是枚举，是为了让"配错了"表现为启动时报错，
         * 而不是 yml 解析阶段的诡异异常。真正解析成枚举在 {@code ModelRouter} 里做。
         */
        private String strategy = "LOCAL_FIRST";

        /**
         * 判定本地节点离线的静默时长（秒）。
         *
         * <p>取"心跳间隔的 3 倍"是这个场景的常规做法——留出两次丢包的容忍空间。
         * 太灵敏会把网络抖动误判成离线（消息被甩去云端，白花钱），
         * 太迟钝则本地真挂了还往本地派（用户一直等不到回复）。
         */
        private long localNodeTimeoutSeconds = 45;

        /**
         * 本地节点心跳间隔（秒），供本地端实现时对齐。
         *
         * <p>放在云端配置里是因为两边必须一致：本地按这个间隔发，
         * 云端按 {@link #localNodeTimeoutSeconds} 判超时。
         */
        private int heartbeatIntervalSeconds = 15;
    }

    /**
     * 热聊配置，对应 yml 里的 {@code app.hot-chat.*}
     *
     * <p><b>它管的是"主动搭话"模式</b>：开启后机器人不再等人问它，
     * 而是看懂群里几个人在聊什么，然后自己决定要不要插一句。
     *
     * <p>这套配置里最要紧的是那两个节流参数（{@link #minMessages} 和
     * {@link #minIntervalSeconds}）——热聊模式让 API 调用量跟着群活跃度走，
     * 群里一热闹就会频繁触发。没有节流的话，一个刷屏的群能在几小时内
     * 烧掉大量额度。所以这两个值不要随意调小。
     */
    @Data
    public static class HotChat {

        /**
         * 每个会话保留的上下文条数（滑动窗口大小）。
         *
         * <p><b>这是"动态缓冲"的上限</b>：消息一条条进来，缓冲从 0 涨到本值，
         * 之后就固定成"最近 N 条"——再新来的消息会把最老的挤出去。
         * 所以它同时是"内存储存上限"和"单次请求的上下文大小"。
         *
         * <p><b>为什么默认只有 3</b>：热聊的上下文只用来回答一个问题——
         * "大家在聊什么、我要不要接一句"。3 条足够看清话题走向，
         * 而每多一条都会让每次搭话的 token 和内存一起涨。
         * 之前这里设成 20 是"看得更全"的思路，实测下来纯属浪费。
         *
         * <p>真觉得上下文不够（比如话题绕得比较久）可以调到 5~10，
         * 但要知道代价是线性的：调到 10 就是每次都多带 7 条消息去调模型。
         */
        private int maxMessages = 3;

        /**
         * 距上次搭话之后，群里至少再积累多少条消息才允许再次搭话。
         *
         * <p>这是防"话痨"的第一道闸：群友只说了半句话时，
         * 机器人不该抢着接。默认 3 条，意思是"等话题稍微展开一点"。
         *
         * <p><b>它和 {@link #maxMessages} 的关系</b>：这个决定"攒够几条才考虑说话"，
         * 那个决定"考虑时能看几条上下文"。两者默认都是 3，
         * 合起来就是"群里聊满 3 句，机器人读这 3 句，决定接不接"。
         */
        private int minMessages = 3;

        /**
         * 两次搭话之间的最小间隔（秒）。
         *
         * <p>这是第二道闸：即使消息条数够了，距上次发言不足这个时间也不搭话。
         * 群聊节奏快的时候，只有条数限制是拦不住的——
         * 十几秒内就能攒够三条，机器人会显得在刷屏。
         */
        private int minIntervalSeconds = 60;

        /**
         * 主动搭话时附加的提示词文件名（不含 {@code .md}）。
         *
         * <p>这就是"拼两个提示词"里的第二段。完整规则：
         * <pre>
         *   最终 System Prompt = 人设（/model-p 选的那个或按身份自动选）
         *                      + 本文件的内容
         * </pre>
         * 两段各管一件事，这是刻意的分工：
         * <ul>
         *   <li><b>人设</b>管"机器人是谁"（语气、身份、性格）——
         *       用 {@code /model-p} 随时换</li>
         *   <li><b>本文件</b>管"现在这个场合该怎么说话"（看群聊上下文、
         *       什么时候插话、不插话时输出什么）</li>
         * </ul>
         * 所以换人设不用碰本文件，改搭话规则也不用碰人设文件。
         */
        private String promptFile = "chat_hot";

        /**
         * 机器人拒绝搭话时输出的标记。
         *
         * <p>让模型"决定不说话"只能靠约定一个暗号：提示词里告诉它
         * "不想接话就只输出这个标记"，程序看到标记就<b>不发送任何消息</b>。
         *
         * <p>为什么要这个机制：热聊模式下每次触发都会调一次模型，
         * 但并不是每次触发都值得发言（群友只是互相道个别的时候）。
         * 让模型自己判断"这次不用我说话"，比程序硬性规定
         * "每条都必须回"自然得多。
         *
         * <p><b>改这个值要同步改提示词文件里的说明</b>，两边必须一致，
         * 否则模型输出的暗号和程序比对的不一样，表现为"机器人对什么都沉默"。
         */
        private String silentToken = "[沉默]";

        /**
         * 上下文里代表机器人自己的称呼。
         *
         * <p>拼上下文时，机器人历史发言会被标成 {@code 【我】xxx}。
         * 不标的话，模型会把自己的话当成别人的，于是反复接自己刚说过的话题。
         */
        private String botSelfLabel = "我";

        /**
         * 是否只对群聊生效。
         *
         * <p>默认 {@code true}。单聊场景里"主动搭话"没有意义——
         * 单聊就是两个人对话，不存在"读懂多方发言再决定插不插嘴"这件事，
         * 开了反而会让机器人对用户没问的事也自顾自说话。
         */
        private boolean groupOnly = true;
    }
}
