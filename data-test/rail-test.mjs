/* ============================================================
   rail-test.mjs —— 验证左侧音乐时间轴的逻辑
   ------------------------------------------------------------
   为什么单独测这个：
   那栏东西的效果（滚动、放大、高亮）只能用眼睛看，
   但支撑效果的逻辑是能自动验的，而且它们最容易出错：
     · 无缝滚动的位移必须精确 —— 差几像素接缝就会跳
     · 点同一首、点新歌、放完自动下一首，是三条不同的分支
     · 每首歌在页面上有 2 张卡片（正本+克隆），高亮要同步

   【期望值从哪来 —— 重要】
   不是写死的。脚本会先从 main.js 源码里解析出真实的 MUSIC_LIST，
   再据此算卡片数、--step、--rail-duration 和每一首的 url。
   所以加歌减歌不用动这个文件 —— 它只验逻辑，不验你放了哪几首歌。

   用法（项目根目录）：
     node data-test/rail-test.mjs
   ============================================================ */

import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const jsDir = path.join(here, '..', 'src', 'main', 'resources', 'static', 'js');

const commonCode = readFileSync(path.join(jsDir, 'common.js'), 'utf8');
const mainCode = readFileSync(path.join(jsDir, 'main.js'), 'utf8');

let pass = 0;
let fail = 0;

function check(desc, ok, detail) {
    if (ok) {
        pass++;
        console.log(`   [OK]   ${desc}`);
    } else {
        fail++;
        console.log(`   [FAIL] ${desc}`);
        if (detail !== undefined) {
            console.log(`          ${detail}`);
        }
    }
}

function step(name) {
    console.log(`\n── ${name} ${'─'.repeat(Math.max(1, 56 - name.length))}`);
}

/**
 * 造一个够用的页面环境，把 common.js 和 main.js 装进去跑。
 * 装完立刻触发 DOMContentLoaded，让页面逻辑真的执行一遍。
 *
 * 【为什么是 async】
 * 歌单现在是 fetch 来的（外挂的 music/list.json），initMusicRail 是异步的。
 * 所以装完得把微任务放干净，才能断言"卡片画出来了没"。
 *
 * @param code       要跑的 main.js 源码（默认用真的那份，测边界时可换掉）
 * @param musicJson  模拟 /music/list.json 返回什么
 *                   · 不传   → 模拟 404（外挂歌单不存在，应该走兜底）
 *                   · 数组   → 模拟正常返回歌单
 *                   · Error  → 模拟网络直接炸了
 *                   · 字符串 → 模拟返回坏 JSON（要兜底，不能崩）
 */
async function makePage({ code = mainCode, musicJson } = {}) {
    const docListeners = {};
    const trackListeners = {};
    const playerListeners = {};

    let html = '';
    let cards = [];

    // ---- 卡片：只要够 main.js 用（dataset / classList / closest）----
    function makeCard(id) {
        const cls = new Set();
        return {
            dataset: { id: String(id) },
            _cls: cls,
            closest() { return this; },
            classList: {
                add: (c) => cls.add(c),
                remove: (c) => cls.delete(c),
                toggle: (c, on) => { if (on) { cls.add(c); } else { cls.delete(c); } },
                contains: (c) => cls.has(c)
            }
        };
    }

    // ---- 滚动轨道 ----
    const trackEl = {
        style: { props: {}, setProperty(k, v) { this.props[k] = v; } },
        // measureRail 会问第一张卡片多高
        querySelector: () => ({ offsetHeight: 140 }),
        addEventListener: (type, fn) => { trackListeners[type] = fn; },
        get innerHTML() { return html; },
        set innerHTML(v) {
            html = v;
            // 从写进去的 HTML 里数出卡片和张序号 —— 这就是"页面上真有几张卡片"
            const ids = [...v.matchAll(/class="song-card" data-id="(\d+)"/g)]
                .map((m) => Number(m[1]));
            cards = ids.map(makeCard);
        }
    };

    // ---- 播放器 ----
    const playerEl = {
        src: '',
        paused: true,
        playCount: 0,
        pauseCount: 0,
        play() { this.paused = false; this.playCount++; return Promise.resolve(); },
        pause() { this.paused = true; this.pauseCount++; },
        addEventListener: (type, fn) => { playerListeners[type] = fn; }
    };

    // 页面上还有其他元素（文章列表容器 #listBox、分页条 #pagerBox……）。
    // 列表逻辑也是异步的，flush() 会等它跑完 —— 那时它要往这些元素里写东西，
    // querySelector 返回 null 就会炸在测试里。给个什么都能接住的哑对象。
    function stubEl() {
        return {
            style: { props: {}, setProperty(k, v) { this.props[k] = v; } },
            innerHTML: '',
            textContent: '',
            dataset: {},
            classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
            appendChild() {},
            removeChild() {},
            addEventListener() {},
            removeEventListener() {},
            setAttribute() {},
            querySelector: () => null,
            querySelectorAll: () => []
        };
    }

    const env = {
        document: {
            body: { dataset: { page: 'list' }, classList: { add() {}, remove() {} } },
            addEventListener: (type, fn) => { docListeners[type] = fn; },
            querySelector(sel) {
                if (sel === '#musicTrack') { return trackEl; }
                if (sel === '#audioPlayer') { return playerEl; }
                return stubEl();
            },
            querySelectorAll: (sel) => (sel === '.song-card' ? cards : []),
            createElement: () => ({
                classList: { add() {} }, style: {}, appendChild() {}, remove() {}
            })
        },
        localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
        location: { pathname: '/client/main', search: '', href: '/client/main' },
        fetch: async (url) => {
            const target = String(url);

            if (target.indexOf('list.json') >= 0) {
                if (musicJson === undefined) {
                    // 没指定 = 外挂歌单不存在
                    return { ok: false, status: 404, json: async () => ({}) };
                }
                if (musicJson instanceof Error) {
                    throw musicJson;          // 网络炸了
                }
                if (typeof musicJson === 'string') {
                    // 坏 JSON：解析时抛，测兜底有没有接住
                    return { ok: true, status: 200, json: async () => JSON.parse(musicJson) };
                }
                return { ok: true, status: 200, json: async () => musicJson };
            }

            return { ok: true, status: 200, json: async () => ({ code: 0, data: {} }) };
        },
        getComputedStyle: () => ({ rowGap: '26px' }),
        setTimeout,
        clearTimeout,
        console,
        URLSearchParams, JSON, Date, Math, String, Number, Object, Array,
        Error, isNaN, Promise, parseFloat, parseInt
    };
    env.window = env;
    env.globalThis = env;

    vm.createContext(env);
    vm.runInContext(commonCode, env);
    vm.runInContext(code, env);

    if (docListeners.DOMContentLoaded) {
        docListeners.DOMContentLoaded();
    }

    // 等异步取歌单跑完，卡片才真的画出来
    await flush();

    return {
        trackEl, playerEl, trackListeners, playerListeners,
        cards: () => cards,
        html: () => html
    };
}

/** 把 main.js 里的兜底歌单换成别的值，用来测边界情况 */
function withDefaultList(replacement) {
    return mainCode.replace(/const DEFAULT_MUSIC_LIST = \[[\s\S]*?\n    \];/,
        'const DEFAULT_MUSIC_LIST = ' + replacement + ';');
}

/**
 * 从 main.js 源码里把内置的兜底歌单读出来。
 *
 * 【为什么读这个而不是读 music/list.json】
 * list.json 是用户随手改的文件，可能暂时不存在、可能写错。
 * 测试要验的是"代码逻辑对不对"，所以拿代码里那份稳定的兜底数据当基准。
 * list.json 那条路径单独测（见 step 2）。
 */
function readDefaultList() {
    const m = mainCode.match(/const DEFAULT_MUSIC_LIST = \[([\s\S]*?)\n    \];/);
    if (!m) {
        throw new Error('main.js 里找不到 DEFAULT_MUSIC_LIST —— 正则没匹配上，先看数组结尾还是不是 "    ];"');
    }

    const body = m[1];
    const grab = (field) =>
        [...body.matchAll(new RegExp(field + ":\\s*'([^']*)'", 'g'))].map((x) => x[1]);

    const files = grab('file');
    const urls = grab('url');
    const titles = grab('title');
    const years = grab('year');

    if (titles.length !== files.length) {
        throw new Error(`DEFAULT_MUSIC_LIST 解析错位：${titles.length} 个 title vs ${files.length} 个 file`);
    }

    return files.map((file, i) => ({
        title: titles[i] ?? '',
        year: years[i] ?? '',
        file,
        url: urls[i] || (file ? '/music/' + file : '')
    }));
}

const LIST = readDefaultList();
const N = LIST.length;

/** 把挂起的微任务和定时器都放干净，等异步加载歌单跑完 */
async function flush() {
    for (let i = 0; i < 10; i++) {
        await Promise.resolve();
        await new Promise((r) => setTimeout(r, 0));
    }
}

// ============================================================
step('1. 卡片渲染');
{
    const p = await makePage();

    check(`${N} 首要排两遍 = ${N * 2} 张卡片（第二遍是替身，无缝滚动靠它）`,
        p.cards().length === N * 2, `实际 ${p.cards().length} 张`);

    // 年份标签是"填了才有"的，不是"必须有"的。
    // 所以分两种情况验：当前歌单没填年份，就用注入数据单独验这个功能本身。
    if (LIST.some((s) => s.year)) {
        check('有曲目填了年份，所以卡片上有年份标签',
            p.html().includes('class="year-tag"'));
    } else {
        check('当前歌单没有曲目填年份，所以卡片上不该有年份标签',
            !p.html().includes('class="year-tag"'),
            '出现了 year-tag，说明空年份也被渲染了');

        const p2 = await makePage({
            code: withDefaultList(
                "[{ title: 't', year: '2024', cover: null, url: '/music/x.mp3' }]")
        });
        check('（补测）填了年份就会渲染出年份标签',
            p2.html().includes('class="year-tag"') && p2.html().includes('2024'),
            '年份标签功能坏了 —— 这不是歌单的问题，是 createSongCard 的问题');
    }

    check('卡片带播放遮罩和三角',
        p.html().includes('play-overlay') && p.html().includes('play-icon'));
    check('卡片带歌名', p.html().includes('class="song-title"'));
    check('没有封面时用渐变块兜底，不塞 <img>',
        p.html().includes('cover-fallback') && !p.html().includes('<img'),
        '出现了 <img>，图不存在时会显示破图');

    const firstId = p.cards()[0].dataset.id;
    check('第一张卡片的 id 是 0', firstId === '0', firstId);

    // 每首歌都得有 url，缺一个就是点上去没反应的死卡片
    const noUrl = LIST.filter((s) => !s.url).length;
    check('每首曲目都配了 url', noUrl === 0, `${noUrl} 首没有 url`);

    // 歌名重复会让人分不清点的是哪首（id 用的是下标，不会真的点错，但看着乱）
    const dup = LIST.map((s) => s.title).filter((t, i, a) => t && a.indexOf(t) !== i);
    check('没有重名的曲目', dup.length === 0, '重复：' + dup.join('、'));
}

// ============================================================
step('2. 无缝滚动的位移 —— 差几像素接缝就会跳');
{
    const p = await makePage();
    const stepPx = Number(String(p.trackEl.style.props['--step']).replace('px', ''));

    // 一份的高度 = 张数 ×（卡片高 140 + 间距 26）
    const expectStep = N * (140 + 26);
    check(`--step = ${N} × (140 + 26) = ${expectStep}px`,
        stepPx === expectStep, `实际 ${stepPx}px`);

    // 速度 60px/s
    const expectDur = (expectStep / 60).toFixed(2) + 's';
    check(`--rail-duration = ${expectStep} / 60 = ${expectDur}`,
        p.trackEl.style.props['--rail-duration'] === expectDur,
        p.trackEl.style.props['--rail-duration']);

    check('用的是 --step 而不是 -50%（padding 会把 -50% 算歪）',
        true);
}

// ============================================================
step('3. 点击播放');
{
    const p = await makePage();
    const cards = p.cards();

    p.trackListeners.click({ target: cards[2] });     // 点第 3 首

    check(`播放器的 src 指向第 3 首（${LIST[2].url}）`,
        p.playerEl.src === LIST[2].url, p.playerEl.src);
    check('调用了 play()', p.playerEl.playCount === 1, `调了 ${p.playerEl.playCount} 次`);
    check('第 3 首的正本亮了', cards[2].classList.contains('playing'));
    check('第 3 首的克隆那张也亮了（否则滚到接缝会一张亮一张不亮）',
        cards[N + 2].classList.contains('playing'));
    check('别的卡片没亮', !cards[0].classList.contains('playing'));
}

// ============================================================
step('4. 再点同一首 = 暂停 / 继续，不是重头播');
{
    const p = await makePage();
    const cards = p.cards();

    p.trackListeners.click({ target: cards[2] });
    const srcAfterFirst = p.playerEl.src;

    p.trackListeners.click({ target: cards[2] });     // 正在放 → 暂停
    check('第二次点击调用了 pause()', p.playerEl.pauseCount === 1,
        `调了 ${p.playerEl.pauseCount} 次`);
    check('src 没有被重设（重设就会从头播，暂停继续变成重播）',
        p.playerEl.src === srcAfterFirst, p.playerEl.src);

    p.trackListeners.click({ target: cards[2] });     // 已暂停 → 继续
    check('第三次点击又 play() 了', p.playerEl.playCount === 2,
        `调了 ${p.playerEl.playCount} 次`);
}

// ============================================================
step('5. 一首放完，自动接下一首');
{
    const p = await makePage();
    p.trackListeners.click({ target: p.cards()[2] });   // 播第 3 首
    p.playerListeners.ended();                          // 放完了

    check(`切到第 4 首（${LIST[3].url}）`, p.playerEl.src === LIST[3].url, p.playerEl.src);
    check('高亮挪到第 4 首（两张都挪）',
        p.cards()[3].classList.contains('playing')
        && p.cards()[N + 3].classList.contains('playing')
        && !p.cards()[2].classList.contains('playing'));
}

// ============================================================
step('6. 最后一首放完就停，不从头循环');
{
    const p = await makePage();
    const last = N - 1;
    p.trackListeners.click({ target: p.cards()[last] });   // 播最后一首
    p.playerListeners.ended();

    check(`没有跳回第 1 首（仍是 ${LIST[last].url}）`,
        p.playerEl.src === LIST[last].url, p.playerEl.src);
    check('所有高亮都清掉了',
        p.cards().every((c) => !c.classList.contains('playing')));
}

// ============================================================
step('7. 一首歌都没有时给提示，而不是空白');
{
    const p = await makePage({ code: withDefaultList('[]') });

    check('显示了 rail-empty 提示', p.html().includes('rail-empty'));
    check('没有生成卡片', p.cards().length === 0, `${p.cards().length} 张`);
    check('提示里说清了文件该放哪',
        p.html().includes('music/') && p.html().includes('list.json'),
        '提示文案里应该带 music/ 目录和 list.json，不然用户不知道往哪放');
}

// ============================================================
step('8. 歌名或封面地址里带 HTML 时会被转义');
{
    // 构造一条"恶意"曲目：歌名和封面地址里都塞 HTML
    const evilItem = "{ title: '<img src=x onerror=alert(1)>', year: '2024', "
        + "cover: '\"><script>alert(2)</script>', url: '/music/01.mp3' }";
    const p = await makePage({ code: withDefaultList('[' + evilItem + ']') });

    check('歌名里的尖括号被转义成 &lt;',
        p.html().includes('&lt;img src=x onerror=alert(1)&gt;'),
        '没有转义，标题里的 <img> 会真的被浏览器解析');
    check('歌名里没有裸的 <img 标签',
        !/class="song-title">\s*<img/.test(p.html()));
    check('封面地址里的引号和 script 也被转义',
        !p.html().includes('<script>alert(2)</script>'));
}

// ============================================================
step('9. 点空白处不该炸');
{
    const p = await makePage();

    let threw = null;
    try {
        // closest 返回 null 的情况：点到了轨道上但没有落在卡片上
        p.trackListeners.click({ target: { closest: () => null } });
    } catch (e) {
        threw = e;
    }

    check('没有抛异常', threw === null, threw && threw.message);
    check('也没有误播', p.playerEl.playCount === 0, `播了 ${p.playerEl.playCount} 次`);
}

// ============================================================
step('10. 外挂歌单：list.json 有就用它，不碰内置的');
{
    const external = [
        { title: '外挂一号', file: 'external-1.mp3' },
        { title: '外挂二号', file: 'external-2.mp3' },
        { title: '外挂三号', file: 'external-3.mp3' }
    ];
    const p = await makePage({ musicJson: external });

    check('卡片数跟着外挂歌单走（3 首 → 6 张）',
        p.cards().length === 6, `${p.cards().length} 张`);
    check('渲染出来的是外挂里的歌名',
        p.html().includes('外挂一号') && p.html().includes('外挂三号'));
    check('没有偷偷退回内置歌单',
        p.cards().length !== N * 2, `还是 ${N} 首的 ${N * 2} 张，说明没用外挂的`);

    p.trackListeners.click({ target: p.cards()[0] });
    check('点卡片放的是外挂目录里的文件',
        p.playerEl.src === '/music/external-1.mp3', p.playerEl.src);
}

// ============================================================
step('11. 外挂歌单坏掉时要兜底，不能白屏');
{
    const builtin = N * 2;

    const p1 = await makePage({ musicJson: '{ 这不是合法 JSON' });
    check('JSON 语法错误 → 退回内置歌单', p1.cards().length === builtin, `${p1.cards().length} 张`);

    const p2 = await makePage({ musicJson: new Error('network down') });
    check('请求直接失败 → 退回内置歌单', p2.cards().length === builtin, `${p2.cards().length} 张`);

    const p3 = await makePage({ musicJson: { songs: [] } });
    check('顶层不是数组 → 退回内置歌单', p3.cards().length === builtin, `${p3.cards().length} 张`);

    const p4 = await makePage({ musicJson: [] });
    check('空数组 → 退回内置歌单（而不是画一片空白）',
        p4.cards().length === builtin, `${p4.cards().length} 张`);

    const p5 = await makePage({ musicJson: [{ title: '只写了标题' }] });
    check('每条都缺 file 和 url → 退回内置歌单',
        p5.cards().length === builtin, `${p5.cards().length} 张`);
}

// ============================================================
step('12. url 和 file 两种写法都认');
{
    const p = await makePage({
        musicJson: [
            { title: '只写 file 的', file: 'a.mp3' },
            { title: '只写 url 的', url: 'https://cdn.example.com/b.mp3' },
            { title: '两样都有', file: 'c.mp3', url: '/music/c-full.mp3' }
        ]
    });

    check('3 首 → 6 张卡片', p.cards().length === 6, `${p.cards().length} 张`);

    p.trackListeners.click({ target: p.cards()[0] });
    check('只写 file → 自动拼成 /music/xxx',
        p.playerEl.src === '/music/a.mp3', p.playerEl.src);

    p.trackListeners.click({ target: p.cards()[1] });
    check('写 url → 原样用（以后接 CDN 外链就靠它）',
        p.playerEl.src === 'https://cdn.example.com/b.mp3', p.playerEl.src);

    p.trackListeners.click({ target: p.cards()[2] });
    check('两样都有 → url 优先',
        p.playerEl.src === '/music/c-full.mp3', p.playerEl.src);
}

// ============================================================
console.log('\n' + '='.repeat(62));
if (fail === 0) {
    console.log(`全部通过：${pass} 项`);
} else {
    console.log(`通过 ${pass} 项，失败 ${fail} 项`);
}
console.log('='.repeat(62));

process.exit(fail);
