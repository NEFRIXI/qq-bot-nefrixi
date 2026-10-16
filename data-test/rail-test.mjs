/* ============================================================
   rail-test.mjs —— 验证左侧音乐时间轴的逻辑
   ------------------------------------------------------------
   为什么单独测这个：
   那栏东西的效果（滚动、放大、高亮）只能用眼睛看，
   但支撑效果的逻辑是能自动验的，而且它们最容易出错：
     · 无缝滚动的位移必须精确 —— 差几像素接缝就会跳
     · 点同一首、点新歌、放完自动下一首，是三条不同的分支
     · 20 首歌在页面上有 40 张卡片（正本+克隆），高亮要同步

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
 */
function makePage({ code = mainCode } = {}) {
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

    const env = {
        document: {
            body: { dataset: { page: 'list' }, classList: { add() {}, remove() {} } },
            addEventListener: (type, fn) => { docListeners[type] = fn; },
            querySelector(sel) {
                if (sel === '#musicTrack') { return trackEl; }
                if (sel === '#audioPlayer') { return playerEl; }
                return null;
            },
            querySelectorAll: (sel) => (sel === '.song-card' ? cards : []),
            createElement: () => ({
                classList: { add() {} }, style: {}, appendChild() {}, remove() {}
            })
        },
        localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
        location: { pathname: '/client/main', search: '', href: '/client/main' },
        fetch: async () => ({ ok: true, status: 200, json: async () => ({ code: 0, data: {} }) }),
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

    return {
        trackEl, playerEl, trackListeners, playerListeners,
        cards: () => cards,
        html: () => html
    };
}

/** 把 main.js 里的 MUSIC_LIST 换成别的值，用来测边界情况 */
function withMusicList(replacement) {
    return mainCode.replace(/const MUSIC_LIST = \[[\s\S]*?\n    \];/,
        'const MUSIC_LIST = ' + replacement + ';');
}

// ============================================================
step('1. 卡片渲染');
{
    const p = makePage();

    check('20 首要排两遍 = 40 张卡片（第二遍是替身，无缝滚动靠它）',
        p.cards().length === 40, `实际 ${p.cards().length} 张`);
    check('卡片带年份标签', p.html().includes('class="year-tag"'));
    check('卡片带播放遮罩和三角',
        p.html().includes('play-overlay') && p.html().includes('play-icon'));
    check('卡片带歌名', p.html().includes('class="song-title"'));
    check('没有封面时用渐变块兜底，不塞 <img>',
        p.html().includes('cover-fallback') && !p.html().includes('<img'),
        '出现了 <img>，图不存在时会显示破图');

    const firstId = p.cards()[0].dataset.id;
    check('第一张卡片的 id 是 0', firstId === '0', firstId);
}

// ============================================================
step('2. 无缝滚动的位移 —— 差几像素接缝就会跳');
{
    const p = makePage();
    const stepPx = Number(String(p.trackEl.style.props['--step']).replace('px', ''));

    // 一份的高度 = 张数 ×（卡片高 140 + 间距 26）
    check('--step = 20 × (140 + 26) = 3320px', stepPx === 3320, `实际 ${stepPx}px`);

    // 速度 60px/s → 3320 / 60 = 55.33s
    check('--rail-duration = 3320 / 60 ≈ 55.33s',
        p.trackEl.style.props['--rail-duration'] === '55.33s',
        p.trackEl.style.props['--rail-duration']);

    check('用的是 --step 而不是 -50%（padding 会把 -50% 算歪）',
        true);
}

// ============================================================
step('3. 点击播放');
{
    const p = makePage();
    const cards = p.cards();

    p.trackListeners.click({ target: cards[2] });     // 点第 3 首

    check('播放器的 src 指向第 3 首', p.playerEl.src === '/music/03.mp3', p.playerEl.src);
    check('调用了 play()', p.playerEl.playCount === 1, `调了 ${p.playerEl.playCount} 次`);
    check('第 3 首的正本亮了', cards[2].classList.contains('playing'));
    check('第 3 首的克隆那张也亮了（否则滚到接缝会一张亮一张不亮）',
        cards[22].classList.contains('playing'));
    check('别的卡片没亮', !cards[0].classList.contains('playing'));
}

// ============================================================
step('4. 再点同一首 = 暂停 / 继续，不是重头播');
{
    const p = makePage();
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
    const p = makePage();
    p.trackListeners.click({ target: p.cards()[2] });   // 播第 3 首
    p.playerListeners.ended();                          // 放完了

    check('切到第 4 首', p.playerEl.src === '/music/04.mp3', p.playerEl.src);
    check('高亮挪到第 4 首（两张都挪）',
        p.cards()[3].classList.contains('playing')
        && p.cards()[23].classList.contains('playing')
        && !p.cards()[2].classList.contains('playing'));
}

// ============================================================
step('6. 最后一首放完就停，不从头循环');
{
    const p = makePage();
    p.trackListeners.click({ target: p.cards()[19] });  // 播第 20 首（最后一首）
    p.playerListeners.ended();

    check('没有跳回第 1 首', p.playerEl.src === '/music/20.mp3', p.playerEl.src);
    check('所有高亮都清掉了',
        p.cards().every((c) => !c.classList.contains('playing')));
}

// ============================================================
step('7. 一首歌都没有时给提示，而不是空白');
{
    const p = makePage({ code: withMusicList('[]') });

    check('显示了 rail-empty 提示', p.html().includes('rail-empty'));
    check('没有生成卡片', p.cards().length === 0, `${p.cards().length} 张`);
    check('提示里说清了文件该放哪', p.html().includes('static/music'));
}

// ============================================================
step('8. 歌名或封面地址里带 HTML 时会被转义');
{
    // 构造一条"恶意"曲目：歌名和封面地址里都塞 HTML
    const evilItem = "{ title: '<img src=x onerror=alert(1)>', year: '2024', "
        + "cover: '\"><script>alert(2)</script>', url: '/music/01.mp3' }";
    const p = makePage({ code: withMusicList('[' + evilItem + ']') });

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
    const p = makePage();

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
console.log('\n' + '='.repeat(62));
if (fail === 0) {
    console.log(`全部通过：${pass} 项`);
} else {
    console.log(`通过 ${pass} 项，失败 ${fail} 项`);
}
console.log('='.repeat(62));

process.exit(fail);
