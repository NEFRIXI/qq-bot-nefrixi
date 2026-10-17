# mybotweb

QQ 机器人（对接腾讯 QQ 开放平台）+ 博客网站，跑在同一个 Spring Boot 进程里。

> **四份文档的分工**
>
> | 文档 | 管什么 |
> |---|---|
> | **本文档** | **怎么把它跑起来**：配置凭证、启动命令、测试、排错 |
> | [`项目说明.md`](项目说明.md) | 代码怎么分布、想改东西该动哪个文件、为什么这么设计 |
> | [`项目数据流详解.md`](项目数据流详解.md) | 一条消息 / 一个请求从进门到出口经过哪几站 |
> | [`开发进度.md`](开发进度.md) | 当前状态快照、踩过的坑、当初为什么这么定 |

**当前状态：QQ 机器人全链路已跑通，博客后端和前端已完成，已部署到云服务器。**

---

## 一、现在能做什么

| 能力 | 状态 |
|---|---|
| 在 QQ 里和机器人对话（单聊 / 群里 @） | ✅ 已跑通 |
| 斜杠命令（10 条命令、39 个名称） | ✅ 可用，**不消耗大模型** |
| 算力路由（本地 / 云端自动选 + 降级告知） | ✅ 已实现 |
| 本地模型自动拉起（`LocalModelSupervisor`） | ✅ 已实现 |
| 热聊模式（机器人主动搭话） | ✅ 已实现，⬜ 默认关闭，发 `/c_hot on` 开启 |
| **博客前台**（欢迎页 / 文章列表 / 详情 / 点赞 / 浏览量） | ✅ 已完成 |
| **博客后台**（登录 / 文章 CRUD / 访问统计 / 服务器状态） | ✅ 已完成 |
| **数据库**（MySQL + Flyway 建表、Redis 计数） | ✅ 已完成 |
| 本地节点接入端点 | ⬜ 未实现（全项目唯一的空壳） |
| 图片上传（OSS） | ⬜ 未做，正文现在只能用外链图片 |

详细的能力清单和设计理由见 `项目说明.md` §一 和 §五。

---

## 二、快速开始（当前环境已配好，直接跑）

### 2.1 本地：只跑机器人（最常见）

```powershell
chcp 65001                 # 不切的话中文日志是乱码
mvn -o spring-boot:run
```

**博客功能默认是关的**（`application-local.yml:55` 里 `app.blog.enabled: false`），
所以这样启动**不会连数据库**，MySQL 没开也能起。

### 2.2 本地：带博客一起跑

```powershell
mvn -o spring-boot:run "-Dspring-boot.run.arguments=--app.blog.enabled=true"
```

然后打开浏览器：

| 地址 | 是什么 |
|---|---|
| `http://127.0.0.1:8080/client` | 欢迎页（右上角有"登录"入口） |
| `http://127.0.0.1:8080/client/main` | 文章列表 |
| `http://127.0.0.1:8080/admin/login` | 后台登录 |

**管理员账号密码在 `application-local.yml` 的 `app.blog.admin` 里。**

> **首次启动**时，如果 `admin_user` 表是空的，会用这组账号密码建出第一个管理员
> （密码 BCrypt 哈希后存库，库里没有明文）。表里已经有记录就不再动 ——
> **所以改了配置里的密码不会影响已经建好的账号。**

### 2.3 服务器上跑

服务器上已经有 systemd 服务在管。改完 jar 之后：

```bash
# 在服务器上
sudo systemctl restart mybotweb
sudo systemctl status mybotweb
tail -f /home/nefrix-r/botweb/run.log
```

**启动参数里的 profile 顺序不能反**（写在 `ExecStart` 里）：

```
--spring.profiles.active=local,prod
--spring.config.additional-location=file:/home/nefrix-r/botweb/config/
```

Spring Boot 同时激活多个 profile 是**后者覆盖前者**。jar 里带着一份完整的
`application-local.yml`（含**本地**的数据库配置），必须写 `local,prod` 让
`application-prod.yml` 的服务器配置赢。**反过来写会去连本地的库，直接起不来。**

⚠️ 服务器上那台机器人的 AppID 是 `1905770351`（`bot_or_web`），本地是
`1905783650`（`webbot`）—— **两台不同的机器人，传 jar 时别搞混**。

---

## 三、从零配置的三件事（当前已完成，留作换机器时参考）

### 第 1 件：把 AppSecret 填进去

打开 `src/main/resources/application-local.yml`，找到：

```yaml
    app-secret: ""        # ← 填这里
```

去 QQ 开放平台 → 你的机器人 → **开发管理** → 复制 **AppSecret**，粘贴进去。

> 这个文件已被 `.gitignore` 忽略，不会提交到 git。
>
> **`application.yml`（会提交的那份）里现在是模板**：`api-key: ${LLM_API_KEY:}`、
> `app-secret: ${QQ_APP_SECRET:}` 这种写法，真值只在 local / prod 两份里。
> 这个坑之前踩过 —— 详见 `项目说明.md` §七 最后一段。

### 第 2 件：在 WSL 里启动 Ollama

> ✅ **把 Ollama 跑起来，聊天就会走本地** —— 这才是"本地优先"的本意。
>
> 不启动也行：那时会自动降级到云端 DeepSeek（**回复末尾会明说**，免得你以为在花
> 免费的本机算力却在付 API 的钱），同时程序会**自动尝试拉起**它
> （`LocalModelSupervisor`，带 60 秒冷却）。

Windows 上的程序要通过 `localhost:11434` 访问 WSL 里的 Ollama。Ollama 默认只绑
`127.0.0.1`，跨出 WSL 边界就访问不到，所以必须显式指定绑定地址。

在 WSL 终端里：

```bash
export OLLAMA_HOST=0.0.0.0:11434
ollama serve
```

另开一个 WSL 终端确认模型已下载：

```bash
ollama list
```

> **输出里的名字必须和配置一字不差。** 改 `app.llm.ollama.model` 成实际名字。
> 名字写错不会导致启动失败，但每次聊天都会失败。好在探测时会把本机实际有哪些列出来：
> `本地 Ollama 在运行，但没有模型 'xxx'。本机已下载的模型：[...]`

**换模型不用改配置重启**（仅管理员）：

```
/model              → 看当前用的哪个、有哪些可以切
/model qwen2.5:3b   → 切过去，下一条消息就生效
```

#### 关于 7B / 4B 的选择

| | 7B（如 `qwen2.5:7b`） | 4B / 3B |
|---|---|---|
| 效果 | 明显更好，能应付稍复杂的问题 | 闲聊够用，稍复杂开始胡说 |
| 速度 | 慢，吃显存（约 5-6GB） | 快，省显存（约 3GB） |
| 适合 | 问答、需要一点推理 | 纯闲聊、机器同时在跑别的程序 |

**建议先用 7B 跑顺**，等确实觉得慢再拉小的对比。用 `/model` 切着聊两句 ——
这正是把它做成命令而不是配置项的意义。

### 第 3 件：把机器人加成好友（沙箱）

开发阶段的机器人**只对沙箱成员响应**。QQ 开放平台 → 你的机器人 →
**开发 → 沙箱配置 → 消息列表配置**，加上你自己的 QQ 号，然后扫码加好友。

不做这一步，你在 QQ 里发消息它不会有任何反应。

---

## 四、怎么跑起来

### 方式一：IDEA（推荐，中文日志不会乱码）

1. 用 IDEA 打开 `D:\ds-work\my_web_project`
2. 等 Maven 索引完成
3. 运行 `MybotwebApplication` 的 `main` 方法

**要带博客功能的话**，在 Run Configuration 的 "Program arguments" 里加：

```
--app.blog.enabled=true
```

### 方式二：命令行

```powershell
chcp 65001                 # 先切控制台到 UTF-8
mvn -o spring-boot:run
```

> `-o` 是离线模式。本机 Maven 仓库依赖齐全，离线跑更快；
> 加了新依赖要去掉 `-o` 让它下载。

### 启动成功的标志

```
Tomcat started on port 8080 (http)
命令路由初始化完成，共注册 10 个命令，索引 39 个名称：...
算力路由器已就绪 / 当前策略：LOCAL_FIRST / 本地节点：离线（未接入或未上报心跳）
鉴权成功，机器人在线！机器人昵称：xxx
```

**逐行看这一眼**：

- 「命令路由初始化完成」后面如果是 `共注册 0 个命令` → `command/impl/` 下的类漏了 `@Component`
- 「算力路由器已就绪」那行的**「本地节点：离线」不能当结论** —— 真正的判据还包括"本机模型服务能不能连上"。要知道这次走谁，看聊天时日志里的 `路由决定：…` 才准
- 「鉴权成功，机器人在线」才说明 QQ 通道通了

**开了博客的话还会看到**：

```
Flyway：Migrating schema `mybotweb` to version "1 - create article"
Flyway：Successfully applied 2 migrations
```

**没开博客**（本机默认）会看到：

```
博客功能未开启，跳过管理员初始化
```

---

## 五、测试方法

### 5.1 不接 QQ 也能测（本地调试接口）

把 `app.channel.type` 改成 `mock`，然后：

```powershell
# 斜杠命令（不走大模型）
curl.exe -X POST http://localhost:8080/api/debug/qq/message `
  -H "Content-Type: application/json" `
  --data-binary '{"userId":"test-user-1","content":"/help"}'

# 普通聊天（走本地模型）
curl.exe -X POST http://localhost:8080/api/debug/qq/message `
  -H "Content-Type: application/json" `
  --data-binary '{"userId":"test-user-2","content":"你好呀"}'
```

响应里会明确告诉你处置结果：

```json
{ "code": 0, "data": { "replied": true, "reply": "可用命令（1 个）：...", "status": "已回复" } }
```

> **注意限流**：默认同一 userId 两次消息要间隔 1 秒（`app.chat.min-interval-millis`）。
> 太短会被静默丢弃，`status` 显示"已忽略（触发限流）"。
> 连续测试请换 userId，或把那个配置调成 0。

### 5.2 博客的端到端测试（67 项断言）

**应用起来之后**（8082 端口、博客开启）直接跑：

```powershell
pwsh -File data-test/e2e-test.ps1
```

它真的会跑一遍完整流程：

| 覆盖 | 验什么 |
|---|---|
| 认证 | 未登录被拦、密码错被拒、提示语不透露是用户名还是密码错 |
| 文章 | 增删改查、草稿不外泄、按状态筛选、slug 冲突被拒、必填校验 |
| 前台 | 匿名可读、列表不含正文、详情每次访问浏览量 +1 |
| **点赞** | **连点三次是 1→2→3**（确认没做去重） |
| 安全 | 正文里的 `<script>` 被转义；登录失败提示语不区分用户名/密码 |
| Markdown | 标题/列表/代码块/引用都渲染成正确标签 |
| 状态统计 | MySQL/Redis 连通性、JVM 内存、磁盘、排行榜 |
| 登出 | 登出后旧令牌立刻失效（401） |

> 管理员密码是脚本**从 yaml 里现场读**的，不写在脚本里 —— 那份文件要进仓库，
> 写进去等于提交密码。

**启动测试实例的完整命令**：

```powershell
mvn -o spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082 --app.channel.type=mock --app.blog.enabled=true"
```

### 5.3 前端逻辑测试（59 + 29 项）

```powershell
node data-test/frontend-test.mjs
node data-test/rail-test.mjs
```

**不需要起浏览器、不需要装任何依赖** —— 它们用 node 的 `vm` 模块造一个假浏览器
环境（mock 掉 `localStorage`、`location`、`fetch`），把 `common.js` / `main.js`
装进去跑。因为那几个函数本来就是纯函数。

| 脚本 | 测什么 | 项数 |
|---|---|---|
| `frontend-test.mjs` | `escapeHtml` 转义、时间解析（Safari 兼容）、401 处理、POST 的 charset | 59 |
| `rail-test.mjs` | 音乐时间轴：卡片数、`--step` 位移、点击播放、播完不循环、双卡片高亮同步 | 29 |

**为什么值得单独测**：这些函数在浏览器里出错的表现是**"页面白着"或"点了没反应"**，
从现象上根本看不出是哪一步断的。

> **外观本身验不了** —— 沙箱里起不来 Chrome（多进程 + 命名管道的 IPC 被拦）。
> 滚动、放大、淡出、扫光这些必须人眼过一遍。

### 5.4 接上 QQ 之后

直接在 QQ 里给机器人发消息：

- 发 `你好` → 走本地模型（Ollama 在跑的话）；没跑就走云端
- 发 `/help` → 直接返回命令列表，不碰模型

---

## 六、算力路由：这条消息交给谁算

### 判据（两个来源，任一成立就算"本地可用"）

```java
private boolean isLocalAvailable() {
    boolean localOk = this.localClient.isAvailable()      // 探测本机 Ollama（10 秒缓存）
            || this.localNodeManager.isOnline();          // 本地节点心跳
    if (!localOk) {
        this.localModelSupervisor.ensureStarted();         // 异步拉起 Ollama
    }
    return localOk;
}
```

| 此刻的情况 | 这条消息走谁 |
|---|---|
| WSL 里的 Ollama 活着 | **本地** |
| Ollama 没跑 | **云端**（降级）+ 自动拉起，下次请求回本地 |

不用猜，搜日志里的 `路由决定`：

```
路由决定：本地（策略 LOCAL_FIRST，本地在线）
路由决定：云端（策略 LOCAL_FIRST，但本地算力不可用，已降级）
```

### 相关文件

| 文件 | 作用 | 状态 |
|---|---|---|
| `route/ModelRouterImpl.java` | 路由判定（四种策略 + 降级 + 触发自动拉起） | ✅ |
| `route/RouteStrategy.java` | 四种策略枚举 | ✅ |
| `llm/ollama/LocalModelSupervisor.java` | **本地不可用时异步拉起 Ollama** | ✅ |
| `llm/ollama/OllamaChatModelClient.java` | 本地模型客户端 | ✅ |
| `cloud/CloudChatModelClient.java` | 云端模型客户端（DeepSeek） | ✅ |
| `cloud/LocalNodeManager` + `InMemoryLocalNodeManager` | 本地节点在线判定 | ✅ |
| `cloud/LocalNodeEndpoint.java` | `/ws/local-agent` 端点 | ⬜ **唯一的空壳** |

> 本地和云端两个客户端**同时装配**，由 `ModelRouterImpl` 在运行期选。
> 早期是靠 `@ConditionalOnProperty(app.role)` 二选一的，那个方式已取消 ——
> `app.role` 现在只用来标注"这台机器是什么角色"。

### 自动拉起：`LocalModelSupervisor`

本地不可用时，`isLocalAvailable()` 会顺手调一次 `ensureStarted()`：

```
wsl -d Ubuntu-20.04 -- bash -lc "export OLLAMA_HOST=0.0.0.0:11434; pkill -f 'ollama serve'; ..."
```

**异步**：发起就返回，当前这条消息照常走云端兜底，拉起来后**下一次**请求走本地。

**六道前置检查**，所以不会变成"每条消息起一个进程"：

`auto-start` 开关 → `manage-enabled` 开关 → **只在 Windows 生效** → 本地已可用就直接返回
→ 冷却时间（默认 60 秒）→ "启动中"互斥标记

### 唯一还没做的

`cloud/LocalNodeEndpoint.java` 仍是占位类（构造器直接抛异常），而且 pom 里**还没有**
`spring-boot-starter-websocket`。它负责"让远端本地节点连进来上报心跳"，单机用不到。

> **两个容易混的点**：
> ① `/setlocal` 是**手动、同步**的（拉起并回执），`LocalModelSupervisor` 是**自动、异步**的。
> ② "服务活着"（本机探测）和"节点在线"（心跳）是两个不同来源，但都算"本地可用"。

---

## 七、代码结构一图

```
src/main/java/com/mybotweb/
├── MybotwebApplication.java      启动类
├── common/
│   ├── result/                   Result / ResultCode（统一响应体）
│   ├── exception/                BusinessException + GlobalExceptionHandler
│   └── util/                     ProjectPaths
├── config/AppProperties.java     所有 app.* 配置（机器人部分）
│
├── ══════ QQ 机器人这半边 ══════
├── channel/                      ★QQ 接入层
│   ├── MessageChannel.java         接口：回复往哪发
│   ├── model/                      QqMessage / QqReply / QqScene
│   ├── mock/MockMessageChannel     mock 实现（打日志）
│   └── qq/                         真实实现
│       ├── QqAccessTokenManager    access_token 获取与刷新
│       ├── QqApiClient             调 OpenAPI
│       ├── QqGatewayClient         ★WebSocket 长连接，收消息
│       └── QqMessageChannel        MessageChannel 的 qq 实现
├── dispatcher/                   ★消息分发
│   ├── MessageDispatcher           三条分支：热聊 / 命令 / 聊天
│   └── DispatchOutcome             处理结果（含"被忽略的原因"）
├── command/                      ★斜杠命令
│   ├── CommandNames                ★全部命令名，只定义一次
│   ├── CommandHandler              接口
│   ├── CommandRouter               解析 / 鉴权 / 执行
│   └── impl/                       10 条命令各一个类
├── llm/                          ★大模型层
│   ├── ChatModelClient             接口：谁来算
│   ├── ModelCallException          携带"给用户看的话"+"给日志看的细节"
│   └── ollama/                     本地模型
│       ├── OllamaChatModelClient
│       ├── OllamaModelSelector
│       └── LocalModelSupervisor    ★本地不可用时异步拉起
├── route/                        ★算力路由
│   ├── ModelRouter / ModelRouterImpl
│   └── RouteStrategy
├── cloud/                        云端与本地节点
│   ├── CloudChatModelClient        ✅
│   ├── LocalNodeManager            ✅ 接口
│   ├── InMemoryLocalNodeManager    ✅ 实现
│   └── LocalNodeEndpoint           ⬜ 唯一的空壳
├── agent/                        人设、记忆、热聊
├── security/                     QQ 侧的权限
├── controller/                   机器人侧的 REST（DebugQqController）
├── client/ClientPageController   /client、/client/main 页面路由
│
├── ══════ 博客网站这半边 ══════
└── blog/                         ★★博客全部代码（36 个类）
    ├── config/                   BlogProperties / BlogWebConfig(拦截器注册) / MybatisPlusConfig
    ├── auth/                     JWT / 拦截器 / ThreadLocal / 首次建号
    ├── entity/ mapper/ dto/ vo/
    ├── markdown/MarkdownRenderer
    ├── service/                  ArticleService / ArticleCounterService / SystemStatusService
    └── web/                      5 个 Controller
```

**资源目录**：

```
src/main/resources/
├── application.yml                模板（会提交）
├── application-local.yml          本机真值（gitignore）
├── application-prod.yml           云端真值（gitignore）
├── db/migration/                  Flyway：V1 建 article、V2 建 admin_user
├── static/
│   ├── client/                    欢迎页 + 文章列表 + 详情
│   ├── admin/                     后台 6 个页面
│   ├── css/                       common.css（变量/按钮/容器）+ main.css + admin.css
│   ├── js/                        common.js（api/escapeHtml/toast）+ main.js + admin.js
│   └── music/                     音乐文件
└── SysPrompt/                     人设层 + 场合规则层
```

**加新斜杠命令**（三步，不用改任何现有文件）：
1. `command/CommandNames.java` 加常量
2. `command/impl/` 下新建类，实现 `CommandHandler`，加 `@Component`
3. 重启

**加新博客接口**：
1. 在 `blog/web/` 新建 Controller，路径前缀决定要不要登录（见 `blog/config/BlogWebConfig`）
2. 业务逻辑放 `blog/service/`
3. 返回结构放 `blog/vo/`

---

## 八、常见问题

### 机器人相关

**Q：日志里中文是乱码**
命令行跑的话先 `chcp 65001`。IDEA 跑没这个问题。

**Q：连接被反复关闭 / 一直连不上**
程序会在连续失败 5 次后打印排查清单，并按 5 分钟一次重连。最常见的三个原因：

1. **intents 权限**。除了三个基础事件，其他事件都要申请，而且「传递了无权限的 intents，websocket 会报错并直接关闭连接」。单聊/群聊属于 `GROUP_AND_C2C_EVENT (1 << 25)`。→ 去开放平台检查「单聊消息」「群聊消息」是否开通
2. **AppSecret 不对**。重新复制，注意别带空格
3. **网络**。本机若有代理，确认没拦 `api.bot.qq.com`

**Q：机器人连上了，但在 QQ 里发消息没反应**
按顺序查：

1. 沙箱成员加了吗？扫码加好友了吗？
2. `app.qq.intents` 是不是 `33554432`？（配成 513 收不到任何单聊消息，而且不报错）
3. 日志里有没有"收到消息"那一行？有的话是限流或处理出错

**Q：热聊开了但机器人从不主动说话**
**平台开关没开**。热聊的原料是"群里不带 @ 的消息"，而这类消息只在 QQ 开放平台开启
**「接收所有消息」** 之后才会推过来。代码已经就绪（`GROUP_MESSAGE_CREATE` 分支），
缺的是那个开关。验证方法：日志里搜 `收到群聊消息（全量模式`，一条都没有就是这个原因。

**Q：聊天回"我这边的本地模型好像没跑起来"**
本机模型服务连不上。`/status` 可以看模型状态（管理员命令）。
Windows 下还可以发 `/setlocal` 让程序试着自动拉起。

**Q：聊天回"云端账户余额不足了（402）"**
DeepSeek 账户欠费了。去控制台充值，或改用本地模型。

**注意这句不是"连不上"** —— 网络不通、密钥不对、余额不足是三种完全不同的问题：

| 回复里出现 | 真实原因 | 怎么办 |
|---|---|---|
| 余额不足（402） | 账户欠费 | 充值，或改用本地模型 |
| 密钥好像不对（401） | Key 错 / 被吊销 | 检查 `application-local.yml` 的 `api-key` |
| 找不到这个模型（404） | 模型名写错 | 核对 `app.llm.cloud.model` |
| 被限流（429） | 请求太频繁 | 等一会 |
| **连不上云端服务（网络不通或超时）** | **真的是网络问题** | 查网络、代理、防火墙 |

**Q：聊天回复末尾出现"（本机模型服务没起来，这条先用云端回的…）"**
本地模型没跑起来，这条**降级用了云端（按 token 计费）**。起来之后会自动切回本地。
这条提示一个进程只出现一次，之后只记日志（WARN，搜"已降级到云端"）。

**Q：怎么确认这次到底走了本地还是云端**
看日志里的 `路由决定：本地（…）` / `路由决定：云端（…）` 那行 —— 那才是权威答案。
启动横幅上的「本地节点：离线」不能当结论。

**Q：`/status` 说没有权限**
你的标识不在 QQ 管理员名单里（`data/admin-ids.txt`）。日志里"收到消息 用户(xxxxxx…)"
会打印标识前缀，拿到完整的填进 `application-local.yml` 的 `app.admin.user-ids`。

**Q：回复超过 5 分钟才生成，发不出去**
腾讯规定被动回复的 `msg_id` 只有 5 分钟有效。超时后程序会自动降级成主动消息，
但主动消息有每日配额。

### 博客相关

**Q：打开 `/client/main` 显示"本站的博客功能当前未开启"**
本机配置里博客默认是关的。启动时加 `--app.blog.enabled=true`（见 §2.2）。

**Q：启动报 `Migration checksum mismatch`**
改了已经执行过的 Flyway 脚本。**改过的脚本不要回改** —— 要调整表结构就新建
`V3__xxx.sql`。真要修（只在开发期、且数据可丢时）：

```sql
-- 连上库手动删掉那条历史记录，或者直接删库重建
DELETE FROM flyway_schema_history WHERE version = '1';
```

**Q：后台登录不了，密码是什么**
密码在 `application-local.yml` 的 `app.blog.admin.password`。
**注意它只在"首次启动、表为空"时生效** —— 表里已经有账号了，改配置不会改密码。
真要改密码，得进数据库改 `password_hash`（用 BCrypt 重新生成一个）。

**Q：后台登录之后过一会就要重新登录**
`app.blog.jwt.secret` 是空的 → 每次启动随机生成一个密钥 → 重启后旧令牌全失效。
填一个固定的长随机串（至少 32 字符）：

```bash
openssl rand -base64 48
```

**Q：点赞数点了没变 / 一直在涨**
**这是设计如此**：不做任何去重，点一次 +1。连点十次就是 +10。
要改成"一人一赞"见 `项目说明.md` §五.11。

**Q：浏览量刷新一次就涨**
同上，`getPublishedBySlug()` 里每次访问都 `incrementView()`。

**Q：改了 HTML/CSS/JS 之后页面没变**
运行中的实例读的是 `target/classes/`，**要 `mvn compile`**。

**Q：文章里的图片显示不出来**
现在只支持外链图片（正文里写 `![](https://...)`）。图片上传（OSS 直传）还没做。

**Q：服务器上内存占用显示 93%，但 `free -m` 说还有 2G**
已修。旧算法用的是 `MemFree`（不含 buff/cache），现在读 `/proc/meminfo` 的
`MemAvailable`。详见 `开发进度.md` §十四。
