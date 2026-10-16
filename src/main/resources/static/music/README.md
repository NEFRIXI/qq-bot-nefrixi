# 音乐文件放这里

把 mp3 丢进这个目录，然后到 `src/main/resources/static/js/main.js`
顶部的 `MUSIC_LIST` 数组里填曲目信息。

## 为什么目录名和访问路径不一样

文件放在 `src/main/resources/static/music/01.mp3`，
页面上访问的地址是 `/music/01.mp3`。

**`static/` 这四个字母不出现在 URL 里** —— Spring Boot 把它当成静态资源的根目录，
所以 `static/` 下面的东西直接按路径访问。不用写 Controller，也不用配映射。

## 加完文件记得重新构建

```powershell
mvn compile
```

这一步会把 `src/main/resources/static/` 下的东西复制到 `target/classes/static/`。
运行中的实例读的是 `target/classes/`，不重新构建的话新加的 mp3 取不到（404）。

## MUSIC_LIST 每一项

```js
{ title: '曲目 01', year: '2024', cover: null, url: '/music/01.mp3' }
```

| 字段 | 说明 |
| --- | --- |
| `title` | 卡片底部的歌名，鼠标移上去才显示 |
| `year` | 鼠标移上去时左上角弹出的年份标签，留空就不显示 |
| `cover` | 封面图地址。填 `null` 会自动用主题色渐变加标题首字顶上，没做封面时也不难看 |
| `url` | 音频地址，写 `/music/文件名.mp3` |

数组现在是 20 条占位数据，直接往上改就行 —— 曲目数不用正好 20，
多几首少几首都可以，滚动速度会按实际条数自动算。
