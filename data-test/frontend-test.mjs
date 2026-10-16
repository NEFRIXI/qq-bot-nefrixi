/* ============================================================
   frontend-test.mjs —— 用 node 验证前端公共逻辑（static/js/common.js）
   ------------------------------------------------------------
   为什么要单独测这个文件：
   后端接口有 e2e 脚本兜着，但前端那些函数（api、escapeHtml、
   时间格式化）在浏览器里出错时，表现是"页面白着"或者
   "点了没反应"，很难定位。而它们其实是纯函数，
   脱离浏览器就能验。

   用法（在项目根目录）：
     node data-test/frontend-test.mjs
   ============================================================ */

import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const commonJsPath = path.join(here, '..', 'src', 'main', 'resources', 'static', 'js', 'common.js');
const code = readFileSync(commonJsPath, 'utf8');

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
    console.log(`\n── ${name} ${'─'.repeat(Math.max(1, 58 - name.length))}`);
}

/**
 * 造一个假的浏览器环境，把 common.js 装进去。
 *
 * 只 mock 这个文件真正用到的那几样：localStorage、location、
 * document、fetch、setTimeout。多 mock 反而会掩盖问题。
 */
function makeEnv({ fetchImpl, pathname = '/admin', search = '' } = {}) {
    const store = new Map();

    const env = {
        localStorage: {
            getItem: (k) => (store.has(k) ? store.get(k) : null),
            setItem: (k, v) => store.set(k, String(v)),
            removeItem: (k) => store.delete(k)
        },
        location: { pathname, search, href: pathname },
        document: {
            querySelector: () => null,
            createElement: () => ({
                classList: { add() {} },
                style: {},
                appendChild() {},
                remove() {}
            }),
            body: { appendChild() {} }
        },
        fetch: fetchImpl || (async () => { throw new Error('没给 fetch 实现'); }),
        setTimeout,
        clearTimeout,
        console,
        URLSearchParams,
        JSON,
        Date,
        Math,
        String,
        Number,
        Object,
        Array,
        Error,
        isNaN
    };

    vm.createContext(env);

    // 顶层 const / class 不会挂到 vm 的全局对象上，所以在末尾手动导出一份
    const exporter = `
        globalThis.__x = {
            escapeHtml, parseTime, formatDate, formatTime, formatRelative,
            api, saveToken, getToken, getNickname, clearToken, hasToken,
            isLoginPage, getQuery, textToHtml, ApiError
        };
    `;
    vm.runInContext(code + exporter, env);

    return env;
}

/** 造一个"服务端返回了 JSON"的假响应 */
function jsonResponse(body, { ok = true, status = 200 } = {}) {
    return {
        ok,
        status,
        json: async () => body
    };
}

// ============================================================
step('1. escapeHtml —— 往页面里插文字时必须挡住 XSS');
{
    const { __x: x } = makeEnv();

    check('尖括号被转义',
        x.escapeHtml('<script>alert(1)</script>') === '&lt;script&gt;alert(1)&lt;/script&gt;',
        x.escapeHtml('<script>alert(1)</script>'));

    check('双引号被转义（能防住属性里的注入）',
        x.escapeHtml('a"b') === 'a&quot;b', x.escapeHtml('a"b'));

    check('单引号被转义',
        x.escapeHtml("a'b") === 'a&#39;b', x.escapeHtml("a'b"));

    check('& 被转义，且不会把已转义的再转一遍',
        x.escapeHtml('a&b') === 'a&amp;b', x.escapeHtml('a&b'));

    check('null 返回空串而不是 "null" 四个字',
        x.escapeHtml(null) === '', JSON.stringify(x.escapeHtml(null)));

    check('undefined 返回空串',
        x.escapeHtml(undefined) === '', JSON.stringify(x.escapeHtml(undefined)));

    check('数字能正常转成文字', x.escapeHtml(42) === '42', x.escapeHtml(42));

    check('转义顺序正确（& 先处理，不会产生双重转义）',
        x.escapeHtml('<a & b>') === '&lt;a &amp; b&gt;', x.escapeHtml('<a & b>'));
}

// ============================================================
step('2. parseTime —— 后端给的时间格式必须能解析');
{
    const { __x: x } = makeEnv();

    // 后端返回的是 "2026-10-17 00:55:04"（中间是空格，不是 T）
    const d1 = x.parseTime('2026-10-17 00:55:04');
    check('能解析带空格的后端格式', d1 instanceof Date && d1.getFullYear() === 2026,
        String(d1));

    const d2 = x.parseTime('2026-10-17T00:55:04');
    check('也能解析标准 ISO 格式', d2 instanceof Date && d2.getFullYear() === 2026,
        String(d2));

    check('两种格式解析出的是同一个时刻',
        d1 && d2 && d1.getTime() === d2.getTime(),
        `${d1 && d1.getTime()} vs ${d2 && d2.getTime()}`);

    check('识别不了的字符串返回 null（而不是 Invalid Date）',
        x.parseTime('这不是时间') === null, String(x.parseTime('这不是时间')));

    check('null 返回 null', x.parseTime(null) === null, String(x.parseTime(null)));

    check('空串返回 null', x.parseTime('') === null, String(x.parseTime('')));

    check('已经是 Date 就原样返回', x.parseTime(d1) === d1, '没有原样返回');
}

// ============================================================
step('3. 时间格式化');
{
    const { __x: x } = makeEnv();

    check('formatDate 只给日期',
        x.formatDate('2026-10-17 00:55:04') === '2026-10-17',
        x.formatDate('2026-10-17 00:55:04'));

    check('formatTime 给到分钟',
        x.formatTime('2026-10-17 00:55:04') === '2026-10-17 00:55',
        x.formatTime('2026-10-17 00:55:04'));

    check('formatDate 解析不了时返回原文（不会显示成空白）',
        x.formatDate('坏数据') === '坏数据', x.formatDate('坏数据'));

    const now = Date.now();
    const ago = (ms) => new Date(now - ms).toISOString();

    check('30 秒前 → 刚刚', x.formatRelative(ago(30 * 1000)) === '刚刚',
        x.formatRelative(ago(30 * 1000)));
    check('5 分钟前', x.formatRelative(ago(5 * 60 * 1000)) === '5 分钟前',
        x.formatRelative(ago(5 * 60 * 1000)));
    check('3 小时前', x.formatRelative(ago(3 * 3600 * 1000)) === '3 小时前',
        x.formatRelative(ago(3 * 3600 * 1000)));
    check('2 天前', x.formatRelative(ago(2 * 86400 * 1000)) === '2 天前',
        x.formatRelative(ago(2 * 86400 * 1000)));
    check('40 天前 → 直接给日期（不再说"40 天前"）',
        x.formatRelative(ago(40 * 86400 * 1000)).length === 10,
        x.formatRelative(ago(40 * 86400 * 1000)));

    // 服务器和本机时间有偏差时，会算出"未来"的时间
    const future = new Date(now + 60 * 1000).toISOString();
    check('未来时间显示"刚刚"而不是负数',
        x.formatRelative(future) === '刚刚', x.formatRelative(future));
}

// ============================================================
step('4. textToHtml 和 getQuery');
{
    const { __x: x } = makeEnv({ search: '?page=3&status=1' });

    check('换行变成 <br>', x.textToHtml('第一行\n第二行') === '第一行<br>第二行',
        x.textToHtml('第一行\n第二行'));

    check('换行的同时仍然转义',
        x.textToHtml('<b>\n</b>') === '&lt;b&gt;<br>&lt;/b&gt;',
        x.textToHtml('<b>\n</b>'));

    check('getQuery 取得到参数', x.getQuery('page') === '3', String(x.getQuery('page')));

    check('getQuery 取不存在的参数返回 null',
        x.getQuery('nope') === null, String(x.getQuery('nope')));
}

// ============================================================
step('5. 令牌的存取');
{
    const { __x: x } = makeEnv();

    check('一开始没有令牌', x.hasToken() === false, String(x.hasToken()));

    x.saveToken('tok-123', '站长');
    check('存进去之后取得到', x.getToken() === 'tok-123', x.getToken());
    check('昵称也存下来了', x.getNickname() === '站长', x.getNickname());
    check('hasToken 变 true', x.hasToken() === true, String(x.hasToken()));

    x.clearToken();
    check('清掉之后取不到', x.getToken() === '', JSON.stringify(x.getToken()));
    check('昵称也一起清了', x.getNickname() === '', JSON.stringify(x.getNickname()));
}

// ============================================================
step('6. api() —— 成功路径');
{
    let seenUrl = null;
    let seenOpts = null;

    const env = makeEnv({
        fetchImpl: async (url, opts) => {
            seenUrl = url;
            seenOpts = opts;
            return jsonResponse({ code: 0, message: 'ok', data: { id: 7, title: '标题' } });
        }
    });
    const x = env.__x;

    x.saveToken('tok-abc');
    const data = await x.api('/admin/api/articles/7');

    check('直接返回 data 部分（不用自己拆 code/message）',
        data && data.id === 7, JSON.stringify(data));
    check('请求地址原样传下去', seenUrl === '/admin/api/articles/7', String(seenUrl));
    check('自动带上了 Authorization 头',
        seenOpts.headers['Authorization'] === 'Bearer tok-abc',
        JSON.stringify(seenOpts.headers));
    check('默认方法是 GET', seenOpts.method === 'GET', seenOpts.method);
}

// ============================================================
step('7. api() —— 关闭 auth 时不该带令牌（前台接口用）');
{
    let seenOpts = null;
    const env = makeEnv({
        fetchImpl: async (u, o) => {
            seenOpts = o;
            return jsonResponse({ code: 0, data: [] });
        }
    });
    env.__x.saveToken('tok-abc');

    await env.__x.api('/client/api/articles', { auth: false });

    check('没有带 Authorization 头',
        seenOpts.headers['Authorization'] === undefined,
        JSON.stringify(seenOpts.headers));
}

// ============================================================
step('8. api() —— POST 时自动 JSON 序列化');
{
    let seenOpts = null;
    const env = makeEnv({
        fetchImpl: async (u, o) => {
            seenOpts = o;
            return jsonResponse({ code: 0, data: 1 });
        }
    });

    await env.__x.api('/admin/api/articles', {
        method: 'POST',
        body: { title: '标题', status: 1 }
    });

    check('方法传对了', seenOpts.method === 'POST', seenOpts.method);
    check('Content-Type 带 charset（中文才不会乱码）',
        String(seenOpts.headers['Content-Type']).toLowerCase().includes('charset=utf-8'),
        seenOpts.headers['Content-Type']);
    check('body 被序列化成了 JSON 字符串',
        typeof seenOpts.body === 'string' && JSON.parse(seenOpts.body).title === '标题',
        String(seenOpts.body));
}

// ============================================================
step('9. api() —— 401 的处理（这是最要紧的一条）');
{
    const env = makeEnv({
        fetchImpl: async () => jsonResponse({ code: 401, message: '登录已失效，请重新登录' })
    });
    const x = env.__x;
    x.saveToken('过期的令牌');

    let thrown = null;
    try {
        await x.api('/admin/api/me');
    } catch (e) {
        thrown = e;
    }

    check('抛出了异常', thrown !== null, '居然没抛');
    check('异常类型是 ApiError', thrown && thrown.name === 'ApiError',
        thrown && thrown.name);
    check('业务码是 401', thrown && thrown.code === 401, thrown && thrown.code);
    check('异常里带着服务端的话', thrown && thrown.message === '登录已失效，请重新登录',
        thrown && thrown.message);
    check('本地令牌被清掉了', x.getToken() === '', JSON.stringify(x.getToken()));
    check('浏览器被送去登录页（并且记下了原来要去哪）',
        env.location.href.startsWith('/admin/login?redirect='),
        env.location.href);
}

// ============================================================
step('10. 已经在登录页时，401 不该再跳一次（否则会死循环）');
{
    const env = makeEnv({
        pathname: '/admin/login',
        fetchImpl: async () => jsonResponse({ code: 401, message: '用户名或密码不对' })
    });

    let thrown = null;
    try {
        // 登录接口就是这么调的：redirectOn401: false
        await env.__x.api('/admin/api/login', {
            method: 'POST',
            body: { username: 'a', password: 'b' },
            auth: false,
            redirectOn401: false
        });
    } catch (e) {
        thrown = e;
    }

    check('仍然抛异常（错误提示要显示出来）', thrown !== null, '没抛');
    check('消息是服务端的原话，没被替换成"登录已失效"',
        thrown && thrown.message === '用户名或密码不对', thrown && thrown.message);
    check('没有发生跳转', env.location.href === '/admin/login', env.location.href);

    check('isLoginPage 能认出登录页', env.__x.isLoginPage() === true, '没认出来');
}

// ============================================================
step('11. api() —— 其它出错情况');
{
    // 业务码非 0
    {
        const env = makeEnv({
            fetchImpl: async () => jsonResponse({ code: 404, message: '文章不存在' })
        });
        let thrown = null;
        try { await env.__x.api('/client/api/articles/nope'); } catch (e) { thrown = e; }
        check('业务错误抛出服务端的提示语',
            thrown && thrown.message === '文章不存在' && thrown.code === 404,
            thrown && `${thrown.code} / ${thrown.message}`);
    }

    // 网络层失败。
    // 这里盯的是"报错信息里有没有诊断线索"：原来只写一句
    // "网络请求失败，请检查连接"，断网、被扩展拦掉、地址拼错
    // 全都长一个样，拿到报错的人根本没法往下查。
    {
        const env = makeEnv({
            fetchImpl: async () => { throw new TypeError('Failed to fetch'); }
        });
        let thrown = null;
        try { await env.__x.api('/client/api/articles'); } catch (e) { thrown = e; }

        check('网络失败时把浏览器给的原始原因带出来了',
            thrown && thrown.message.includes('Failed to fetch'),
            thrown && thrown.message);
        check('顺带带上请求地址（地址拼错时一眼能看出来）',
            thrown && thrown.message.includes('/client/api/articles'),
            thrown && thrown.message);
        check('开头仍是人话，不是直接甩一句 Failed to fetch',
            thrown && thrown.message.startsWith('连不上服务器'),
            thrown && thrown.message);
        check('网络失败的 code 是 0', thrown && thrown.code === 0, thrown && thrown.code);
    }

    // HTTP 500
    {
        const env = makeEnv({
            fetchImpl: async () => jsonResponse({}, { ok: false, status: 500 })
        });
        let thrown = null;
        try { await env.__x.api('/x'); } catch (e) { thrown = e; }
        check('HTTP 非 2xx 时报出状态码',
            thrown && thrown.message.includes('500'), thrown && thrown.message);
    }

    // 返回的不是 JSON
    {
        const env = makeEnv({
            fetchImpl: async () => ({
                ok: true, status: 200,
                json: async () => { throw new SyntaxError('Unexpected token <'); }
            })
        });
        let thrown = null;
        try { await env.__x.api('/x'); } catch (e) { thrown = e; }
        check('返回 HTML 错误页时给出可理解的提示',
            thrown && thrown.message.includes('不是 JSON'), thrown && thrown.message);
    }
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
