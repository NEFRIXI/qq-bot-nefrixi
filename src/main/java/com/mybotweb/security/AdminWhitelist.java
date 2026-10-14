package com.mybotweb.security;

import com.mybotweb.command.CommandNames;
import com.mybotweb.common.util.ProjectPaths;
import com.mybotweb.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 管理员白名单 —— 判断一个用户是不是管理员，并负责名单的增删与持久化。
 *
 * <p>对应规格第 4.1 节。名单有三个来源：
 * <ol>
 *   <li><b>配置文件</b>（{@code app.admin.user-ids}）—— 改完要重启，适合固定名单</li>
 *   <li><b>持久化文件</b>（{@code data/admin-ids.txt}）—— 由 {@code /claim}
 *       或自动认领写入，<b>立即生效且重启不丢</b></li>
 *   <li><b>自动认领</b> —— 名单全空时，第一个发消息的人自动成为管理员</li>
 * </ol>
 *
 * <p><b>为什么会搞出后两个来源？</b>
 *
 * <p>最初只有配置文件这一个来源，结果第一次部署就撞上了死结：
 * <pre>
 *   要使自己成为管理员 → 得知道自己的标识
 *   要知道标识         → 得先能问机器人（/whoami）
 *   要配白名单         → 得手动改文件 + 重启
 * </pre>
 * 这三步互相咬住，第一次用的人必然卡住。
 * 后来加了 {@code /whoami} 解决"不知道标识"，但"改文件 + 重启"仍然别扭。
 * 所以现在再加两条路：能自动认就自动认，认完存下来，
 * 全程不需要你碰配置文件。
 *
 * <p><b>安全边界</b>：自动认领只在<b>名单完全为空</b>时触发<b>一次</b>。
 * 一旦名单里有了人（不管来自哪个来源），这个机制就永久失效。
 * 所以它不是一个持续的漏洞，只是"首次部署的一次便利"。
 * 机器人要对外开放时，把 {@code app.admin.auto-claim-first-user} 关掉即可。
 *
 * @author mybotweb
 */
@Slf4j
@Component
public class AdminWhitelist {

    /** 持久化文件所在目录（相对项目根目录） */
    private static final String STORAGE_DIRECTORY = "data";

    /** 持久化文件名 */
    private static final String STORAGE_FILENAME = "admin-ids.txt";

    /** 应用配置，读固定名单和自动认领开关 */
    private final AppProperties appProperties;

    /** 持久化文件的绝对路径 */
    private final Path storageFile;

    /**
     * 当前生效的管理员集合。
     *
     * <p>用 {@code volatile} 是因为它会被整个替换（增删时都是"算出新集合再换上去"），
     * 保证别的线程要么看到旧的完整集合、要么看到新的完整集合，
     * 不会看到一个改了一半的集合。
     */
    private volatile Set<String> adminIds = Set.of();

    /**
     * 自动认领是否已经执行过。
     *
     * <p>用原子布尔量而不是普通字段，是为了防止几条消息同时到达时
     * 触发多次认领（那样会把好几个人都设成管理员）。
     * {@code compareAndSet(false, true)} 保证只有一个线程能把它从 false 翻成 true。
     */
    private final AtomicBoolean autoClaimDone = new AtomicBoolean(false);

    public AdminWhitelist(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.storageFile = resolveStorageFile(appProperties);
    }

    /**
     * 决定管理员名单存在哪个文件。
     *
     * <p>优先用配置里的 {@code app.admin.storage-path}；没配就用默认的
     * {@code data/admin-ids.txt}（相对项目根目录）。
     *
     * <p>相对路径按项目根目录解析，所以配置里写 {@code data-test/admin-ids.txt}
     * 也会落在项目下，不会跑到什么莫名其妙的地方去。
     *
     * @param appProperties 应用配置
     * @return 名单文件的绝对路径
     */
    private static Path resolveStorageFile(AppProperties appProperties) {
        String configured = appProperties.getAdmin().getStoragePath();

        if (configured != null && !configured.isBlank()) {
            Path path = Paths.get(configured.strip());
            // 相对路径挂到项目根目录下；绝对路径直接用
            return path.isAbsolute() ? path : Paths.get(ProjectPaths.projectRoot()).resolve(path);
        }

        return Paths.get(ProjectPaths.projectRoot(), STORAGE_DIRECTORY, STORAGE_FILENAME);
    }

    /**
     * 启动时加载名单。
     *
     * <p>用 {@code @PostConstruct} 是因为它要在依赖注入完成之后执行，
     * 那时 {@code appProperties} 才可用。
     *
     * <p>本方法由 Spring 自动调用，不需要手动执行。
     */
    @PostConstruct
    public void reload() {
        Set<String> loaded = new HashSet<>();

        // ---- 来源一：配置文件 ----
        for (String id : appProperties.getAdmin().getUserIds()) {
            if (id != null && !id.isBlank()) {
                // 统一 strip：配置文件里很容易手滑带上空格，
                // 而 openid 多一个空格就永远匹配不上，这种 bug 极难查
                loaded.add(id.strip());
            }
        }
        int fromConfig = loaded.size();

        // ---- 来源二：持久化文件 ----
        Set<String> fromStorage = readFromStorage();
        loaded.addAll(fromStorage);

        this.adminIds = Set.copyOf(loaded);

        if (this.adminIds.isEmpty()) {
            if (appProperties.getAdmin().isAutoClaimFirstUser()) {
                log.warn("""
                        
                        ================================================================
                        管理员白名单为空。
                        
                        自动认领已开启（app.admin.auto-claim-first-user = true）：
                        第一个给机器人发消息的人会被自动设为管理员。
                        
                        【风险提醒】如果这个机器人已经能被别人访问，
                        请立刻关掉这个开关 —— 否则第一个陌生人会成为管理员。
                        ================================================================""");
            } else {
                log.warn("""
                        
                        ================================================================
                        管理员白名单为空 —— 还没有任何管理员。
                        
                        请在 QQ 里给机器人发送：
                        
                            /admin
                        
                        那会把发命令的人设为第一个管理员。
                        
                        【这个初始化只在名单为空时有效】，一旦有了管理员就永久关闭。
                        所以请确认那条 /admin 是你自己发的。
                        ================================================================""");
            }
        } else {
            log.info("管理员白名单已加载，共 {} 人（配置文件 {} 人，持久化文件 {} 人）",
                    this.adminIds.size(), fromConfig, fromStorage.size());
        }
    }

    /**
     * 判断某个用户是不是管理员。
     *
     * <p>这个方法会被每条消息调用一次，所以实现上只做一次集合查找，不做任何 IO。
     *
     * @param userId 用户标识（腾讯的 openid）
     * @return 是管理员返回 {@code true}
     */
    public boolean isAdmin(String userId) {
        if (userId == null || userId.isBlank()) {
            // 拿不到用户标识时一律按普通用户处理。
            // 这是"默认拒绝"原则：宁可把管理员误判成普通用户（他能再发一次），
            // 也不能把普通用户误判成管理员（那是越权）
            return false;
        }
        return this.adminIds.contains(userId.strip());
    }

    /**
     * 尝试自动认领管理员身份。
     *
     * <p>调用时机：消息入口处，处理每条消息之前。内部会快速判断是否该触发。
     *
     * <p>触发条件是三条同时成立：配置开关打开、名单为空、还没认领过。
     * 任何一条不成立都直接返回 {@code false}，开销只到一次布尔判断。
     *
     * @param userId 请求认领的用户标识
     * @return 本次真的认领成功返回 {@code true}
     */
    public boolean tryAutoClaim(String userId) {
        if (!appProperties.getAdmin().isAutoClaimFirstUser()) {
            return false;
        }
        if (userId == null || userId.isBlank()) {
            return false;
        }
        // 名单里已经有人了，这个机制就永久失效
        if (!this.adminIds.isEmpty()) {
            return false;
        }
        // 保证只认领一次：多条消息同时到达时，只有一条能通过这里
        if (!this.autoClaimDone.compareAndSet(false, true)) {
            return false;
        }

        addAdmin(userId, "自动认领（名单为空时的第一个用户）");

        log.warn("""
                
                ================================================================
                已自动认领管理员身份
                ----------------------------------------------------------------
                用户标识：{}
                原因    ：管理员名单为空，这是第一个与机器人对话的用户
                
                这条记录已经写入：
                  {}
                重启后依然有效，你不需要再改任何配置文件。
                
                现在可以试试：/status  /model  /help
                
                【注意】如果这个机器人以后要给别人用，请把配置项
                app.admin.auto-claim-first-user 改成 false，
                并把 data/admin-ids.txt 里的标识核对一遍。
                ================================================================""",
                userId, this.storageFile);

        return true;
    }

    /**
     * 按用户标识构造 {@link UserContext}，并在必要时触发自动认领。
     *
     * <p>把"判断权限"和"自动认领"都收在这一个方法里，
     * 调用方（消息入口）只需要调它一次，不需要知道内部有哪些机制。
     *
     * @param userId 用户标识
     * @return 带权限信息的用户上下文
     */
    public UserContext buildContext(String userId) {
        // 先尝试自动认领，再判断身份 —— 顺序不能反，
        // 否则第一个用户这一次仍然会被当成普通用户（要等第二条消息才生效）
        tryAutoClaim(userId);

        return isAdmin(userId) ? UserContext.ofAdmin(userId) : UserContext.ofNormalUser(userId);
    }

    /**
     * 添加一个管理员，并写入持久化文件。
     *
     * <p>用 {@code synchronized} 是因为它同时改内存和文件：
     * 如果两个请求同时进来，不加锁可能出现"文件里写了两遍、
     * 或者后写的把先写的覆盖掉"。
     *
     * @param userId 要添加的用户标识
     * @param reason 添加原因，只用于日志（比如"通过 /claim 认领"）
     * @return 本次确实新增了返回 {@code true}；已经在名单里返回 {@code false}
     */
    public synchronized boolean addAdmin(String userId, String reason) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        String id = userId.strip();
        if (this.adminIds.contains(id)) {
            return false;
        }

        Set<String> updated = new HashSet<>(this.adminIds);
        updated.add(id);
        this.adminIds = Set.copyOf(updated);
        writeToStorage(updated);

        log.info("新增管理员 {}（原因：{}），当前共 {} 人", id, reason, updated.size());
        return true;
    }

    /**
     * 移除一个管理员，并同步到持久化文件。
     *
     * @param userId 要移除的用户标识
     * @return 本次确实移除了返回 {@code true}
     */
    public synchronized boolean removeAdmin(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        String id = userId.strip();
        if (!this.adminIds.contains(id)) {
            return false;
        }

        Set<String> updated = new HashSet<>(this.adminIds);
        updated.remove(id);
        this.adminIds = Set.copyOf(updated);
        writeToStorage(updated);

        log.info("移除管理员 {}，当前共 {} 人", id, updated.size());
        return true;
    }

    /**
     * 当前名单人数，供 {@code /status} 和 {@code /whoami} 展示。
     *
     * @return 管理员人数
     */
    public int size() {
        return this.adminIds.size();
    }

    /**
     * 取当前所有管理员的标识。
     *
     * <p>用途：有人申请管理员权限时，要<b>逐个私聊通知</b>所有管理员。
     * 返回的是不可变集合，调用方拿到后不能改动——否则可能在遍历通知的
     * 过程中把名单改了，那是很难理解的一类并发问题。
     *
     * @return 管理员标识集合（不可变），<b>不会是 null</b>
     */
    public Set<String> getAllAdminIds() {
        return this.adminIds;
    }

    /**
     * 取持久化文件的路径，供提示语展示"认领结果存在哪"。
     *
     * @return 文件绝对路径
     */
    public String getStorageFilePath() {
        return this.storageFile.toString();
    }

    /**
     * 从持久化文件读名单。
     *
     * <p>文件格式很简单，每行一个标识，{@code #} 开头的是注释。
     * 这样你想手动编辑也方便——打开就是人可读的，不用猜格式。
     *
     * @return 文件里的标识集合；文件不存在或读失败时返回空集合
     */
    private Set<String> readFromStorage() {
        if (!Files.exists(this.storageFile)) {
            return Set.of();
        }
        try {
            return Files.readAllLines(this.storageFile, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            // 读不出来不是致命问题：配置里的名单仍然生效。
            // 但要报 ERROR，因为很可能意味着文件权限有问题
            log.error("读取管理员名单文件失败：{}", this.storageFile, e);
            return Set.of();
        }
    }

    /**
     * 把名单写回持久化文件。
     *
     * <p>每次增删都整体重写。名单只有几个人，重写的开销可以忽略，
     * 换来的是"文件内容永远和内存一致"——比增量修改简单得多，也不会写乱。
     *
     * <p>写入时自带注释头，方便你打开文件时知道这是什么。
     *
     * @param ids 要写入的标识集合
     */
    private void writeToStorage(Set<String> ids) {
        try {
            Path parent = this.storageFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            List<String> lines = new ArrayList<>();
            lines.add("# 管理员标识（腾讯 openid，不是 QQ 号），每行一个");
            lines.add("# 本文件由程序自动维护，也可以手动编辑（改完需重启应用）");
            lines.add("# 自动认领或 " + CommandNames.withSlash(CommandNames.ADD_ADMIN)
                    + " 认领都会写到这里，重启不丢");
            lines.addAll(ids.stream().sorted().toList());

            Files.write(this.storageFile, lines, StandardCharsets.UTF_8);
            log.info("管理员名单已写入：{}", this.storageFile);

        } catch (IOException e) {
            // 写失败不影响本次生效（内存里已经改了），但重启后会丢，
            // 所以必须报 ERROR 让人知道
            log.error("写入管理员名单文件失败，本次修改重启后会丢失：{}", this.storageFile, e);
        }
    }
}
