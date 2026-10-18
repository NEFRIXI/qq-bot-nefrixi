# 音乐外挂目录

**这个目录在 jar 外面。** 加歌、换歌、改歌单都只动这里，
**不用重新 `mvn package`，不用重启服务，也不用重新上传 jar。**

改完刷新一下浏览器页面就生效。

---

## 加一首歌：三步

### 第 1 步：把 mp3 丢进这个目录

直接复制进来就行。文件名**用中文、带空格、带括号、带 `×`、带 `Ⅳ` 都没问题**，
不用改成 `01.mp3` 这种。

### 第 2 步：在 `list.json` 里加一条

```json
{ "title": "歌名", "file": "文件名.mp3" }
```

`file` 就是刚放进来那个文件的名字，**照着复制，别手打**。

### 第 3 步：刷新页面

没了。就这三步。

---

## `list.json` 怎么写

一个数组，一项一首歌：

```json
[
  { "title": "深海少女", "file": "初音ミク - 深海少女.mp3" },
  { "title": "夏虫",     "file": "洛天依 - 夏虫.mp3" }
]
```

**字段**：

| 字段 | 必填 | 说明 |
|---|---|---|
| `title` | 建议填 | 卡片底部的歌名。不填会显示"未命名" |
| `file` | 二选一 | 音频文件名，自动拼成 `/music/文件名` |
| `url` | 二选一 | 完整地址。想用外链（比如 CDN）时填这个 |
| `year` | 否 | 鼠标移上去左上角弹的年份标签。**不填就不显示** |
| `cover` | 否 | 封面图地址。不填会自动用主题色渐变 + 歌名首字 |

`file` 和 `url` 同时写的话 `url` 优先。

**顺序就是时间轴上的顺序**，想调顺序就调数组顺序。

### 几个容易踩的格式坑

- **JSON 很严格**：字符串必须双引号，最后一项后面**不能有逗号**
- **一个点都不能差**：`file` 里的名字要和磁盘上的文件名**完全一致**（空格、全半角括号都算）
- 写错了不会白屏 —— 页面会退回内置的兜底歌单，控制台里有一行 `console.warn` 说明原因

---

## 为什么要有这个目录

`src/main/resources/static/` 下的东西是**打进 jar 的**。
音乐有几十 MB，又是最常变的东西 —— 每加一首就重新打包、再传整个 jar 上去，太重了。

所以后端把 `/music/**` 这段路径单独交给了这个目录（见 `StaticResourceConfig`）。
查找顺序是：

```
1. 这个目录（外挂，优先）
2. jar 里的 static/music/（兜底，平时是空的）
```

**已经实测过**：应用运行中，往这里丢一个文件、改一下 `list.json`，
下一次请求就能取到，**完全不用重启**。

---

## 验证配好没有

两个脚本，一个验逻辑、一个验能不能真的取到文件：

```powershell
# 1) 逻辑：卡片数、滚动位移、点击播放、自动下一首、坏歌单兜底
#    纯 node，不用起服务
node data-test/rail-test.mjs

# 2) 真发 HTTP：文件在不在、外挂生效没有、类型对不对、支不支持拖进度条
#    需要先起一个实例
mvn -o spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082 --app.channel.type=mock"
pwsh -File data-test/music-url-test.ps1
```

两个脚本都是**照着 `list.json` 实际的歌去验**的，加歌减歌都不用改它们。

第二个脚本的 `-BaseUrl` 参数可以直接验服务器：

```powershell
pwsh -File data-test/music-url-test.ps1 -BaseUrl http://111.231.80.13:8080
```

---

## 部署到服务器上

服务器上这个目录是 `/home/nefrix-r/botweb/music/`，**和 jar 并排**：

```
/home/nefrix-r/botweb/
├── mybotweb-1.0.0.jar
├── config/
├── music/            ← 就是这个目录
│   ├── list.json
│   └── *.mp3
└── logs/
```

加歌就是：

```bash
# 传文件（在你的 Windows 机器上执行）
scp "本地路径/新歌.mp3" wn-bot-r:/home/nefrix-r/botweb/music/

# 改歌单（登上去编辑）
ssh wn-bot-r
nano /home/nefrix-r/botweb/music/list.json
```

**不用重启服务**，刷新网页就行。

> **路径是按"启动时的当前目录"算的，不是按 jar 所在目录。**
> systemd 的 service 文件里 `WorkingDirectory=/home/nefrix-r/botweb`，
> 所以 `./music` 正好指到这里。如果哪天改了 `WorkingDirectory`，
> 要么跟着挪目录，要么在配置里写绝对路径：
> `app.static.music-dir: /home/nefrix-r/botweb/music`

---

## 要不要把这些 mp3 提交到 git

**目前是没有提交的**（`.gitignore` 里也还没加规则），你自己决定：

| 提交 | 不提交（推荐） |
|---|---|
| 换电脑时音乐跟着走 | 仓库不会因为音频变大（现在 29.6 MB） |
| 但要占仓库体积，且每次换歌都产生 diff | 换电脑时要单独拷一次音乐目录 |

要让它不进仓库，在 `.gitignore` 里加一行：

```
music/*.mp3
```

（`list.json` 和这个 README 建议还是提交，那是配置不是内容。）
