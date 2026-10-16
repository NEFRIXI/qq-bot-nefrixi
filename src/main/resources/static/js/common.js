/* ============================================================
   common.js —— 所有页面共用的工具
   ------------------------------------------------------------
   这里放四样东西，页面自己的逻辑放各自的 js 里：
     ① api()        —— 统一请求封装（自动带令牌、统一处理 401、统一报错）
     ② escapeHtml() —— 往页面里插文字时的防 XSS 转义
     ③ 时间格式化
     ④ toast()      —— 轻量提示，不引第三方库

   【为什么每个页面都要引它】
   用户要求"提前写好公用 JS 函数"，就是为了避免每个页面各写一份
   ——那样一旦发现 401 的处理有问题，要改四个地方。
   ============================================================ */

/* ============================================================
   1. 令牌的存取
   ------------------------------------------------------------
   存在 localStorage 里。为什么不用 Cookie：
     · Cookie 会被浏览器自动带上，意味着别的网站伪造一个请求
       也能蹭到你的登录状态（CSRF）
     · localStorage 里的东西只有本站的 JS 能读到，请求时
       必须由 JS 显式放进 Authorization 头
   代价是刷新页面后需要 JS 再取一次 —— 可接受。
   ============================================================ */
const TOKEN_KEY = 'mybotweb_admin_token';
const NICKNAME_KEY = 'mybotweb_admin_nickname';

/** 保存登录令牌 */
function saveToken(token, nickname) {
    localStorage.setItem(TOKEN_KEY, token);
    if (nickname) {
        localStorage.setItem(NICKNAME_KEY, nickname);
    }
}

/** 取登录令牌，没有就返回空串 */
function getToken() {
    return localStorage.getItem(TOKEN_KEY) || '';
}

/** 取缓存的昵称（只为页面渲染快一点，真值以 /admin/api/me 为准） */
function getNickname() {
    return localStorage.getItem(NICKNAME_KEY) || '';
}

/** 清掉本地登录痕迹 */
function clearToken() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(NICKNAME_KEY);
}

/** 有没有登录过（只看本地有没有令牌，不代表它还没过期） */
function hasToken() {
    return getToken() !== '';
}

/* ============================================================
   2. api() —— 统一请求封装
   ------------------------------------------------------------
   用法：
     const page = await api('/client/api/articles?page=1');
     const id   = await api('/admin/api/articles', { method: 'POST', body: {...} });

   它替你做了四件事：
     · 自动加 Content-Type 和 JSON 序列化
     · 自动带 Authorization 头
     · 把 { code, message, data } 拆开，直接给你 data
     · code 是 401 时清掉本地令牌并跳登录页

   出错时抛 ApiError，用 try/catch 接住，e.message 就是给用户看的话。
   ============================================================ */

/** 请求失败的统一异常。带一个 code（业务码，网络错误时是 0） */
class ApiError extends Error {
    constructor(message, code) {
        super(message);
        this.name = 'ApiError';
        this.code = code;
    }
}

/**
 * 发一个请求。
 *
 * @param {string} url      请求地址
 * @param {object} [options] 可选：
 *        method            请求方法，默认 GET
 *        body              请求体，对象会自动转 JSON
 *        auth              是否带令牌，默认 true
 *        redirectOn401     401 时是否跳登录页，默认 true
 * @returns {Promise<any>} 服务端返回的 data 部分
 */
async function api(url, options = {}) {
    const opts = {
        method: options.method || 'GET',
        headers: Object.assign({}, options.headers)
    };

    // 请求体：对象自动序列化
    if (options.body !== undefined && options.body !== null) {
        opts.headers['Content-Type'] = 'application/json;charset=UTF-8';
        opts.body = (typeof options.body === 'string')
            ? options.body
            : JSON.stringify(options.body);
    }

    // 自动带令牌
    if (options.auth !== false) {
        const token = getToken();
        if (token) {
            opts.headers['Authorization'] = 'Bearer ' + token;
        }
    }

    let response;
    try {
        response = await fetch(url, opts);
    } catch (e) {
        // fetch 只在网络层失败时抛异常：断网、服务器没起来、
        // 跨域被拦、或者请求被浏览器扩展挡掉了。
        // 抛出来的是 TypeError，而且各浏览器的文案还不一样：
        //     Chrome   "Failed to fetch"
        //     Firefox  "NetworkError when attempting to fetch resource"
        //     Safari   "Load failed"
        //
        // 【为什么要把原始文案带出去】
        // 原来这里只写一句"网络请求失败，请检查连接"，
        // 结果页面上报错了却完全不知道是哪种情况 ——
        // 断网？服务没起？被拦？地址拼错？全长得一样。
        // 带上原文和请求地址之后，一眼就能分辨。
        const reason = (e && e.message) ? e.message : '浏览器没有说明原因';
        throw new ApiError('连不上服务器：' + reason + '（请求地址 ' + url + '）', 0);
    }

    if (!response.ok) {
        // 走到这里说明响应不是 2xx。正常情况下后端总是返回 HTTP 200
        // 加业务码，出现这个多半是代理、网关或者应用没起来
        throw new ApiError('服务器返回了 HTTP ' + response.status, response.status);
    }

    let body;
    try {
        body = await response.json();
    } catch (e) {
        // 比如返回了一个 HTML 错误页，JSON.parse 会失败
        throw new ApiError('服务器返回的内容不是 JSON（可能是被代理拦了）', 0);
    }

    if (body.code === 0) {
        return body.data;
    }

    // 401：登录失效。清掉本地令牌并跳登录页
    if (body.code === 401) {
        clearToken();

        // 【为什么要判断当前在哪】如果已经在登录页了还跳，
        // 会变成"登录失败 → 跳登录页 → 又请求 → 又失败"的循环，
        // 浏览器看起来像卡死
        if (options.redirectOn401 !== false && !isLoginPage()) {
            const back = encodeURIComponent(location.pathname + location.search);
            location.href = '/admin/login?redirect=' + back;
        }
        throw new ApiError(body.message || '登录已失效，请重新登录', 401);
    }

    throw new ApiError(body.message || '请求失败', body.code);
}

/** 当前是不是在登录页 */
function isLoginPage() {
    return location.pathname === '/admin/login'
        || location.pathname === '/admin/login.html';
}

/* ============================================================
   3. escapeHtml() —— 防 XSS
   ------------------------------------------------------------
   【什么时候必须用】
   凡是把"来自服务端的数据"拼进 innerHTML 的地方，都要过这个函数。
   比如文章标题里有 <script> 时，不转义就会真的执行。

   【什么时候不用】
   文章正文（contentHtml）——它本来就是渲染好的 HTML，用 innerHTML 插。
   但也只有它可以直接插，别的字段一律要转义。
   ============================================================ */
function escapeHtml(value) {
    if (value === null || value === undefined) {
        return '';
    }
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/* ============================================================
   4. 时间处理
   ------------------------------------------------------------
   后端返回的时间是 "2026-10-17 00:55:04" 这种格式（带空格）。
   【坑在这里】Safari 和部分旧浏览器不认这种格式，
   new Date("2026-10-17 00:55:04") 会得到 Invalid Date。
   标准格式是带 T 的 ISO 格式，所以下面统一把空格换成 T。
   ============================================================ */

/**
 * 把后端的时间字符串转成 Date 对象。
 * @returns {Date|null} 解析不了时返回 null
 */
function parseTime(value) {
    if (!value) {
        return null;
    }
    if (value instanceof Date) {
        return value;
    }
    const text = String(value).trim().replace(' ', 'T');
    const date = new Date(text);
    return isNaN(date.getTime()) ? null : date;
}

/** 格式化成 "2026-10-17 00:55" */
function formatTime(value) {
    const date = parseTime(value);
    if (!date) {
        return value ? String(value) : '';
    }
    const pad = n => String(n).padStart(2, '0');
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
        + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
}

/** 格式化成 "2026-10-17"，只要日期 */
function formatDate(value) {
    const date = parseTime(value);
    if (!date) {
        return value ? String(value) : '';
    }
    const pad = n => String(n).padStart(2, '0');
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate());
}

/**
 * 说人话的时间：刚刚 / 5 分钟前 / 3 小时前 / 2 天前，
 * 超过 30 天就直接给日期。
 */
function formatRelative(value) {
    const date = parseTime(value);
    if (!date) {
        return '';
    }
    const diffSeconds = (Date.now() - date.getTime()) / 1000;

    if (diffSeconds < 0) {
        // 服务器时间和本机时间有偏差时会出现"未来"的时间，别显示成负数
        return '刚刚';
    }
    if (diffSeconds < 60) {
        return '刚刚';
    }
    if (diffSeconds < 3600) {
        return Math.floor(diffSeconds / 60) + ' 分钟前';
    }
    if (diffSeconds < 86400) {
        return Math.floor(diffSeconds / 3600) + ' 小时前';
    }
    if (diffSeconds < 86400 * 30) {
        return Math.floor(diffSeconds / 86400) + ' 天前';
    }
    return formatDate(value);
}

/* ============================================================
   5. toast() —— 轻提示
   ------------------------------------------------------------
   用法：toast('保存成功') / toast('出错了', 'error')
   不引第三方库，样式在 common.css 的 .toast 里。
   ============================================================ */

/**
 * 弹一条提示，几秒后自己消失。
 *
 * @param {string} message 提示文字
 * @param {string} [type]  ok | error | info（默认 info）
 */
function toast(message, type = 'info') {
    let box = document.querySelector('.toast-box');
    if (!box) {
        box = document.createElement('div');
        box.className = 'toast-box';
        document.body.appendChild(box);
    }

    const item = document.createElement('div');
    item.className = 'toast toast-' + type;
    // ★ 用 textContent 而不是 innerHTML：提示语里可能带用户输入的内容
    item.textContent = message;
    box.appendChild(item);

    setTimeout(function () {
        item.classList.add('is-out');
        // 等淡出动画（0.24s）播完再移除节点
        setTimeout(() => item.remove(), 260);
    }, 2600);
}

/* ============================================================
   6. 几个顺手的小工具
   ============================================================ */

/** 取单个元素，$('#id') 这种写法 */
function $(selector, root = document) {
    return root.querySelector(selector);
}

/** 取一组元素，返回真数组（NodeList 没有 map/filter） */
function $$(selector, root = document) {
    return Array.from(root.querySelectorAll(selector));
}

/** 取地址栏上的查询参数，getQuery('page') */
function getQuery(name) {
    return new URLSearchParams(location.search).get(name);
}

/** 把所有查询参数取成一个对象 */
function getQueries() {
    const result = {};
    new URLSearchParams(location.search).forEach(function (value, key) {
        result[key] = value;
    });
    return result;
}

/**
 * 把一段文字里的换行和空格处理成能安全放进 HTML 的形式，
 * 并且把换行变成 <br>。用在"摘要、说明"这类多行纯文本上。
 */
function textToHtml(value) {
    return escapeHtml(value).replace(/\n/g, '<br>');
}

/**
 * 给元素设置加载中的内容。
 * @param {HTMLElement} el 目标元素
 * @param {string} [text] 提示文字
 */
function showLoading(el, text = '正在加载…') {
    if (el) {
        el.innerHTML = '<div class="loading"><span class="spinner"></span>'
            + escapeHtml(text) + '</div>';
    }
}

/** 给元素设置空状态 */
function showEmpty(el, text = '暂无数据') {
    if (el) {
        el.innerHTML = '<div class="empty">' + escapeHtml(text) + '</div>';
    }
}

/**
 * 页面顶部的错误提示。用在"整个页面加载失败"这种场景 ——
 * 比 toast 好，因为它不会自己消失，用户能一直看到原因。
 */
function showPageError(el, message, retryFn) {
    if (!el) {
        return;
    }
    el.innerHTML = '<div class="empty">'
        + escapeHtml(message)
        + (retryFn ? '<br><br><button class="btn btn-sm" id="retryBtn">重试</button>' : '')
        + '</div>';

    if (retryFn) {
        const btn = el.querySelector('#retryBtn');
        if (btn) {
            btn.addEventListener('click', retryFn);
        }
    }
}
