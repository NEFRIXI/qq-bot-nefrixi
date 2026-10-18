# mybotweb

QQ 机器人（对接腾讯 QQ 开放平台）+ 一个有用户体系的博客网站，跑在同一个 Spring Boot 进程里。

> **四份文档的分工**
>
> | 文档 | 管什么 |
> |---|---|
> | **本文档** | **怎么把它跑起来**：配置凭证、启动命令、测试、排错 |
> | [`项目说明.md`](项目说明.md) | 代码怎么分布、想改东西该动哪个文件、为什么这么设计 |
> | [`项目数据流详解.md`](项目数据流详解.md) | 一条消息 / 一个请求从进门到出口经过哪几站 |
> | [`开发进度.md`](开发进度.md) | 当前状态快照、踩过的坑、当初为什么这么定 |

**当前状态：QQ 机器人全链路已跑通；博客含用户注册登录、评论、发帖、后台用户管理；已部署到云服务器。**

---

## 一、现在能做什么

| 能力 | 状态 |
|---|---|
| 在 QQ 里和机器人对话（单聊 / 群里 @） | ✅ 已跑通 |
| 斜杠命令（10 条命令、39 个名称） | ✅ 可用，**不消耗大模型** |
| 算力路由（本地 / 云端自动选 + 降级告知） | ✅ 已实现 |
| 本地模型自动拉起（`LocalModelSupervisor`） | ✅ 已实现 |
| 热聊模式（机器人主动搭话） | ✅ 已实现，⬜ 默认关闭，发 `/c_hot on` 开启 |
| `/status` 查看系统状态（含内存/运行时长） | ✅ 已精简并加了资源占用 |
| **博客前台**（欢迎页 / 列表 / 详情 / 点赞 / 浏览量） | ✅ 已完成 |
| **普通用户注册 / 登录 / 登出** | ✅ 已完成 |
| **评论（两层楼中楼）** | ✅ 已完成 |
| **用户发帖（只能删自己的）** | ✅ 已完成 |
| **接口限流（注册/登录/评论/发帖）** | ✅ 已完成 |
| **博客后台**（登录 / 文章 CRUD / 统计 / 服务器状态 / **用户管理**） | ✅ 已完成 |
| **数据库**（MySQL + Flyway 三个脚本、Redis 计数与会话） | ✅ 已完成 |
| 日志按天分目录 | ✅ 已完成（`logs/2026-10-17/`） |
| 本地节点接入端点 | ⬜ 未实现（全项目唯一的空壳） |
| 图片上传（OSS） | ⬜ 未做，正文现在只能用外链图片 |
| 改密码 / 找回密码 | ⬜ 未做，只能改数据库 |

详细的能力清单和设计理由见 `项目说明.md` §一 和 §五。

---

## 二、快速开始（当前环境已配好，直接跑）

### 2.1 本地：只跑机器人（最常见）

```powershell
chcp 65001                 # 不切的话中文日志是乱码
mvn -o spring-boot:run
```

**博客功能默认是关的**（`application-local.yml` 里 `app.blog.enabled: false`），
所以这样启动**不会连数据库**，MySQL 没开也能起。

### 2.2 本地：带博客一起跑

```powershell
mvn -o spring-boot:run "-Dspring-boot.run.arguments=--app.blog.enabled=true"
```

**首次启动**会自动建表（Flyway 跑 V1/V2/V3）并建出第一个管理员。
然后打开浏览器：

| 地址 | 是什么 | 要登录吗 |
|---|---|---|
| `http://127.0.0.1:8080/client` | 欢迎页（带实时状态 + 右上角登录入口） | 否 |
| `http://127.0.0.1:8080/client/main` | 文章列表 | 否 |
| `http://127.0.0.1:8080/client/main/{slug}` | 文章详情（**带评论区**） | 看评论不用，发评论要 |
| `http://127.0.0.1:8080/client/auth` | **普通用户登录 / 注册** | 否 |
| `http://127.0.0.1:8080/client/post` | **写文章（普通用户）** | **要** |
| `http://127.0.0.1:8080/admin/login` | **管理员**登录 | 否 |
| `http://127.0.0.1:8080/admin` | 后台概览 | 要（管理员） |
| `http://127.0.0.1:8080/admin/work/articles` | 文章管理 | 要（管理员） |
| `http://127.0.0.1:8080/admin/work/users` | **用户管理（封禁/解封）** | 要（管理员） |
| `http://127.0.0.1:8080/admin/work/stats` | 访问统计 | 要（管理员） |
| `http://127.0.0.1:8080/admin/work/system` | 服务器状态 | 要（管理员） |

> ⚠️ **两套身份、两个登录入口，别混**：
>
> - `/admin/login` 登的是**管理员**（能删文章、看服务器状态、封用户）
> - `/client/auth` 登的是**普通用户**（能发帖、评论）
> - 两套令牌用的是同一个密钥，但载荷里的 `type` 不同，互相不认
> - 浏览器里也是两个 localStorage key：`mybotweb_admin_token` / `mybotweb_user_token`，
>   所以**同一个浏览器可以同时登录两边**

**管理员账号密码在 `application-local.yml` 的 `app.blog.admin`。**
普通用户自己注册就能用。

### 2.3 服务器上跑

服务器上已经有 systemd 服务在管。改完 jar 之后：

```bash
sudo systemctl restart mybotweb
sudo systemctl status mybotweb
tail -f /home/nefrix-r/botweb/run.log
ls logs/                         # 会看到 2026-10-16/ 2026-10-17/ 这样的日期目录
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

**音乐目录不用跟着 jar 传。** `music/` 是外挂目录，和 jar 并排放着：

```bash
# 传歌（在 Windows 上执行）
scp "新歌.mp3" wn-bot-r:/home/nefrix-r/botweb/music/

# 改歌单
ssh wn-bot-r
nano /home/nefrix-r/botweb/music/list.json
```

**改完不用重启**，刷新网页就生效。所以以后加歌全程不碰 jar、不碰 systemd。

> 目录是按"启动时的工作目录"找的，不是按 jar 位置。service 文件里的
> `WorkingDirectory=/home/nefrix-r/botweb` 决定了 `./music` 指到这里。
> 从别的目录启动就会找不到 —— 那时在配置里写绝对路径，
> 或者用环境变量 `APP_MUSIC_DIR` 覆盖。

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
> **`application.yml`（会提交的那份）里现在是模板**：`api-key: ${LLM_API_KEY:}` 这种写法，
> 真值只在 local / prod 两份里。

### 第 2 件：在 WSL 里启动 Ollama

> ✅ **把 Ollama 跑起来，聊天就会走本地** —— 这才是"本地优先"的本意。
>
> 不启动也行：那时会自动降级到云端 DeepSeek（**回复末尾会明说**），
> 同时程序会**自动尝试拉起**它（`LocalModelSupervisor`，带 60 秒冷却）。

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

**建议先用 7B 跑顺**，等确实觉得慢再拉小的对比。

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
- 「算力路由器已就绪」那行的**「本地节点：离线」不能当结论** —— 要知道这次走谁，
  看聊天时日志里的 `路由决定：…` 才准
- 「鉴权成功，机器人在线」才说明 QQ 通道通了

**开了博客的话还会看到**：

```
Flyway：Migrating schema `mybotweb` to version "3 - create app user and comment"
Flyway：Successfully applied 3 migrations
管理员账号已存在，跳过初始化：admin
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

> ⚠️ **这个脚本还没覆盖用户体系**（注册/登录/评论/发帖/封禁）。
> 那套代码是后加的，测试没跟着更新 —— 手工验一遍，或者按同样的风格补上。

**启动测试实例的完整命令**：

```powershell
mvn -o spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082 --app.channel.type=mock --app.blog.enabled=true"
```

> 管理员密码是脚本**从 yaml 里现场读**的，不写在脚本里 —— 那份文件要进仓库，
> 写进去等于提交密码。

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
| `rail-test.mjs` | 音乐时间轴：卡片数、`--step` 位移、点击播放、播完不循环、双卡片高亮同步、**外挂歌单与兜底** | 45 |

> ⚠️ `user.js` 里的逻辑**没有对应的测试脚本**。

**再看音乐文件能不能真的取到**（需要先起服务，见 §5.2 的启动命令）：

```powershell
pwsh -File data-test/music-url-test.ps1
```

它真发 HTTP，验 16 项：外挂目录生效没有、8 个文件在不在、`Content-Type` 对不对、
支不支持 Range（拖进度条）、不存在的文件会不会老实 404、歌单和磁盘文件是否一一对应。

用 `-BaseUrl http://111.231.80.13:8080` 可以直接验服务器上的部署。

### 5.4 手工验证用户体系（现在只能这样）

```powershell
# 1) 注册（成功直接返回令牌）
$reg = curl.exe -s -X POST http://localhost:8082/client/api/user/register `
  -H "Content-Type: application/json;charset=UTF-8" `
  --data-binary '{"username":"alice","password":"Passw0rd!","nickname":"Alice"}'
$reg

# 2) 拿令牌发评论（把 <令牌> 换成上一步的 token）
curl.exe -s -X POST http://localhost:8082/client/api/comments `
  -H "Content-Type: application/json;charset=UTF-8" `
  -H "Authorization: Bearer <令牌>" `
  --data-binary '{"articleId":1,"content":"第一条评论"}'

# 3) 不带令牌发评论 → 应该 401
curl.exe -s -X POST http://localhost:8082/client/api/comments `
  -H "Content-Type: application/json" `
  --data-binary '{"articleId":1,"content":"匿名试试"}'

# 4) 看评论列表（公开，不需要令牌）
curl.exe -s "http://localhost:8082/client/api/comments?articleId=1"
```

**要验的关键几条**：

- 不带令牌 POST 评论 → `code: 401`
- 用**后台管理员令牌**调用户接口 → 也是 401（`type` 不是 `USER`）
- 拿 A 的令牌删 B 的帖子 → `code: 403`（"只能删除自己发的帖子"）
- 管理员封禁某人后，那人手上的旧令牌立刻失效
- 一分钟内连续发 21 条评论 → 第 21 条被 429

### 5.5 接上 QQ 之后

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

### 自动拉起：`LocalModelSupervisor`

本地不可用时，`isLocalAvailable()` 会顺手调一次 `ensureStarted()`，**异步**执行。
发起就返回，当前这条消息照常走云端兜底，拉起来后**下一次**请求走本地。

**六道前置检查**，所以不会变成"每条消息起一个进程"：
`auto-start` 开关 → `manage-enabled` 开关 → **只在 Windows 生效** → 本地已可用就直接返回
→ 冷却时间（默认 60 秒）→ "启动中"互斥标记

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
├── cloud/                        云端与本地节点（LocalNodeEndpoint 是空壳）
├── agent/                        人设、记忆、热聊
├── security/                     QQ 侧的权限 + 博客侧的基础设施
│   ├── UserContext / AdminWhitelist / AdminClaimService / RateLimiter
│   ├── ClientIpResolver            ⭐从请求里取真实 IP
│   └── RedisRateLimiter            ⭐博客侧限流（滑动窗口 + Lua）
├── controller/                   机器人侧的 REST（DebugQqController）
├── client/                       页面路由
│   ├── ClientPageController       /client、/client/main、/client/auth、/client/post
│   └── ClientStatusController     ⭐/client/api/status（首页轻量状态）
│
├── ══════ 博客网站这半边 ══════
└── blog/                         ★★博客全部代码
    ├── config/                   BlogProperties / BlogWebConfig(三道拦截器) / MybatisPlusConfig
    ├── auth/                     JWT / 拦截器 / 两个 ThreadLocal / 两套认证服务
    ├── entity/ mapper/ dto/ vo/
    ├── markdown/MarkdownRenderer
    ├── service/                  Article / ArticleCounter / SystemStatus /
    │                             ⭐UserPost / ⭐Comment / ⭐AdminUserManage
    └── web/                      9 个 Controller
```

**资源目录**：

```
src/main/resources/
├── application.yml                模板（会提交）
├── application-local.yml          本机真值（gitignore）
├── application-prod.yml           云端真值（gitignore）
├── logback-spring.xml             ⭐日志按天分目录
├── db/migration/                  Flyway：V1 建 article、V2 建 admin_user、V3 建 app_user+comment
├── static/
│   ├── client/                    欢迎页 / 列表 / 详情 / auth / post
│   ├── admin/                     登录 / 首页 / 文章 / 编辑 / 统计 / 系统 / users
│   ├── css/                       common.css + main.css + admin.css
│   ├── js/                        common.js + main.js + admin.js + user.js
│   └── music/                     ⭐ 兜底位置，平时是空的（见下）
└── SysPrompt/                     人设层 + 场合规则层
```

**项目根目录下还有一个 `music/`** —— 那是**外挂目录**，不在 jar 里：

```
<项目根>/
├── music/                         ⭐ 外挂：真实歌单和 mp3 都在这
│   ├── list.json                  歌单，改这个
│   ├── README.md                  加歌指南
│   └── *.mp3
├── src/                           打进 jar 的
└── pom.xml
```

后端把 `/music/**` 交给了这个目录（`config/StaticResourceConfig`），
所以**加歌 = 丢 mp3 + 改 `list.json`**，不用 `mvn package`、不用重启、不用重传 jar。
jar 里的 `static/music/` 只是兜底，平时空着。

部署到服务器上也是同样结构 —— `music/` 和 jar 并排放在 `/home/nefrix-r/botweb/`。

**加新斜杠命令**（三步，不用改任何现有文件）：
1. `command/CommandNames.java` 加常量
2. `command/impl/` 下新建类，实现 `CommandHandler`，加 `@Component`
3. 重启

**加新博客接口**：
1. 在 `blog/web/` 新建 Controller
2. **先想清楚要不要登录** —— 要的话去 `UserAuthInterceptor.requiresLogin()` 加一条规则
3. 业务逻辑放 `blog/service/`
4. 可能被刷的写操作，加 `rateLimiter.checkOrThrow(...)`

---

## 八、常见问题

### 机器人相关

**Q：日志里中文是乱码**
命令行跑的话先 `chcp 65001`。IDEA 跑没这个问题。

**Q：连接被反复关闭 / 一直连不上**
程序会在连续失败 5 次后打印排查清单，并按 5 分钟一次重连。最常见的三个原因：

1. **intents 权限**。「传递了无权限的 intents，websocket 会报错并直接关闭连接」。
   单聊/群聊属于 `GROUP_AND_C2C_EVENT (1 << 25)`。→ 去开放平台检查是否开通
2. **AppSecret 不对**。重新复制，注意别带空格
3. **网络**。本机若有代理，确认没拦 `api.bot.qq.com`

**Q：机器人连上了，但在 QQ 里发消息没反应**
按顺序查：

1. 沙箱成员加了吗？扫码加好友了吗？
2. `app.qq.intents` 是不是 `33554432`？（配成 513 收不到任何单聊消息，而且不报错）
3. 日志里有没有"收到消息"那一行？有的话是限流或处理出错

**Q：热聊开了但机器人从不主动说话**
**平台开关没开**。热聊的原料是"群里不带 @ 的消息"，而这类消息只在 QQ 开放平台开启
**「接收所有消息」** 之后才会推过来。验证方法：日志里搜 `收到群聊消息（全量模式`，
一条都没有就是这个原因。

**Q：聊天回复末尾出现"（本机模型服务没起来，这条先用云端回的…）"**
本地模型没跑起来，这条**降级用了云端（按 token 计费）**。起来之后会自动切回本地。
这条提示一个进程只出现一次，之后只记日志（WARN，搜"已降级到云端"）。

**Q：怎么确认这次到底走了本地还是云端**
看日志里的 `路由决定：本地（…）` / `路由决定：云端（…）` 那行 —— 那才是权威答案。

| 回复里出现 | 真实原因 | 怎么办 |
|---|---|---|
| 余额不足（402） | 账户欠费 | 充值，或改用本地模型 |
| 密钥好像不对（401） | Key 错 / 被吊销 | 检查 `application-local.yml` 的 `api-key` |
| 找不到这个模型（404） | 模型名写错 | 核对 `app.llm.cloud.model` |
| 被限流（429） | 请求太频繁 | 等一会 |
| **连不上云端服务（网络不通或超时）** | **真的是网络问题** | 查网络、代理、防火墙 |

### 博客相关 —— 基础

**Q：打开 `/client/main` 显示"本站的博客功能当前未开启"**
本机配置里博客默认是关的。启动时加 `--app.blog.enabled=true`（见 §2.2）。

**Q：启动报 `Migration checksum mismatch`**
改了已经执行过的 Flyway 脚本。**改过的脚本不要回改** —— 要调整表结构就新建
`V4__xxx.sql`。

**Q：后台登录不了，密码是什么**
密码在 `application-local.yml` 的 `app.blog.admin.password`。
**注意它只在"首次启动、表为空"时生效**。真要改密码，得进数据库改 `password_hash`
（用 BCrypt 重新生成一个）。

**Q：后台登录之后过一会就要重新登录**
`app.blog.jwt.secret` 是空的 → 每次启动随机生成密钥 → 重启后旧令牌全失效。
填一个固定的长随机串（至少 32 字符）：`openssl rand -base64 48`

**Q：改了 HTML/CSS/JS 之后页面没变**
运行中的实例读的是 `target/classes/`，**要 `mvn compile`**。

**Q：文章里的图片显示不出来**
现在只支持外链图片（正文里写 `![](https://...)`）。图片上传还没做。

### 博客相关 —— 用户体系（新增）

**Q：注册不了，提示"这个用户名已经被注册了"**
换一个。如果确定没人用过，可能是并发撞了唯一索引 —— 重试一次即可。

**Q：提示"密码连续错了太多次，请 X 分钟后再试"**
同一个**账号名**连错 5 次会锁 5 分钟（`AppUserAuthService.MAX_FAILURES` / `LOCK_SECONDS`）。
这是防止有人盯着一个账号暴力猜密码。等 5 分钟，或者换个账号。

**Q：提示"操作太频繁"**
四条限流在起作用（都在 `app.blog.rate-limit.*` 里改，设成 `0` 可关闭）：

| 场景 | 默认阈值 | 按什么限 | 配置项 |
|---|---|---|---|
| 注册 | 每 IP 每分钟 5 个 | **IP** | `app.blog.rate-limit.register-per-minute` |
| 登录 | 每 IP 每分钟 10 次 | **IP** | `app.blog.rate-limit.login-per-minute` |
| 评论 | 每用户每分钟 20 条 | **用户** | `app.blog.rate-limit.comment-per-minute` |
| 发帖 | 每用户每小时 10 篇 | **用户** | `app.blog.rate-limit.post-per-hour` |

> 全部设成 `0` 或负数就是**关闭对应限流**（调试时用，生产别这么配）。

> **注意"按 IP"的坑**：如果你在 Nginx 后面部署，**必须**把
> `app.blog.trust-proxy` 设成 `true`，否则所有人共享代理 IP，一个人刷就全网被挡。
> 反过来，如果没有代理却设成 `true`，攻击者伪造 `X-Forwarded-For` 就能绕过限流。

**Q：我被封禁了，提示"这个账号已被停用"**
站长在 `/admin/work/users` 把你停用了。而且**封禁时你手上所有令牌都被立刻注销了** ——
不是等令牌自然过期，是当场踢下线。

**Q：登录成功了，但刷新页面又变成未登录**
检查浏览器 localStorage 里的 `mybotweb_user_token` 还在不在。
如果后台登录着、前台没登录，那是正常的 —— **两套令牌互不相通**。

**Q：为什么我用管理员账号在 `/client/auth` 登不进去**
**故意的**。管理员令牌的 `type` 是 `ADMIN`，用户接口只认 `USER`。
管理员要发文走 `/admin/work/articles`，那边有完整编辑器。

**Q：评论太长了发不出去**
上限 1000 字（`comment` 表的 `VARCHAR(1000)`）。

**Q：回复别人的评论，为什么显示成跟其他人平级？**
**这是设计**。评论只存两层：楼 + 楼中楼。你回复"楼中楼里的某人"时，
新评论会被抬到顶层评论下面跟那条回复并列，但会带上"回复 @某人"。
好处是拉一页评论只要一条 SQL，不用递归。

**Q：能看到自己发过哪些帖子吗 / 能删吗**
`/client/post` 页面下方有"我的帖子"，能删。**只能删自己的** —— 删别人的会返回 403。

### 博客相关 —— 运维

**Q：服务器上内存占用显示 93%，但 `free -m` 说还有 2G**
已修。旧算法用的是 `MemFree`（不含 buff/cache），现在读 `/proc/meminfo` 的
`MemAvailable`。

**Q：日志文件在哪、怎么清理**
- 当前：`logs/mybotweb.log`
- 归档：`logs/2026-10-16/mybotweb.0.log`（**按天分目录**）
- 清理：`rm -rf logs/2026-10-16`（删掉整个日期目录，不用挑文件名）
- 自动策略写在 `logback-spring.xml`：保留 30 天、总量上限 2GB

**Q：日志里时间不对 / 和服务器时区差 8 小时**
检查 `logback-spring.xml` 的 pattern 用的是本地时区。系统时区不对的话先
`timedatectl set-timezone Asia/Shanghai`。

**Q：`/status` 显示的内存和后台 `/admin/work/system` 对不上**
两者采集的时间点不同，而且 `/status` 里的"整机内存"和"堆内存"是两个概念：
堆是 JVM 自己的，整机是整个服务器的。再叠加上 15 秒缓存（首页那个接口），
数字本来就会有小幅差异。
