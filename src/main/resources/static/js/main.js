/* ============================================================
   main.js —— 前台博客的两个页面（列表 / 详情）
   ------------------------------------------------------------
   两个页面共用一个文件，靠 <body data-page="list|article"> 区分。
   为什么不拆成两个：它们要用的工具函数、顶部栏渲染、错误处理
   几乎一样，拆开就是复制两份，改一处忘一处。
   ============================================================ */
(function () {
    'use strict';

    // 当前页面类型，在 HTML 的 <body data-page="..."> 上
    const PAGE = document.body.dataset.page;

    // 列表页当前在第几页，翻页时更新
    let currentPage = 1;

    // 详情页的文章 id 和点赞状态
    let articleId = null;
    let likedInThisSession = false;

    /* ========================================================
       ★★★ 左侧音乐栏的歌曲数据 ★★★
       --------------------------------------------------------
       【现在真正生效的歌单不在这里了】

       是外挂目录里的那个 music/list.json —— 页面加载时 fetch 过来渲染。
       加歌只改那个文件 + 把 mp3 丢进同一个目录，不用碰这个 JS，
       更不用重新打包 jar。

       下面这个 DEFAULT_MUSIC_LIST 只是【兜底】：
       list.json 取不到（目录没建、JSON 写错了、请求失败）时用它，
       保证页面不会空一片。两边不一致时，以 list.json 为准。

       【每个字段】
         title  卡片底部的歌名
         file   音频文件名（相对 /music/ 的路径，可以带子目录）
         url    完整地址。填了就直接用它，不再拼 /music/ —— 留给以后接外链
         year   鼠标移上去时左上角弹出的年份标签（留空就不显示）
         cover  封面图地址。填 null 会用主题色渐变 + 标题首字顶上

       year / cover / url 都可以不写，normalizeSong() 会给默认值。
       ======================================================== */
    const DEFAULT_MUSIC_LIST = [
        { title: '深海少女', file: '初音ミク - 深海少女.mp3' },
        { title: '夢と葉桜 (梦与叶樱)', file: '初音ミク、青木月光 - 夢と葉桜 (梦与叶樱).mp3' },
        { title: '愛言葉Ⅳ (爱的言语 Ⅳ)', file: 'DECO×27、初音ミク - 愛言葉Ⅳ (爱的言语 Ⅳ).mp3' },
        { title: 'ツギハギスタッカート (拼凑的断音)', file: 'hanser - ツギハギスタッカート (拼凑的断音).mp3' },
        { title: '夏虫', file: '洛天依 - 夏虫.mp3' },
        { title: '一花依世界', file: '洛天依 - 一花依世界.mp3' },
        { title: '光与影的对白', file: '洛天依 - 光与影的对白.mp3' },
        { title: '不负韶光', file: '多多poi、花玲 - 不负韶光.mp3' }
    ];

    /** 歌单地址。想换个文件名就改这里 */
    const MUSIC_LIST_URL = '/music/list.json';

    /** 实际在用的歌单。list.json 取到之后会被整个替换掉 */
    let MUSIC_LIST = DEFAULT_MUSIC_LIST;

    // 当前在放第几首（数组下标）。null = 没在放
    let currentPlayingId = null;

    document.addEventListener('DOMContentLoaded', function () {
        // 这两样两个页面都有，所以放在页面判断之前
        initMusicRail();
        initReadProgress();

        if (PAGE === 'list') {
            initListPage();
        } else if (PAGE === 'article') {
            initArticlePage();
        }
    });

    /**
     * 顶部阅读进度条。
     *
     * 改的是 transform: scaleX，不是 width —— 滚动时这个函数
     * 每帧都会跑，改 width 会让浏览器每帧重排整页布局，长文章上
     * 会直接卡住。scaleX 只影响合成层。
     */
    function initReadProgress() {
        const bar = $('#readProgress');
        const scroller = $('.page-main');
        if (!bar || !scroller) {
            return;      // 当前页面没有进度条
        }

        let ticking = false;

        function paint() {
            ticking = false;

            const scrollable = scroller.scrollHeight - scroller.clientHeight;
            // 内容不够长、压根滚不动时，进度条保持 0，
            // 而不是除以 0 变成 NaN 把整条线弄没
            const ratio = scrollable > 0 ? scroller.scrollTop / scrollable : 0;

            bar.style.transform = 'scaleX(' + Math.min(Math.max(ratio, 0), 1).toFixed(4) + ')';
        }

        scroller.addEventListener('scroll', function () {
            // 用 requestAnimationFrame 节流：scroll 事件一帧能触发好几次，
            // 每次都算一遍纯属白算 —— 屏幕一帧只画一次。
            // passive: true 告诉浏览器这个回调不会阻止滚动，
            // 它就不用等回调跑完才开始滚
            if (!ticking) {
                ticking = true;
                window.requestAnimationFrame(paint);
            }
        }, { passive: true });

        paint();
    }

    /* ========================================================
       列表页
       ======================================================== */
    function initListPage() {
        // 支持用地址栏参数直接翻页：/client/main?page=3
        const pageFromUrl = parseInt(getQuery('page'), 10);
        currentPage = (pageFromUrl > 0) ? pageFromUrl : 1;

        loadList();
    }

    async function loadList() {
        const box = $('#listBox');
        showLoading(box);

        try {
            const data = await api(
                '/client/api/articles?page=' + currentPage,
                { auth: false }        // 前台接口不需要令牌
            );

            renderList(data);
            renderPager(data);
        } catch (e) {
            // 整个列表加载失败：显示常驻的错误提示 + 重试按钮
            showPageError(box, '文章列表加载失败：' + e.message, loadList);
            $('#pagerBox').innerHTML = '';
        }
    }

    function renderList(data) {
        const box = $('#listBox');

        if (!data.records || data.records.length === 0) {
            showEmpty(box, currentPage > 1 ? '这一页没有文章' : '还没有发布任何文章');
            return;
        }

        // 用 escapeHtml 处理每一个文字字段 —— 标题和摘要都是纯文本，
        // 直接拼进 innerHTML 会让文章标题里的 <script> 真的执行
        box.innerHTML = data.records.map(function (item) {
            const url = '/client/main/' + encodeURIComponent(item.slug);

            // 左列放"月-日 / 年"，不放序号。
            // 【为什么不放序号】序号在翻页时会从头来过（前端不知道后端每页几条），
            // 第二页的第 1 篇显示成 01 等于在骗人；日期是这篇自己的事实，翻到哪页都对。
            const date = formatDate(item.publishedAt);
            const day = date.length >= 10 ? date.slice(5) : date;
            const year = date.length >= 10 ? date.slice(0, 4) : '';

            return ''
                + '<article class="post-card">'
                + '  <div class="post-no">'
                + '    <b>' + escapeHtml(day) + '</b>'
                + (year ? '<span>' + escapeHtml(year) + '</span>' : '')
                + '  </div>'
                + '  <a class="post-title" href="' + url + '">'
                +        escapeHtml(item.title)
                + '  </a>'
                + (item.summary
                    ? '<p class="post-summary">' + escapeHtml(item.summary) + '</p>'
                    : '')
                + '  <div class="post-meta">'
                + '    <span>' + item.viewCount + ' 次浏览</span>'
                + '    <span class="dot">·</span>'
                + '    <span>' + item.likeCount + ' 赞</span>'
                + '  </div>'
                + '</article>';
        }).join('');

        // 列表填好之后才挂入场动画 —— 反过来的话观察器会盯着空容器
        observeCards();
    }

    /**
     * 让列表项在滚进可视区时淡入上浮。
     *
     * 【为什么用 IntersectionObserver，不监听 scroll】
     * scroll 事件每滚一像素就触发一次，回调里还得读元素位置
     * （强制重排），文章一多就明显掉帧。IntersectionObserver 由
     * 浏览器在合成阶段判断，回调次数只跟"有几个元素进出视口"有关，
     * 跟滚了多远无关。
     *
     * 【为什么是 JS 主动打标记，而不是 CSS 里直接写 opacity: 0】
     * CSS 直接写的话，万一 JS 半路挂了（比如接口报错提前 return），
     * 整页文章会永远不显示 —— 内容是有的，就是看不见，最难查。
     * 现在由 JS 加 .is-hidden，JS 没跑起来就一切照常可见。
     */
    function observeCards() {
        const cards = $$('.post-card');

        // 老浏览器没有这个 API：直接全显示，不做动画
        if (typeof IntersectionObserver !== 'function') {
            cards.forEach(function (card) {
                card.classList.add('is-shown');
            });
            return;
        }

        const observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) {
                    return;
                }

                const el = entry.target;
                // 逐张错开一点。最多错开到第 6 张 ——
                // 一屏也就看到五六篇，再往后错开只会让人觉得卡
                const index = Number(el.dataset.enterIndex || 0);
                el.style.transitionDelay = Math.min(index, 6) * 55 + 'ms';

                el.classList.add('is-shown');
                // 出现过的不用再盯着了
                observer.unobserve(el);
            });
        }, {
            // 提前一点开始，滚到底时不会"等它露头才动"
            rootMargin: '0px 0px -6% 0px',
            threshold: 0.01
        });

        cards.forEach(function (el, i) {
            el.dataset.enterIndex = i;
            el.classList.add('is-hidden');
            observer.observe(el);
        });
    }

    function renderPager(data) {
        const box = $('#pagerBox');

        // 只有一页就不显示翻页条 —— 一个点了没反应的"下一页"不如不显示
        if (!data.pages || data.pages <= 1) {
            box.innerHTML = '';
            return;
        }

        const hasPrev = data.current > 1;
        const hasNext = data.current < data.pages;

        // 用 disabled 属性而不是把按钮藏起来：按钮位置稳定，
        // 用户翻页时不会因为按钮突然出现/消失而点错
        box.innerHTML = ''
            + '<div class="pager">'
            + '  <button class="btn btn-sm" id="prevBtn"' + (hasPrev ? '' : ' disabled') + '>上一页</button>'
            + '  <span class="pager-info">第 ' + data.current + ' / ' + data.pages + ' 页 · 共 ' + data.total + ' 篇</span>'
            + '  <button class="btn btn-sm" id="nextBtn"' + (hasNext ? '' : ' disabled') + '>下一页</button>'
            + '</div>';

        const prevBtn = $('#prevBtn');
        const nextBtn = $('#nextBtn');

        if (prevBtn && hasPrev) {
            prevBtn.addEventListener('click', function () {
                goToPage(data.current - 1);
            });
        }
        if (nextBtn && hasNext) {
            nextBtn.addEventListener('click', function () {
                goToPage(data.current + 1);
            });
        }
    }

    function goToPage(page) {
        currentPage = page;
        // 翻页后回到顶部。不这么做的话，从第 3 页往回翻时
        // 页面还停在底部，看到的是列表末尾，会以为没生效
        window.scrollTo({ top: 0, behavior: 'smooth' });
        loadList();
    }

    /* ========================================================
       详情页
       ======================================================== */
    function initArticlePage() {
        const slug = getSlugFromPath();

        if (!slug) {
            showPageError($('#articleBox'), '地址里没有文章标识');
            return;
        }

        loadArticle(slug);
    }

    /**
     * 从地址栏里取出 slug。
     *
     * 地址形如 /client/main/why-bot-said-402，
     * 拆开是 ['client', 'main', 'why-bot-said-402']，取最后一段。
     *
     * 【为什么要 decodeURIComponent】slug 里如果有中文或特殊字符，
     * 浏览器地址栏里显示的是 %E4%B8%BA 这种编码形式，
     * 请求时必须还原成原文，否则查不到。
     */
    function getSlugFromPath() {
        const parts = location.pathname.split('/').filter(Boolean);
        return parts.length >= 3 ? decodeURIComponent(parts[parts.length - 1]) : '';
    }

    async function loadArticle(slug) {
        const box = $('#articleBox');
        showLoading(box, '正在读取文章…');

        let data;
        try {
            data = await api('/client/api/articles/' + encodeURIComponent(slug), { auth: false });
        } catch (e) {
            // 404 单独说一句，比笼统的"加载失败"有用得多
            const message = (e.code === 404)
                ? '这篇文章不存在，或者作者把它收回草稿了'
                : '文章加载失败：' + e.message;
            showPageError(box, message, function () { loadArticle(slug); });
            return;
        }

        articleId = data.id;
        document.title = data.title + ' · WN';

        box.innerHTML = ''
            + '<header class="article-head">'
            + '  <h1 class="article-title">' + escapeHtml(data.title) + '</h1>'
            + '  <div class="article-meta">'
            + '    <span>' + formatDate(data.publishedAt) + '</span>'
            + '    <span class="dot">·</span>'
            + '    <span id="viewCount">' + data.viewCount + ' 次浏览</span>'
            + '  </div>'
            + '</header>'
            // ★ 正文用 innerHTML 直接插，不走 escapeHtml。
            //   它是服务端 MarkdownRenderer 渲染出来的 HTML，
            //   危险标签和危险链接已经在服务端处理过了。
            //   全页面只有这一个地方可以这样插。
            + '<div class="article-body">' + (data.contentHtml || '') + '</div>'
            + '<div class="article-actions">'
            + '  <button class="btn btn-primary like-btn" id="likeBtn">'
            + '    <span>赞</span><span class="like-count" id="likeCount">'
            +        data.likeCount + '</span>'
            + '  </button>'
            + '  <span class="text-faint text-sm">觉得有用就点一下</span>'
            + '</div>';

        const likeBtn = $('#likeBtn');
        if (likeBtn) {
            likeBtn.addEventListener('click', doLike);
        }
    }

    /**
     * 点赞。
     *
     * 【注意这是"点一次加一次"】服务端不做任何去重 ——
     * 这是用户明确要求的（不识别真人还是机器人）。
     * 所以这里不禁用按钮、不拦截重复点击，
     * only 用 likedInThisSession 做个"这次浏览点过了"的视觉效果。
     */
    async function doLike() {
        const btn = $('#likeBtn');
        if (!btn || articleId === null) {
            return;
        }

        try {
            const count = await api(
                '/client/api/articles/' + articleId + '/like',
                { method: 'POST', auth: false }
            );

            $('#likeCount').textContent = count;

            if (!likedInThisSession) {
                likedInThisSession = true;
                btn.classList.add('is-liked');
            }
            toast('感谢点赞', 'ok');
        } catch (e) {
            toast('点赞失败：' + e.message, 'error');
        }
    }

    /* ========================================================
       音乐时间轴（左侧那一栏）
       ======================================================== */

    /**
     * 起左栏。
     *
     * 现在是<b>异步</b>的：先去取外挂的歌单，取回来再画卡片。
     * 调用方不用等它 —— 左栏晚几十毫秒没画出来无所谓，
     * 右边文章列表该多快还是多快。
     */
    function initMusicRail() {
        const track = $('#musicTrack');
        if (!track) {
            return Promise.resolve();      // 当前页面没有左栏
        }

        return loadMusicList().then(function (list) {
            MUSIC_LIST = list;
            renderRail(track);
        });
    }

    /**
     * 取歌单。
     *
     * <p>顺序：外挂的 {@code music/list.json} → 内置的 {@code DEFAULT_MUSIC_LIST}。
     * 任何一步出错都静默降级，绝不让左栏变成一片空白。
     *
     * <p>用 {@code cache: 'no-cache'} 而不是默认值：改完 list.json
     * 刷新页面就该看到新歌，不该被浏览器缓存拖一小时。
     */
    async function loadMusicList() {
        try {
            const response = await fetch(MUSIC_LIST_URL, { cache: 'no-cache' });
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }

            const data = await response.json();
            if (!Array.isArray(data)) {
                throw new Error('顶层不是数组');
            }

            const list = data.map(normalizeSong).filter(Boolean);
            if (list.length === 0) {
                throw new Error('一条有效曲目都没有（每条至少要有 title 或 file）');
            }
            return list;

        } catch (e) {
            console.warn('歌单 ' + MUSIC_LIST_URL + ' 没取到（' + e.message
                + '），改用内置的 ' + DEFAULT_MUSIC_LIST.length + ' 首');
            // 【兜底这份也必须过一遍 normalize】
            // DEFAULT_MUSIC_LIST 里写的是 file 而不是 url，
            // 直接 return 的话 togglePlay 拿到的 song.url 是 undefined，
            // 表现就是"卡片点了有反应、但没声音"，而且控制台不报错。
            return DEFAULT_MUSIC_LIST.map(normalizeSong).filter(Boolean);
        }
    }

    /**
     * 把一条歌单数据补齐成渲染需要的形状。
     *
     * <p>灵活点在这里：只写 {@code file} 也行（最常见），只写 {@code url} 也行
     * （留给以后接外链）。两样都没给就返回 null，由调用方过滤掉 ——
     * 宁可少画一张，也不画一张点了没反应的死卡片。
     *
     * @param raw list.json 里的一条原始数据
     * @return 补齐后的对象，或 null（这条数据不可用）
     */
    function normalizeSong(raw) {
        if (!raw) {
            return null;
        }

        const url = raw.url || (raw.file ? '/music/' + raw.file : '');
        if (!url) {
            return null;
        }

        return {
            title: raw.title || '未命名',
            year: raw.year || '',
            cover: raw.cover || null,
            url: url
        };
    }

    /**
     * 把卡片画出来。
     *
     * <p>和取数据分开是有意的：测逻辑的时候（rail-test.mjs）
     * 直接调这个函数就行，不用管网络。
     */
    function renderRail(track) {
        if (MUSIC_LIST.length === 0) {
            track.innerHTML = '<div class="rail-empty">还没有添加音乐。'
                + '<br><br>把 mp3 丢进项目根目录的 music/，'
                + '<br>再把文件名写进 music/list.json。</div>';
            return;
        }

        // 同一批卡片排两遍，第二遍是替身。滚动原理见 measureRail 的注释
        const oneRound = MUSIC_LIST.map(createSongCard).join('');
        track.innerHTML = oneRound + oneRound;

        // 下面三个只在第一次渲染时跑。页面不会重复 init，所以不重复绑
        measureRail(track);
        bindRailClick(track);
        bindPlayerEvents();
    }

    function createSongCard(song, index) {
        // 用下标当 id。不拿歌名当 id —— 歌名会重复，重复了就点错歌
        const title = escapeHtml(song.title || '未命名');
        const year = song.year ? escapeHtml(String(song.year)) : '';

        // 有封面才放 <img>；没有就直接画一个渐变块。
        // 也可以先放 <img> 再用 onerror 兜底，但那会先闪一下破图
        const visual = song.cover
            ? '<img src="' + escapeHtml(song.cover) + '" alt="" loading="lazy">'
            : '<div class="cover-fallback">'
                + escapeHtml((song.title || '♪').charAt(0))
                + '</div>';

        // 逐张错开入场。最多错开到第 10 张 ——
        // 一屏看得到六七张，再多错开就是在干等
        const delay = Math.min(index, 10) * 45;

        return ''
            + '<div class="song-card" data-id="' + index + '"'
            +      ' style="animation-delay:' + delay + 'ms">'
            + visual
            + (year ? '<div class="year-tag">' + year + '</div>' : '')
            + '<div class="play-overlay"><div class="play-icon"></div></div>'
            + '<div class="song-title">' + title + '</div>'
            + '</div>';
    }

    /**
     * 量出"一份"卡片有多高，写进 CSS 变量。
     *
     * 【无缝滚动的原理】
     * 卡片排了两遍，动画从 0 滚到"一份的高度"。滚到终点时，
     * 第二遍正好挪到第一遍原来的位置，画面完全重合，这时把位移
     * 瞬间归零，看不出任何接缝。所以那份位移必须**精确等于**
     * 一份的高度，差几个像素就会看到明显的跳动。
     *
     * 【为什么不用 translateY(-50%)】
     * track 的总高把上下那 96px 的 padding 也算进去了，-50% 相当于
     * 把 padding 劈成两半，算出来的位移比一份的高度多出 48px。
     * 滚到接缝那一瞬间画面会跳 48px，很显眼。
     */
    function measureRail(track) {
        const first = track.querySelector('.song-card');
        if (!first) {
            return;
        }

        // 用 offsetHeight 而不是 getBoundingClientRect：
        // 后者会把 hover 时的 scale(1.3) 算进去，量到的是放大后的高度
        const cardHeight = first.offsetHeight;
        const gap = parseFloat(getComputedStyle(track).rowGap) || 0;
        if (cardHeight <= 0) {
            return;
        }

        // 一份的高度 = 张数 ×（卡片高 + 间距）。
        // 每张卡片后面都跟着一个间距，包括这一份的最后一张 ——
        // 它和下一份第一张之间的那道缝也算数，所以是相乘
        const step = MUSIC_LIST.length * (cardHeight + gap);
        track.style.setProperty('--step', step + 'px');

        // 滚动速度：每秒多少像素。20 首约 3300px，60px/s 大约 55 秒一轮。
        // 嫌快嫌慢只改这个数
        const PIXELS_PER_SECOND = 60;
        track.style.setProperty('--rail-duration',
            (step / PIXELS_PER_SECOND).toFixed(2) + 's');
    }

    /**
     * 点卡片放歌。
     *
     * 事件绑在轨道上，不绑在每张卡片上：卡片是动态生成的，
     * 而且每首歌都有两张（正本 + 克隆），逐张绑要绑两遍还容易漏。
     */
    function bindRailClick(track) {
        track.addEventListener('click', function (event) {
            const card = event.target.closest('.song-card');
            if (!card) {
                return;
            }

            const id = parseInt(card.dataset.id, 10);
            const song = MUSIC_LIST[id];
            if (song) {
                togglePlay(song, id);
            }
        });
    }

    function togglePlay(song, id) {
        const player = $('#audioPlayer');
        if (!player) {
            return;
        }

        // 点的就是当前这首：只切播放/暂停，不重设 src ——
        // 重设 src 会让这首歌从头开始，于是"暂停后继续"变成了"重播"
        if (currentPlayingId === id) {
            if (player.paused) {
                player.play().catch(onPlayFailed);
            } else {
                player.pause();
            }
            return;
        }

        player.src = song.url;
        player.play().catch(onPlayFailed);

        currentPlayingId = id;
        markPlaying(id);
    }

    function onPlayFailed(error) {
        // 浏览器会拦"没有用户操作就自动播放"。这里是点击触发的，
        // 正常走不到这儿；真走到了基本只有一个原因：音频文件不在
        console.warn('播放失败：', error);
        const song = MUSIC_LIST[currentPlayingId];
        toast('这首放不出来。确认文件在 music/ 目录里：' + (song ? song.url : ''), 'error');
    }

    /**
     * 把高亮挪到第 id 首。
     *
     * 同一首歌在页面上有两张卡片（正本和克隆），所以不能只改一张 ——
     * 否则滚到接缝附近会看到"一张亮着、一张没亮"。
     */
    function markPlaying(id) {
        $$('.song-card').forEach(function (card) {
            card.classList.toggle('playing', parseInt(card.dataset.id, 10) === id);
        });
    }

    /**
     * 同步底部控制条：歌名 + 播放/暂停图标。
     *
     * 绑在 audio 的 play / pause 事件上，不在每个播放、暂停的地方手动调。
     * 理由：浏览器保证这两个事件和 player.paused 一致，
     * 而"点了暂停"和"播完了自动停"走的是不同代码路径，
     * 手动调总会漏掉某一条。
     */
    function syncCtrl() {
        const box = $('#railCtrl');
        const nameEl = $('#railCtrlName');
        const btn = $('#railCtrlBtn');
        const player = $('#audioPlayer');
        if (!box || !nameEl || !btn || !player) {
            return;
        }

        const song = currentPlayingId === null ? null : MUSIC_LIST[currentPlayingId];
        if (!song) {
            box.hidden = true;
            return;
        }

        box.hidden = false;
        nameEl.textContent = song.title || '未命名';

        const running = !player.paused;

        // 【为什么用自绘 SVG 而不是 ▶ / ❚❚ 字符】
        // 这两个 Unicode 字符在不同字体（Segoe UI Symbol / Apple Symbols /
        // Noto）里的大小、基线、字重全都不一样，同一个按钮在 Windows 和
        // Mac 上会长得不像同一个东西。画出来的图标才受控。
        const ICON_PLAY = '<svg viewBox="0 0 24 24" aria-hidden="true">'
            + '<path d="M8 5v14l11-7z"/></svg>';
        const ICON_PAUSE = '<svg viewBox="0 0 24 24" aria-hidden="true">'
            + '<rect x="7" y="5" width="3.6" height="14" rx="1"/>'
            + '<rect x="13.4" y="5" width="3.6" height="14" rx="1"/></svg>';

        btn.innerHTML = running ? ICON_PAUSE : ICON_PLAY;
        btn.setAttribute('aria-label', running ? '暂停' : '继续播放');
    }

    /**
     * 一首放完，自动接下一首。
     */
    function playNext() {
        if (currentPlayingId === null || MUSIC_LIST.length === 0) {
            return;
        }

        const next = currentPlayingId + 1;

        // 最后一首放完就停下并清掉高亮，不从头循环 ——
        // 自动循环会让人以为播放器坏了：明明没点它，却一直在响
        if (next >= MUSIC_LIST.length) {
            currentPlayingId = null;
            markPlaying(-1);
            // 最后一首放完不会触发 pause 事件（它是自然 ended），
            // 所以这里得手动收掉底部控制条
            syncCtrl();
            return;
        }

        togglePlay(MUSIC_LIST[next], next);
    }

    function bindPlayerEvents() {
        const player = $('#audioPlayer');
        if (!player) {
            return;
        }

        player.addEventListener('ended', playNext);

        // 播放状态一变就同步控制条。点卡片、点控制条按钮、自动切下一首，
        // 最终都会落到这两个事件上，不用在每个入口手动调
        player.addEventListener('play', syncCtrl);
        player.addEventListener('pause', syncCtrl);

        // 控制条上的播放/暂停按钮。
        // 它只切状态，不改 src —— 改了 src 就等于从头播，
        // "继续播放"会变成"重播"
        const btn = $('#railCtrlBtn');
        if (btn) {
            btn.addEventListener('click', function () {
                if (player.paused) {
                    player.play().catch(onPlayFailed);
                } else {
                    player.pause();
                }
            });
        }
    }
})();
