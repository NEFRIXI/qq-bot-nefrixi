/* ============================================================
   user.js —— 普通用户侧的逻辑
   ------------------------------------------------------------
   和 admin.js / main.js 并列，各管一摊：
     admin.js  后台（管理员）
     main.js   前台阅读（文章列表、详情、音乐栏）
     user.js   前台身份与互动（登录注册、评论、发帖）   ← 本文件

   靠 <body data-page="..."> 区分：
     auth     登录 / 注册页
     article  文章详情页（挂评论区）

   【为什么令牌的 key 和后台不一样】
     admin 用 mybotweb_admin_token，这里用 mybotweb_user_token。
     两套身份互不相干，共用 key 的话，
     在后台登录之后前台会莫名其妙地"已登录"，反过来也一样。
   ============================================================ */
(function () {
    'use strict';

    const PAGE = document.body.dataset.page;

    /** 用户令牌在 localStorage 里的键名（刻意和后台分开） */
    const TOKEN_KEY = 'mybotweb_user_token';

    /** 当前登录用户，未登录为 null */
    let currentUser = null;

    /* ========================================================
       令牌读写
       ======================================================== */
    function getToken() {
        try {
            return localStorage.getItem(TOKEN_KEY);
        } catch (e) {
            // 隐私模式下 localStorage 可能直接抛异常，别让页面整个崩掉
            return null;
        }
    }

    function saveToken(token) {
        try {
            localStorage.setItem(TOKEN_KEY, token);
        } catch (e) {
            console.warn('保存登录状态失败：' + e);
        }
    }

    function clearToken() {
        try {
            localStorage.removeItem(TOKEN_KEY);
        } catch (e) {
            /* 忽略 */
        }
    }

    /* ========================================================
       请求封装
       --------------------------------------------------------
       不能直接用 common.js 里的 api()：那个函数读的是后台令牌。
       这里自己写一份，行为保持一致（成功返回 data，失败抛带 code 的错误）。
       ======================================================== */
    async function userApi(url, options = {}) {
        const opts = {
            method: options.method || 'GET',
            headers: Object.assign({}, options.headers)
        };

        if (options.body !== undefined && options.body !== null) {
            opts.headers['Content-Type'] = 'application/json;charset=UTF-8';
            opts.body = (typeof options.body === 'string')
                ? options.body
                : JSON.stringify(options.body);
        }

        const token = getToken();
        if (token && options.auth !== false) {
            opts.headers['Authorization'] = 'Bearer ' + token;
        }

        let response;
        try {
            response = await fetch(url, opts);
        } catch (e) {
            throw new Error('连不上服务器（' + (e.message || '网络错误') + '）');
        }

        let body;
        try {
            body = await response.json();
        } catch (e) {
            throw new Error('服务器返回的内容不是 JSON（HTTP ' + response.status + '）');
        }

        if (body.code === 0) {
            return body.data;
        }

        // 401：令牌过期或被登出。清掉本地痕迹，让下次操作重新登录
        if (body.code === 401) {
            clearToken();
            currentUser = null;
        }

        const error = new Error(body.message || '请求失败');
        error.code = body.code;
        throw error;
    }

    /* ========================================================
       提示条
       ======================================================== */
    function toast(message, kind) {
        let box = document.querySelector('.toast-box');
        if (!box) {
            box = document.createElement('div');
            box.className = 'toast-box';
            document.body.appendChild(box);
        }
        const el = document.createElement('div');
        el.className = 'toast' + (kind === 'ok' ? ' toast-ok' : (kind === 'error' ? ' toast-error' : ''));
        el.textContent = message;
        box.appendChild(el);
        setTimeout(function () {
            el.classList.add('is-out');
            setTimeout(function () { el.remove(); }, 400);
        }, 2600);
    }

    /* ========================================================
       拉取当前用户
       --------------------------------------------------------
       有没有令牌都要问一次服务端：本地有字符串不代表它还有效
       （可能已经过期、被登出、被封禁踢下线）。
       ======================================================== */
    async function loadCurrentUser() {
        if (!getToken()) {
            currentUser = null;
            return null;
        }
        try {
            currentUser = await userApi('/client/api/user/me');
            return currentUser;
        } catch (e) {
            // 401 时 userApi 已经清了令牌；其它错误（比如断网）也当成未登录，
            // 但不能清令牌 —— 网络抖动不该把用户踢下线
            if (e.code === 401) {
                currentUser = null;
            }
            return null;
        }
    }

    /* ========================================================
       一、登录 / 注册页
       ======================================================== */
    function initAuthPage() {
        const loginForm = document.getElementById('loginForm');
        const registerForm = document.getElementById('registerForm');
        const title = document.getElementById('authTitle');
        const sub = document.getElementById('authSub');
        const tabs = document.querySelectorAll('.auth-tab');

        function switchTab(which) {
            const isLogin = (which === 'login');
            loginForm.hidden = !isLogin;
            registerForm.hidden = isLogin;
            title.textContent = isLogin ? '登录' : '注册';
            sub.textContent = isLogin ? '登录后可以发帖和评论' : '注册完直接进入登录状态';
            tabs.forEach(function (t) {
                t.classList.toggle('is-active', t.dataset.tab === which);
            });
        }

        tabs.forEach(function (t) {
            t.addEventListener('click', function () {
                switchTab(t.dataset.tab);
            });
        });

        // ---- 登录 ----
        loginForm.addEventListener('submit', async function (e) {
            e.preventDefault();
            const btn = document.getElementById('loginBtn');
            const username = document.getElementById('loginUsername').value.trim();
            const password = document.getElementById('loginPassword').value;

            if (!username || !password) {
                toast('用户名和密码都要填', 'error');
                return;
            }

            btn.disabled = true;
            try {
                const data = await userApi('/client/api/user/login', {
                    method: 'POST',
                    auth: false,
                    body: { username: username, password: password }
                });
                saveToken(data.token);
                toast('登录成功', 'ok');
                // 有 back 参数就回去，没有就回文章列表
                const back = new URLSearchParams(location.search).get('back');
                setTimeout(function () {
                    location.href = back || '/client/main';
                }, 400);
            } catch (err) {
                toast(err.message, 'error');
            } finally {
                btn.disabled = false;
            }
        });

        // ---- 注册 ----
        registerForm.addEventListener('submit', async function (e) {
            e.preventDefault();
            const btn = document.getElementById('registerBtn');
            const username = document.getElementById('regUsername').value.trim();
            const nickname = document.getElementById('regNickname').value.trim();
            const password = document.getElementById('regPassword').value;
            const password2 = document.getElementById('regPassword2').value;

            if (password !== password2) {
                toast('两次输入的密码不一样', 'error');
                return;
            }

            btn.disabled = true;
            try {
                const data = await userApi('/client/api/user/register', {
                    method: 'POST',
                    auth: false,
                    body: { username: username, password: password, nickname: nickname || null }
                });
                // 注册接口直接返回令牌，不用再登一次
                saveToken(data.token);
                toast('注册成功，已登录', 'ok');
                setTimeout(function () {
                    location.href = '/client/main';
                }, 400);
            } catch (err) {
                toast(err.message, 'error');
            } finally {
                btn.disabled = false;
            }
        });

        // 已登录的话直接走人，别让人重复登录
        loadCurrentUser().then(function (user) {
            if (user) {
                toast('你已经登录了：' + (user.nickname || user.username), 'ok');
            }
        });
    }

    /* ========================================================
       二、文章详情页的评论区
       ======================================================== */

    /** 当前文章 id，评论接口要用数字 id（详情页 URL 里是 slug） */
    let articleId = null;

    /**
     * 从地址栏拿 slug，再问一次接口换成 id。
     *
     * 为什么不复用 main.js 已经请求过的文章对象：
     * 两个文件各自独立，main.js 没有把这个 id 暴露出来。
     * 多一次请求换来"完全不改动现有代码"，这个取舍是值的。
     */
    async function resolveArticleId() {
        const parts = location.pathname.split('/').filter(Boolean);
        const slug = parts[parts.length - 1];
        if (!slug) {
            return null;
        }
        try {
            const article = await userApi('/client/api/articles/' + encodeURIComponent(slug), { auth: false });
            return article ? article.id : null;
        } catch (e) {
            console.warn('取文章 id 失败：' + e.message);
            return null;
        }
    }

    function escapeHtml(text) {
        return String(text == null ? '' : text)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function formatTime(value) {
        return value ? String(value).replace('T', ' ').slice(0, 16) : '';
    }

    /** 渲染一条评论（含它的楼中楼） */
    function renderComment(c) {
        const replies = (c.replies || []).map(function (r) {
            const replyTo = r.replyToName
                ? '<span class="cm-reply-to">回复 @' + escapeHtml(r.replyToName) + '</span> '
                : '';
            return ''
                + '<li class="cm-reply" data-id="' + r.id + '">'
                + '  <div class="cm-head">'
                + '    <b class="cm-author">' + escapeHtml(r.authorName) + '</b>'
                + '    <span class="cm-time">' + escapeHtml(formatTime(r.createdAt)) + '</span>'
                + '  </div>'
                + '  <p class="cm-text">' + replyTo + escapeHtml(r.content) + '</p>'
                + '  <button type="button" class="cm-act" data-reply="' + r.id + '"'
                + '          data-name="' + escapeHtml(r.authorName) + '">回复</button>'
                + '</li>';
        }).join('');

        return ''
            + '<li class="cm-item" data-id="' + c.id + '">'
            + '  <div class="cm-head">'
            + '    <b class="cm-author">' + escapeHtml(c.authorName) + '</b>'
            + '    <span class="cm-time">' + escapeHtml(formatTime(c.createdAt)) + '</span>'
            + '  </div>'
            + '  <p class="cm-text">' + escapeHtml(c.content) + '</p>'
            + '  <button type="button" class="cm-act" data-reply="' + c.id + '"'
            + '        data-name="' + escapeHtml(c.authorName) + '">回复</button>'
            + (replies ? '<ul class="cm-replies">' + replies + '</ul>' : '')
            + '</li>';
    }

    function renderComments(box, list) {
        if (!list || list.length === 0) {
            box.innerHTML = '<p class="empty">还没有评论，来说第一句吧</p>';
            return;
        }
        box.innerHTML = '<ul class="cm-list">' + list.map(renderComment).join('') + '</ul>';
    }

    async function initComments() {
        const box = document.getElementById('commentsBox');
        if (!box) {
            return;
        }

        articleId = await resolveArticleId();
        if (!articleId) {
            box.innerHTML = '<p class="empty">评论区暂时不可用</p>';
            return;
        }

        await loadCurrentUser();

        // ---- 发评论表单：登录了才显示 ----
        const formBox = document.getElementById('commentFormBox');
        if (formBox) {
            if (currentUser) {
                formBox.innerHTML = ''
                    + '<div class="cm-compose">'
                    + '  <textarea class="input cm-input" id="cmInput" rows="3"'
                    + '            maxlength="1000" placeholder="说点什么…"></textarea>'
                    + '  <div class="cm-compose-foot">'
                    + '    <span class="cm-replying" id="cmReplying" hidden></span>'
                    + '    <button class="btn btn-primary btn-sm" type="button" id="cmSubmit">发表</button>'
                    + '  </div>'
                    + '</div>';
            } else {
                formBox.innerHTML = '<p class="cm-login-tip">'
                    + '<a href="/client/auth?back=' + encodeURIComponent(location.pathname) + '">登录</a>'
                    + ' 后可以评论</p>';
            }
        }

        let replyToId = null;

        // ---- 回复按钮：事件委托，评论是动态渲染的 ----
        box.addEventListener('click', function (e) {
            const btn = e.target.closest('[data-reply]');
            if (!btn) {
                return;
            }
            if (!currentUser) {
                toast('登录后才能回复', 'error');
                return;
            }
            replyToId = Number(btn.dataset.reply);
            const name = btn.dataset.name || '';
            const tip = document.getElementById('cmReplying');
            const input = document.getElementById('cmInput');
            if (tip) {
                tip.hidden = false;
                tip.textContent = '正在回复 @' + name + '（点这里取消）';
                tip.onclick = function () {
                    replyToId = null;
                    tip.hidden = true;
                };
            }
            if (input) {
                input.focus();
            }
        });

        // ---- 发表 ----
        const submitBtn = document.getElementById('cmSubmit');
        if (submitBtn) {
            submitBtn.addEventListener('click', async function () {
                const input = document.getElementById('cmInput');
                const content = (input.value || '').trim();
                if (!content) {
                    toast('先写点什么', 'error');
                    return;
                }

                submitBtn.disabled = true;
                try {
                    await userApi('/client/api/comments', {
                        method: 'POST',
                        body: {
                            articleId: articleId,
                            content: content,
                            parentId: replyToId
                        }
                    });
                    input.value = '';
                    replyToId = null;
                    const tip = document.getElementById('cmReplying');
                    if (tip) {
                        tip.hidden = true;
                    }
                    toast('已发表', 'ok');
                    await refresh();
                } catch (err) {
                    toast(err.message, 'error');
                } finally {
                    submitBtn.disabled = false;
                }
            });
        }

        async function refresh() {
            try {
                const list = await userApi('/client/api/comments?articleId=' + articleId, { auth: false });
                renderComments(box, list);
            } catch (err) {
                box.innerHTML = '<p class="empty">评论加载失败：' + escapeHtml(err.message) + '</p>';
            }
        }

        await refresh();
    }

    /* ========================================================
       四、写文章页
       --------------------------------------------------------
       同一个页面做两件事：上面写新帖，下面列"我发过的"。
       没登录时两块都变成"去登录"的提示 —— 不让用户填完一屏
       才在提交时被拒绝。
       ======================================================== */
    async function initPostPage() {
        const formBox = document.getElementById('postFormBox');
        const myBox = document.getElementById('myPostsBox');
        const user = await loadCurrentUser();

        if (!user) {
            if (formBox) {
                formBox.innerHTML = ''
                    + '<div class="card post-card post-gate">'
                    + '  <p class="post-gate-text">发帖需要先登录</p>'
                    + '  <a class="btn btn-primary" href="/client/auth?back=/client/post">去登录</a>'
                    + '</div>';
            }
            if (myBox) {
                myBox.innerHTML = '<p class="empty">登录后可以看到自己发过的帖子</p>';
            }
            return;
        }

        // ---- 发布 ----
        const form = document.getElementById('postForm');
        if (form) {
            form.addEventListener('submit', async function (e) {
                e.preventDefault();
                const btn = document.getElementById('postSubmit');
                const hint = document.getElementById('postActionsHint');
                const title = document.getElementById('postTitle').value.trim();
                const content = document.getElementById('postContent').value.trim();

                if (!title || !content) {
                    toast('标题和正文都要填', 'error');
                    return;
                }

                btn.disabled = true;
                if (hint) {
                    hint.textContent = '发布中…';
                }

                try {
                    await userApi('/client/api/posts', {
                        method: 'POST',
                        body: { title: title, content: content }
                    });

                    // 清空表单 + 刷新列表，不跳转。
                    // 想立刻看效果就点列表里那一篇 —— 比自动跳走更可控
                    document.getElementById('postTitle').value = '';
                    document.getElementById('postContent').value = '';
                    toast('已发布', 'ok');
                    await loadMyPosts();
                } catch (err) {
                    toast(err.message, 'error');
                } finally {
                    btn.disabled = false;
                    if (hint) {
                        hint.textContent = '';
                    }
                }
            });
        }

        await loadMyPosts();
    }

    /** 拉取并渲染"我发过的" */
    async function loadMyPosts() {
        const box = document.getElementById('myPostsBox');
        if (!box) {
            return;
        }

        try {
            const list = await userApi('/client/api/posts/mine');
            if (!list || list.length === 0) {
                box.innerHTML = '<p class="empty">还没发过帖子</p>';
                return;
            }

            box.innerHTML = '<ul class="my-post-list">'
                + list.map(renderMyPost).join('')
                + '</ul>';

            // 删除按钮：事件委托，列表是动态重建的
            box.addEventListener('click', async function (e) {
                const btn = e.target.closest('[data-del]');
                if (!btn) {
                    return;
                }
                if (!confirm('确定删除这篇吗？\n\n删了就没了，不能恢复。')) {
                    return;
                }

                btn.disabled = true;
                try {
                    await userApi('/client/api/posts/' + btn.dataset.del, { method: 'DELETE' });
                    toast('已删除', 'ok');
                    await loadMyPosts();
                } catch (err) {
                    toast(err.message, 'error');
                    btn.disabled = false;
                }
            });

        } catch (err) {
            box.innerHTML = '<p class="empty">加载失败：' + escapeHtml(err.message) + '</p>';
        }
    }

    /** 我的帖子列表里的一行 */
    function renderMyPost(p) {
        return ''
            + '<li class="my-post">'
            + '  <a class="my-post-link" href="/client/main/' + encodeURIComponent(p.slug) + '">'
            +      escapeHtml(p.title) + '</a>'
            + '  <span class="my-post-time">'
            +      escapeHtml(formatTime(p.publishedAt || p.updatedAt)) + '</span>'
            + '  <button class="my-post-del" type="button" data-del="' + p.id + '">删除</button>'
            + '</li>';
    }

    /* ========================================================
       三、顶栏的登录状态
       --------------------------------------------------------
       所有前台页面都调。已登录显示"名字 + 退出"，未登录显示"登录"。
       用 innerHTML 一次性重建 —— 这块内容很少，
       比逐节点增删简单，也不会漏掉某个状态。
       ======================================================== */
    async function initTopbarUser() {
        const box = document.getElementById('userBox');
        if (!box) {
            return;
        }

        const user = await loadCurrentUser();

        if (user) {
            const name = escapeHtml(user.nickname || user.username);
            box.innerHTML = '<span class="topbar-user">' + name + '</span>'
                + '<button class="topbar-logout" type="button" id="logoutBtn">退出</button>';

            const btn = document.getElementById('logoutBtn');
            if (btn) {
                btn.addEventListener('click', async function () {
                    try {
                        await userApi('/client/api/user/logout', { method: 'POST' });
                    } catch (e) {
                        // 退出接口失败也要清本地令牌，
                        // 否则用户会卡在"点了退出没反应"
                    }
                    clearToken();
                    currentUser = null;
                    location.reload();
                });
            }
        } else {
            // back 参数让用户登录完能回到原来那一页
            box.innerHTML = '<a href="/client/auth?back='
                + encodeURIComponent(location.pathname) + '">登录</a>';
        }
    }

    /* ========================================================
       入口
       ======================================================== */
    document.addEventListener('DOMContentLoaded', function () {
        // 顶栏状态所有页面都要
        initTopbarUser();

        if (PAGE === 'auth') {
            initAuthPage();
        } else if (PAGE === 'article') {
            // 等 main.js 把文章画完再挂评论，避免和它抢 DOM
            initComments();
        } else if (PAGE === 'post') {
            initPostPage();
        }
    });
})();
