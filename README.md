# mybotweb

QQ 机器人（对接腾讯 QQ 开放平台）+ 网站后端。网站名 **WN**。

> **这份文档讲的是"怎么把它跑起来"**（配置凭证、启动、排错）。
>
> **想了解代码怎么分布、想改东西该动哪个文件？** 看 [`项目说明.md`](项目说明.md)——
> 那里面有消息流转图、目录职责表，以及一张"我要改 X 该动哪个文件"的速查表。
>
> **想按数据流把它彻底看懂？** 看 [`项目数据流详解.md`](项目数据流详解.md)——
> 一条消息从进门到出口经过哪几站、每站做了什么预处理、为什么必须在这一步做。

当前进度：**对话链路已完整跑通**（凭证已配好，已经在 QQ 里实际收发过消息）。

---

## 一、现在能做什么

| 能力 | 状态 |
|---|---|
| 在 QQ 里和机器人对话 | ✅ 已跑通（单聊 / 群里 @） |
| 斜杠命令（10 条命令、39 个名称） | ✅ 可用，**不消耗大模型** |
| 管理员 / 普通用户权限区分 | ✅ 可用（名单存在 `data/admin-ids.txt`） |
| 本地小模型跑闲聊（WSL 里的 Ollama） | ✅ 可用，**看 Ollama 此刻在不在跑** |
| 云端大模型（DeepSeek，OpenAI 兼容接口） | ✅ 已启用，本地不可用时的兜底 |
| 算力路由（本地 / 云端自动选） | ✅ 已实现，本地不可用时降级云端并**在回复里告知** |
| 本地模型自动拉起 | ✅ 已实现（本地没跑时自动拉，带冷却和互斥） |
| 热聊模式（机器人主动搭话） | ✅ 已实现，⬜ 默认关闭，发 `/c_hot on` 开启 |
| 群消息分流（没 @ 机器人时不刷屏） | ✅ 已实现，只存上下文不回复 |
| 本地 HTTP 调试接口 | ✅ 可用，不需要任何凭证（⚠️ **但也没有鉴权**，上云前必须处理） |
| 网站后端（数据库、业务 API） | ⬜ **未开始**，位置已留好，见第五节 |
| 网站前端页面 | 🔶 欢迎页已做（`/client`），其余未开始 |
| 本地节点接入端点 | ⬜ 未实现（全项目唯一的空壳），见第六节 |

---

## 二、从零配置的三件事（当前项目已完成，留作备忘 / 换机器时参考）

### 第 1 件：把 AppSecret 填进去

打开 `src/main/resources/application-local.yml`，找到这一行：

```yaml
    app-secret: ""        # ← 填这里
```

去 QQ 开放平台 → 你的机器人 → **开发管理** → 复制 **AppSecret**，粘贴到两个引号中间。

> 这个文件已被 `.gitignore` 忽略，不会提交到 git 仓库，凭证不会外泄。
>
> ⚠️ **但 `application.yml` 里还留着一份明文 API Key**（`app.llm.cloud.api-key`），
> 而那个文件**是会提交的**。上云或分享代码之前先处理掉：先去控制台轮换密钥，
> 再把它改成 `api-key: ${LLM_API_KEY:}`。顺序不能反 —— 只要进过一次提交，
> 历史记录里就能翻出来。详见 `项目说明.md` 第七节。

### 第 2 件：在 WSL 里启动 Ollama

> ✅ **把 Ollama 跑起来，聊天就会走本地** —— 这才是"本地优先"的本意。
>
> 判据是"本机 Ollama 能不能连上"，所以这一步是真正让本地模型生效的动作。
> 不启动也行：那时会自动降级到云端 DeepSeek（**这条会在回复末尾明说**，
> 免得你以为在用免费的本机模型却在花 API 的钱），同时程序会**自动尝试拉起**它
> （`LocalModelSupervisor`，带 60 秒冷却，见第六节）。

Windows 上的本程序要通过 `localhost:11434` 访问 WSL 里的 Ollama。Ollama 默认只绑 `127.0.0.1`，**跨出 WSL 边界就访问不到**，所以必须显式指定绑定地址。

在 WSL 终端里执行：

```bash
export OLLAMA_HOST=0.0.0.0:11434
ollama serve
```

另开一个 WSL 终端，确认模型已下载：

```bash
ollama list
```

> **这里输出的名字必须和配置里的一字不差。** 打开 `application.yml`，
> 把 `app.llm.ollama.model` 改成 `ollama list` 里实际显示的名字（例如 `qwen2.5:7b`）。
>
> 名字写错不会导致启动失败，但每次聊天都会失败。好在程序探测时会把本机
> 实际有哪些模型列出来：
> `本地 Ollama 在运行，但没有模型 'xxx'。本机已下载的模型：[...]`

**想换模型不用改配置重启**，在 QQ 里发命令即可（仅管理员）：

```
/model              → 看当前用的是哪个、有哪些可以切
/model qwen2.5:3b   → 切过去，下一条消息就生效
```

要让某个模型出现在"可切换"列表里，需要先在 `application.yml` 的
`app.llm.ollama.available-models` 里登记（改完这个配置要重启一次）。
不登记也行——那一项留空就允许切到任意模型名，只是打错字不会被拦。

#### 关于 7B / 4B 的选择

| | 7B（如 `qwen2.5:7b`） | 4B / 3B |
|---|---|---|
| 效果 | 明显更好，能应付稍复杂的问题 | 闲聊够用，稍复杂就开始胡说 |
| 速度 | 慢，吃显存（约 5-6GB） | 快，省显存（约 3GB） |
| 适合 | 问答、需要一点推理的对话 | 纯闲聊、机器同时在跑别的程序时 |

**建议**：先用 7B 跑顺，等确实觉得慢再拉小的对比。
用 `/model` 切过去聊两句，感觉不对再切回来——这正是把它做成命令而不是配置项的意义。

### 第 3 件：把机器人加成好友（沙箱）

开发阶段的机器人**只对沙箱成员响应**。去 QQ 开放平台 → 你的机器人 → **开发 → 沙箱配置 → 消息列表配置**，添加你自己的 QQ 号，然后扫码把机器人加为好友。

不做这一步，你在 QQ 里发消息它不会有任何反应。

---

## 三、怎么跑起来

### 方式一：IDEA（推荐，中文日志不会乱码）

1. 用 IDEA 打开 `D:\ds-work\my_web_project`
2. 等 Maven 索引完成
3. 运行 `MybotwebApplication` 的 `main` 方法

### 方式二：命令行

```powershell
# 先切控制台到 UTF-8，否则中文日志会乱码
chcp 65001
mvn -o spring-boot:run
```

> `-o` 是离线模式。本机 Maven 仓库里依赖齐全，离线跑更快；
> 如果以后加了新依赖，去掉 `-o` 让它去下载。

### 启动成功的标志

```
Tomcat started on port 8080 (http)
命令路由初始化完成，共注册 10 个命令，索引 39 个名称：...
算力路由器已就绪 / 当前策略：LOCAL_FIRST / 本地节点：离线（未接入或未上报心跳）
鉴权成功，机器人在线！机器人昵称：xxx
```

**中间两行各有含义，都要看一眼：**

- 「命令路由初始化完成」后面如果是 `共注册 0 个命令`，说明 `command/impl/` 下的类漏了 `@Component`
- 「算力路由器已就绪」那行显示策略和两个算力端点。**注意「本地节点：离线」这一项不能当结论用** —— 它只看"本地节点有没有上报心跳"，而真正的判据还包括"本机模型服务能不能连上"。要知道这次走谁，看聊天时日志里的 `路由决定：…` 那行才准
- 看到「鉴权成功，机器人在线」才说明 QQ 通道通了

> **启动日志里出现一次 `IllegalStateException: WebSocket 还没连接，无法发送` 是正常的**——
> 首次连接有个已知的时序问题，程序会在 5 秒后自动重连成功，不影响使用。详见 `项目说明.md` 第七节。

---

## 四、测试方法

### 不接 QQ 也能测（本地调试接口）

把 `application.yml` 里 `app.channel.type` 改成 `mock`，然后在浏览器或命令行发：

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
{
  "code": 0,
  "data": {
    "replied": true,
    "reply": "可用命令（1 个）：...",
    "status": "已回复"
  }
}
```

> **注意限流**：默认同一个 userId 两次消息要间隔 1 秒（`app.chat.min-interval-millis`）。
> 间隔太短会被静默丢弃，响应里的 `status` 会显示"已忽略（触发限流）"。
> 连续测试时请换 userId，或者把那个配置调成 0。

### 接上 QQ 之后

直接在 QQ 里给机器人发消息：

- 发 `你好` → 走本地模型（Ollama 在跑的话）；Ollama 没跑就走云端
- 发 `/help` → 直接返回命令列表，不碰模型

---

## 五、★ 网站后端以后加在哪里

网站相关的代码**后端一行都没写**（按你的要求先专注把对话拉通），
只有欢迎页做出来了（`src/main/resources/static/client/index.html`，通过 `/client` 访问）。
将来往这些位置加，包名和分层已经定好了：

| 要加的东西 | 放在哪个包 / 路径 | 说明 |
|---|---|---|
| 网站 REST 接口 | `com.mybotweb.controller` | 比如 `ArticleController`，路径建议 `/api/article/**` |
| 管理端接口 | `com.mybotweb.controller` | 建议路径 `/api/admin/**`，和管理员权限绑定 |
| 业务服务接口 | `com.mybotweb.service` | 接口放这里，实现放 `service.impl` |
| 数据库实体 | `com.mybotweb.entity` | 一个类对应一张表，类名用驼峰（`ArticleCategory`） |
| Mapper | `com.mybotweb.mapper` | MyBatis-Plus 的 `BaseMapper` 子接口 |
| 请求参数 | `com.mybotweb.controller.dto` | 已经在用（`DebugMessageRequest` 就是） |
| 响应数据 | `com.mybotweb.controller.vo` | 已经在用（`DebugReplyVo` 就是） |
| 前端页面 | `src/main/resources/static` | 放进去就能通过 `http://IP:8080/xxx.html` 访问 |
| 建表 SQL | `src/main/resources/db/` | 建议文件名 `schema.sql` |

**还需要额外引入的依赖**（现在 pom 里没有）：

```xml
<!-- MyBatis-Plus（注意：Boot 3.x 需要用 3.5.3 以上版本） -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-boot-starter</artifactId>
    <version>3.5.7</version>
</dependency>

<!-- MySQL 驱动 -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

**加完依赖记得去掉 `mvn` 的 `-o`**，让它联网下载。

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

不用猜，搜日志里的 `路由决定` 那一行，它会直接告诉你：

```
路由决定：本地（策略 LOCAL_FIRST，本地在线）
路由决定：云端（策略 LOCAL_FIRST，但本地算力不可用，已降级）
```

### 相关文件

| 文件 | 作用 | 状态 |
|---|---|---|
| `route/ModelRouter.java` | 接口 | ✅ |
| `route/ModelRouterImpl.java` | 路由判定（四种策略 + 降级 + 触发自动拉起） | ✅ 已实现 |
| `route/RouteStrategy.java` | 四种策略枚举 | ✅ 已实现 |
| `route/NoAvailableModelException.java` | `LOCAL_ONLY` 且本地不可用时抛 | ✅ |
| `llm/ollama/LocalModelSupervisor.java` | **本地不可用时异步拉起 WSL 里的 Ollama** | ✅ 已实现 |
| `llm/ollama/OllamaChatModelClient.java` | 本地模型客户端 | ✅ |
| `cloud/CloudChatModelClient.java` | 云端模型客户端（DeepSeek） | ✅ |
| `cloud/LocalNodeManager` + `InMemoryLocalNodeManager` | 本地节点在线判定 | ✅ 已实现 |
| `cloud/LocalNodeEndpoint.java` | `/ws/local-agent` 端点 | ⬜ **唯一的空壳** |

> 本地和云端两个客户端现在**同时装配**，由 `ModelRouterImpl` 在运行期选。
> 早期版本是靠 `@ConditionalOnProperty(app.role)` 二选一装配的，那个方式已经取消
> —— `app.role` 现在只用来标注"这台机器是什么角色"。

### 自动拉起：`LocalModelSupervisor`

本地不可用时，`isLocalAvailable()` 会顺手调一次 `ensureStarted()`，它执行的是：

```
wsl -d Ubuntu-20.04 -- bash -lc "export OLLAMA_HOST=0.0.0.0:11434; pkill -f 'ollama serve'; ..."
```

**异步**：发起就返回，当前这条消息照常走云端兜底，等拉起来后**下一次**请求就走本地。
它前面有**六道检查**，所以不会变成"每条消息起一个进程"：

`auto-start` 开关 → `manage-enabled` 开关 → **只在 Windows 生效** → 本地已可用就直接返回
→ 冷却时间（默认 60 秒）→ "启动中"互斥标记

### 唯一还没做的

`cloud/LocalNodeEndpoint.java` 仍是占位类（构造器直接抛异常），而且 **pom 里还没有
引入 `spring-boot-starter-websocket`**。它负责"让远端本地节点连进来上报心跳"，
单机场景用不到。

> **两个容易混淆的点**：
> ① `/setlocal` 是**手动、同步**的（拉起并给你回执），`LocalModelSupervisor` 是**自动、异步**的。两者读同一份 `app.local-ollama.*` 配置。
> ② "服务活着"（本机探测）和"节点在线"（心跳）是两个不同来源，但都算"本地可用"。

---

## 七、代码结构一图

```
src/main/java/com/mybotweb/
├── MybotwebApplication.java      启动类
├── common/result/                统一响应体 Result / ResultCode
├── config/AppProperties.java     所有 app.* 配置集中在这里
├── channel/                      ★QQ 接入层
│   ├── MessageChannel.java         接口：回复往哪发
│   ├── model/                      QqMessage / QqReply / QqScene
│   ├── mock/MockMessageChannel     mock 实现（打日志）
│   └── qq/                         真实实现
│       ├── QqAccessTokenManager    access_token 获取与刷新
│       ├── QqApiClient             调 OpenAPI（取网关地址、发消息）
│       ├── QqGatewayClient         ★WebSocket 长连接，收消息
│       └── QqMessageChannel        MessageChannel 的 qq 实现
├── dispatcher/                   ★消息分发
│   ├── MessageDispatcher           消息入口路由（三条分支：热聊 / 命令 / 聊天）
│   └── DispatchOutcome             处理结果（含"被忽略的 6 种原因"）
├── command/                      ★斜杠命令
│   ├── CommandHandler              接口，新命令实现它即可
│   ├── CommandRouter               解析 / 鉴权 / 执行
│   └── impl/                       10 条命令各一个类
├── llm/                          ★大模型层
│   ├── ChatModelClient             接口：谁来算（本地和云端都实现它）
│   ├── model/ChatMessage           ChatRole
│   ├── mock/MockChatModelClient    回声实现
│   └── ollama/                     本地模型（WSL）
│       ├── OllamaChatModelClient
│       ├── OllamaModelSelector     记住"当前用哪个模型"
│       └── dto/                    请求/响应结构
├── route/                        ★算力路由（这次交给谁算）
│   ├── ModelRouter / ModelRouterImpl
│   └── RouteStrategy               四种策略枚举
├── cloud/
│   ├── CloudChatModelClient        ✅ 云端大模型（当前在用）
│   ├── LocalNodeManager            ✅ 接口
│   ├── InMemoryLocalNodeManager    ✅ 实现
│   └── LocalNodeEndpoint           ⬜ 唯一的空壳
├── agent/
│   ├── ConversationMemory          多轮记忆（内存，重启清空）
│   ├── HotChatMode                 热聊开关 + 两道节流闸
│   ├── HotChatBuffer               热聊的按群缓冲
│   └── prompt/                     PromptLoader + SystemPromptManager
├── security/
│   ├── UserContext                 用户身份
│   ├── AdminWhitelist              管理员判断
│   ├── AdminClaimService           申请流程（验证码）
│   └── RateLimiter                 用户限流
└── controller/                   REST 接口
    ├── DebugQqController           本地调试入口
    ├── ClientPageController        /client 页面路由
    └── dto/  vo/
```

**加新斜杠命令的完整步骤**（三步，不用改任何现有文件）：
1. 在 `command/impl/` 下新建类，实现 `CommandHandler`
2. 加上 `@Component` 注解
3. 重启应用

`CommandRouter` 会自动收集，`/help` 会自动列出来。

---

## 八、常见问题

**Q：日志里中文是乱码**
命令行跑的话先执行 `chcp 65001`。用 IDEA 跑不会有这个问题。

**Q：连接被反复关闭 / 一直连不上**
程序会在连续失败 5 次后打印一份排查清单，并按 5 分钟一次的节奏重连（不再刷屏）。
最常见的三个原因：

1. **intents 权限**。官方文档写明：除了 `GUILDS`、`PUBLIC_GUILD_MESSAGES`、`GUILD_MEMBERS`
   三个基础事件默认有权限外，「其他的特殊事件，都需要经过申请才能够使用」，
   而且「如果在鉴权的时候传递了无权限的 intents，websocket 会报错，并直接关闭连接」。
   单聊/群聊事件属于 `GROUP_AND_C2C_EVENT (1 << 25)`。
   → 去开放平台检查机器人的功能配置里「单聊消息」「群聊消息」是否已开通。
2. **AppSecret 不对**。重新复制一次，注意别带空格。
3. **网络**。本机若有代理软件，确认没拦 `api.bot.qq.com`。

**Q：机器人连上了，但在 QQ 里发消息没反应**
按顺序检查：
1. 沙箱成员加了吗？扫码加好友了吗？
2. `app.qq.intents` 是不是 `33554432`？（配成 513 会收不到任何单聊消息，而且不报错）
3. 日志里有没有"收到消息"这一行？有的话是限流或处理出错，看后面的日志。

**Q：聊天回"我这边的本地模型好像没跑起来"**
本机模型服务连不上。先确认它在不在跑；`/status` 可以看模型状态（管理员命令）。
Windows 下还可以发 `/setlocal` 让程序试着自动拉起。

**Q：聊天回"云端账户余额不足了（402）"**
云端（DeepSeek）账户欠费了。去控制台充值，或者改用本地模型。

**注意这句不是"连不上"** —— 网络不通、密钥不对、余额不足是三种完全不同的问题，
排查方向也完全不同。程序现在会分开说清楚：

| 回复里出现 | 真实原因 | 怎么办 |
|---|---|---|
| 余额不足（402） | 账户欠费 | 充值，或改用本地模型 |
| 密钥好像不对（401） | Key 错 / 被吊销 | 检查 `application-local.yml` 的 `api-key` |
| 找不到这个模型（404） | 模型名写错 | 核对 `app.llm.cloud.model` |
| 被限流（429） | 请求太频繁 | 等一会 |
| **连不上云端服务（网络不通或超时）** | **真的是网络问题** | 查网络、代理、防火墙 |

**Q：聊天回复末尾出现"（本机模型服务没起来，这条先用云端回的…）"**
说明本地模型没跑起来，这条**降级用了云端（按 token 计费）**。
本机模型服务起来之后会自动切回本地。
这条提示一个进程只出现一次，之后只记在日志里（WARN 级别，搜"已降级到云端"）。

**Q：怎么确认这次到底走了本地还是云端**
看聊天时日志里的 `路由决定：本地（…）` / `路由决定：云端（…）` 那一行 ——
那才是权威答案。启动横幅上的「本地节点：离线」不能当结论（见第三节）。

**Q：`/status` 说没有权限**
你的用户标识不在白名单里。日志里"收到消息 用户(xxxxxx…)"那行会打印你的标识前缀，
拿到完整的之后填到 `application-local.yml` 的 `app.admin.user-ids`。

**Q：回复超过 5 分钟才生成，发不出去**
腾讯规定被动回复的 `msg_id` 只有 5 分钟有效。超时后程序会自动降级成主动消息，
但主动消息有每日配额。本地 4B 模型正常应在几秒内出结果，如果经常超时说明机器太慢，
考虑换更小的模型或者切云端。
