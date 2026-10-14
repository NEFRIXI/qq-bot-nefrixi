package com.mybotweb.command;

import java.util.List;

/**
 * 全部斜杠命令的名字 —— <b>要改命令名，只改这一个文件</b>。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么要有这个类</b>
 *
 * <p>命令名本来是散在各处的：每个 {@code *Command} 类的 {@code getName()}
 * 写一份、{@code getUsage()} 再写一份，而十几条提示语里还会提到
 * "发 /xxx 可以……"。结果是想把 {@code c_hot} 改个名字，
 * 得在七八个文件里翻一遍，漏掉任何一处都会出现
 * "提示语教你发的命令根本不存在"这种坑。
 *
 * <p>现在命令名只在这里定义一次，其他地方一律引用常量。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>怎么改命令名</b>
 *
 * <p>只改下面常量<b>右边的字符串</b>，不用动常量名：
 *
 * <pre>{@code
 *   // 改前
 *   public static final String HOT_CHAT = "c_hot";
 *
 *   // 改后 —— 引用这个常量的 20 多处全部自动跟着变
 *   public static final String HOT_CHAT = "热聊";
 * }</pre>
 *
 * <p>常量名用的是<b>语义</b>（{@code HOT_CHAT} = 热聊这个功能），
 * 不是字面拼写 —— 所以改字面值的时候，代码里一行都不用跟着改。
 * 这也是为什么它叫 {@code HOT_CHAT} 而不是 {@code C_HOT}。
 *
 * <p>改完要<b>重启应用</b>：命令索引是启动时建立的。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>哪些地方常量管不到（改名字后要么手动同步、要么本来就不必改）</b>
 *
 * <ol>
 *   <li><b>{@code resources/SysPrompt/*.md} 里的引用</b> ——
 *       提示词是数据文件，用不了 Java 常量。比如 {@code chat_normal.md}
 *       里若写着"发 /help 看命令"，改名字后要手动改那一行</li>
 *   <li><b>Javadoc 注释里的引用</b>（{@code {@code /status}} 这种）——
 *       注释是编译期文本，同样用不了常量。它不影响运行，但会悄悄过时，
 *       而过时的注释比没有注释更误导人</li>
 *   <li><b>控制台日志里的引用</b>（{@code log.warn} 里那些）——
 *       日志靠 SLF4J 的 {@code {}} 占位符填参数，再把命令名也塞进去，
 *       会让本来就长的排查信息更难读。所以这里<b>有意保留字面量</b>：
 *       改名字后日志里可能还写着老名字，但它只影响排查时看的那一眼</li>
 * </ol>
 *
 * <p><b>改完名字怎么检查有没有漏</b>——在项目根目录跑一次：
 *
 * <pre>{@code
 *   # PowerShell
 *   Get-ChildItem -Recurse -Include *.java,*.md src |
 *       Select-String -Pattern '/旧名字'
 * }</pre>
 *
 * <p>命中结果怎么处理：命令类和提示语里应该是 0 处（都走常量了）；
 * 日志里的按需改；{@code SysPrompt/*.md} 里的必须改。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>别名的唯一性谁来保证</b>
 *
 * <p>{@code CommandRouter} 在启动时会把所有主名和别名建成一张索引，
 * 遇到重复<b>直接启动失败</b>并说明是谁和谁撞了。所以加别名时不用怕
 * 悄悄覆盖掉别的命令 —— 撞了会当场报出来。
 *
 * @see CommandHandler#getName()
 * @see CommandHandler#getAliases()
 * @see CommandRouter
 */
public final class CommandNames {

    /**
     * 纯常量类，不允许实例化。
     */
    private CommandNames() {
    }

    /**
     * 给命令名加上斜杠前缀，用在提示语里。
     *
     * <p>常量本身<b>不带</b>斜杠（因为 {@link CommandHandler#getName()}
     * 约定就是不带），所以拼提示语时用这个方法，不要自己写 {@code "/" + xxx}：
     *
     * <pre>{@code
     *   "想成为管理员？发送 "
     *       + CommandNames.withSlash(CommandNames.ADD_ADMIN)
     *       + " 申请，由现有管理员批准。"
     * }</pre>
     *
     * @param name 命令名（不带斜杠）
     * @return 形如 {@code /help}
     */
    public static String withSlash(String name) {
        return "/" + name;
    }

    // ================================================================
    // 普通用户命令（isAdminOnly() == false）
    // ================================================================

    /** 帮助 —— 列出命令，或看某条命令的详细用法。 */
    public static final String HELP = "help";

    /** {@link #HELP} 的别名：{@code /?} 和 {@code /h}。 */
    public static final List<String> HELP_ALIASES = List.of("?", "h");

    /** 我是谁 —— 把自己的用户标识（openid）告诉用户。 */
    public static final String WHO_AM_I = "whoami";

    /**
     * {@link #WHO_AM_I} 的别名。
     *
     * <p>刻意收得很宽：这是新用户最先需要的命令（不知道标识就没法申请管理员），
     * 而"我是谁"的英文拼法实在太多，漏字母的写法也常见。
     */
    public static final List<String> WHO_AM_I_ALIASES = List.of(
            "whomi",      // 最常见的漏字母写法
            "who",
            "me",
            "myid",
            "bot-me",
            "我是谁");

    /** 管理员申请 —— 普通用户向管理员要权限（需要管理员给验证码）。 */
    public static final String ADD_ADMIN = "add_admin";

    /**
     * {@link #ADD_ADMIN} 的别名。
     *
     * <p>{@code claim} 是它的老命令名，留着是为了不让已经形成肌肉记忆的人打不出来。
     */
    public static final List<String> ADD_ADMIN_ALIASES = List.of("申请", "apply", "claim");

    // ================================================================
    // 管理员命令（isAdminOnly() == true）
    // ================================================================

    /**
     * 系统状态 —— 看策略、本地算力、模型、通道、限流等。
     *
     * <p>没有别名，所以没有配套的 {@code STATUS_ALIASES} ——
     * {@link CommandHandler#getAliases()} 的默认实现就是返回空列表，不用覆写。
     */
    public static final String STATUS = "status";

    /** 本地模型 —— 查看和切换"当前用哪个本地模型"。 */
    public static final String MODEL = "model";

    /** {@link #MODEL} 的别名。 */
    public static final List<String> MODEL_ALIASES = List.of("models", "m", "模型");

    /** 人设 —— 查看和切换 System Prompt 文件。 */
    public static final String MODEL_PROMPT = "model-p";

    /**
     * {@link #MODEL_PROMPT} 的别名。
     *
     * <p>前两个是它合并掉的旧命令（原来是 {@code /model-prompt-list} 和
     * {@code /model-prompt-select} 两条），保留是为了兼容旧习惯。
     */
    public static final List<String> MODEL_PROMPT_ALIASES = List.of(
            "model-prompt-list",
            "model-prompt-select",
            "prompt",
            "人设");

    /** 热聊模式 —— 开关"机器人主动搭话"。 */
    public static final String HOT_CHAT = "c_hot";

    /**
     * {@link #HOT_CHAT} 的别名。
     *
     * <p>中文别名很有用：手机上打"热聊"比打 {@code c_hot} 快得多，
     * 而下划线还是很容易打错的那个字符。
     */
    public static final List<String> HOT_CHAT_ALIASES = List.of("热聊", "主动搭话", "hot");

    /** 拉起本地模型 —— 在本机把模型服务进程拉起来。 */
    public static final String SET_LOCAL = "setlocal";

    /**
     * {@link #SET_LOCAL} 的别名。
     *
     * <p>注意：这条命令只在 Windows + WSL 场景下有意义，
     * 云服务器上会明确回"当前环境不支持"。
     */
    public static final List<String> SET_LOCAL_ALIASES = List.of("拉起本地", "本地模型", "startlocal");

    /** 管理员管理 —— 初始化、增删管理员、查看名单。 */
    public static final String ADMIN = "admin";

    /** {@link #ADMIN} 的别名。 */
    public static final List<String> ADMIN_ALIASES = List.of("admins", "管理员");

    /** 管理员帮助 —— 管理员视角的完整命令列表（含只有管理员能用的）。 */
    public static final String HELP_ADMIN = "help_admin";

    /** {@link #HELP_ADMIN} 的别名。 */
    public static final List<String> HELP_ADMIN_ALIASES = List.of(
            "helpadmin", "admin_help", "管理员帮助");
}
