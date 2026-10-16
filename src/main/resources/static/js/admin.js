/* ============================================================
   admin.js —— 后台所有页面
   ------------------------------------------------------------
   六个页面共用一个文件，靠 <body data-page="..."> 区分：
     login         登录页
     home          概览
     articles      文章管理（列表、删除）
     article-edit  新建 / 编辑文章
     system        服务器状态
     stats         访问统计

   共用的东西（顶部栏、登录校验、登出）写一次，
   各页面的逻辑在下面 switch 里各占一段。
   ============================================================ */
(function () {
    'use strict';

    const PAGE = document.body.dataset.page;

    /** 顶部导航的各项。加新功能只改这一处 */
    const NAV_LINKS = [
        { href: '/admin', text: '概览' },
        { href: '/admin/work/articles', text: '文章管理' },
        { href: '/admin/work/stats', text: '访问统计' },
        { href: '/admin/work/system', text: '服务器状态' }
    ];

    document.addEventListener('DOMContentLoaded', async function () {
        // 登录页不需要顶部栏，也不需要先校验登录
        if (PAGE !== 'login') {
            renderAdminBar();

            const me = await requireLogin();
            if (!me) {
                // 没登录（或令牌失效）。api() 已经负责跳转到登录页了，
                // 这里直接停手，别再往下跑业务逻辑
                return;
            }
        }

        switch (PAGE) {
            case 'login':
                initLoginPage();
                break;
            case 'home':
                initHomePage();
                break;
            case 'articles':
                initArticlesPage();
                break;
            case 'article-edit':
                initEditPage();
                break;
            case 'system':
                initSystemPage();
                break;
            case 'stats':
                initStatsPage();
                break;
            default:
                // 页面忘了写 data-page。不报错，但也不做任何事 ——
                // 这种情况下页面上会是一片空白，比抛异常更好排查
                console.warn('未知的 data-page：' + PAGE);
        }
    });

    /* ========================================================
       公用：顶部栏
       ======================================================== */
    function renderAdminBar() {
        const bar = $('#adminBar');
        if (!bar) {
            return;
        }

        const current = location.pathname;

        bar.innerHTML = ''
            + '<div class="container-wide admin-bar-inner">'
            + '  <a class="admin-brand" href="/admin">'
            + '    <span class="brand-dot"></span>WN 后台'
            + '  </a>'
            + '  <nav class="admin-nav">'
            +      NAV_LINKS.map(function (link) {
                       return '<a href="' + link.href + '"'
                           + (isNavActive(current, link.href) ? ' class="is-active"' : '')
                           + '>' + escapeHtml(link.text) + '</a>';
                   }).join('')
            + '  </nav>'
            + '  <div class="admin-user">'
            + '    <span id="adminNickname">…</span>'
            + '    <a href="/client" target="_blank" rel="noopener">看站点</a>'
            + '    <button class="btn btn-sm" id="logoutBtn">登出</button>'
            + '  </div>'
            + '</div>';

        $('#logoutBtn').addEventListener('click', doLogout);
    }

    /**
     * 判断导航项是不是当前页。
     *
     * 用前缀匹配而不是全等：/admin/work/articles/new 也应该
     * 让"文章管理"高亮，否则进了新建页导航就没高亮了。
     */
    function isNavActive(current, href) {
        if (href === '/admin') {
            return current === '/admin' || current === '/admin/';
        }
        return current === href || current.indexOf(href + '/') === 0;
    }

    /**
     * 校验登录，顺便把昵称填到右上角。
     *
     * 每个后台页面进来都调一次 /admin/api/me：
     * 令牌可能已经过期、或者你在别处登出过，
     * 光看本地有没有令牌是判断不出来的。
     *
     * @returns {Promise<object|null>} 登录信息，没登录时返回 null
     */
    async function requireLogin() {
        try {
            const me = await api('/admin/api/me');
            const el = $('#adminNickname');
            if (el) {
                el.textContent = me.nickname || me.username;
            }
            return me;
        } catch (e) {
            // api() 内部已经处理了 401（清令牌 + 跳登录页），
            // 这里只需要安静地停下来
            return null;
        }
    }

    async function doLogout() {
        try {
            // redirectOn401 设为 false：都主动登出了，
            // 就算令牌已失效也没必要再触发一次"跳登录页"
            await api('/admin/api/logout', { method: 'POST', redirectOn401: false });
        } catch (e) {
            // 【登出接口失败也必须清本地令牌】否则用户会卡在
            // "点了登出没反应"，还以为自己已经退出去了
            console.warn('登出接口调用失败：' + e.message);
        }
        clearToken();
        location.href = '/admin/login';
    }

    /* ========================================================
       登录页
       ======================================================== */
    function initLoginPage() {
        const form = $('#loginForm');
        if (!form) {
            return;
        }

        form.addEventListener('submit', async function (event) {
            // 阻止表单默认的"刷新页面"提交方式，改由 JS 发请求
            event.preventDefault();

            const username = $('#username').value.trim();
            const password = $('#password').value;
            const btn = $('#loginBtn');

            if (!username || !password) {
                toast('请填写用户名和密码', 'error');
                return;
            }

            btn.disabled = true;
            btn.textContent = '登录中…';

            try {
                const data = await api('/admin/api/login', {
                    method: 'POST',
                    body: { username: username, password: password },
                    auth: false,
                    // ★ 这个必须设成 false。登录失败返回的是 401，
                    //   如果让 api() 按 401 处理就会跳登录页 ——
                    //   而我们本来就在登录页，会变成刷新页面、
                    //   连"用户名或密码不对"这句提示都看不到
                    redirectOn401: false
                });

                saveToken(data.token, data.nickname);
                toast('登录成功', 'ok');

                // 登录前想去的页面（被踢到登录页时会带上 redirect 参数）
                const back = getQuery('redirect');
                // 【必须校验这个跳转目标】它是从地址栏读的，
                // 不检查的话，别人发给你一个
                // /admin/login?redirect=https://坏站点 的链接，
                // 你登录后就被带去那个站点了。
                // 只允许站内路径（以单个 / 开头，不是 // 开头）
                const safeBack = (back && back.charAt(0) === '/' && back.charAt(1) !== '/')
                    ? back : '/admin';

                location.href = safeBack;

            } catch (e) {
                toast(e.message, 'error');
                btn.disabled = false;
                btn.textContent = '登录';
                $('#password').value = '';
                $('#password').focus();
            }
        });
    }

    /* ========================================================
       概览
       ======================================================== */
    async function initHomePage() {
        const box = $('#homeBox');
        showLoading(box);

        try {
            const stats = await api('/admin/api/system/stats');
            renderHome(box, stats);
        } catch (e) {
            showPageError(box, '概览加载失败：' + e.message, initHomePage);
        }
    }

    function renderHome(box, stats) {
        box.innerHTML = ''
            + '<div class="stat-grid">'
            +   statCard('已发布', stats.publishedArticles, '篇')
            +   statCard('草稿', stats.draftArticles, '篇')
            +   statCard('总浏览量', stats.totalViews, '次')
            +   statCard('总点赞数', stats.totalLikes, '个')
            + '</div>'

            + '<div class="card">'
            + '  <div class="card-title">快捷操作</div>'
            + '  <div class="row-wrap">'
            + '    <a class="btn btn-primary" href="/admin/work/articles/new">写新文章</a>'
            + '    <a class="btn" href="/admin/work/articles">管理文章</a>'
            + '    <a class="btn" href="/admin/work/stats">看统计</a>'
            + '    <a class="btn" href="/admin/work/system">看服务器状态</a>'
            + '  </div>'
            + '</div>'

            + (stats.mostViewedTitle
                ? '<div class="card">'
                  + '  <div class="card-title">被看得最多的一篇</div>'
                  + '  <p>' + escapeHtml(stats.mostViewedTitle) + '</p>'
                  + '</div>'
                : '');
    }

    /** 状态卡片。数字和单位分开写，单位用小字，视觉上更清楚 */
    function statCard(label, value, unit) {
        return ''
            + '<div class="stat-card">'
            + '  <div class="label">' + escapeHtml(label) + '</div>'
            + '  <div class="value">' + value
            +      (unit ? ' <small>' + escapeHtml(unit) + '</small>' : '')
            + '  </div>'
            + '</div>';
    }

    /* ========================================================
       文章管理
       ======================================================== */
    function initArticlesPage() {
        // 状态筛选从地址栏读，这样刷新页面筛选条件不丢。
        // 空字符串表示"全部"
        const status = getQuery('status');
        const box = $('#articleTableBox');

        // 把当前筛选项标出来
        $$('.filter-group a').forEach(function (link) {
            const linkStatus = new URL(link.href, location.origin).searchParams.get('status');
            const isActive = (linkStatus || '') === (status || '');
            if (isActive) {
                link.classList.add('is-active');
            }
        });

        loadAdminArticles(box, status);
    }

    async function loadAdminArticles(box, status) {
        showLoading(box);

        let url = '/admin/api/articles?size=50';
        if (status === '0' || status === '1') {
            url += '&status=' + status;
        }

        let data;
        try {
            data = await api(url);
        } catch (e) {
            showPageError(box, '文章列表加载失败：' + e.message, function () {
                loadAdminArticles(box, status);
            });
            return;
        }

        if (!data.records || data.records.length === 0) {
            showEmpty(box, status === '0' ? '还没有草稿'
                : status === '1' ? '还没有发布过文章' : '还没有文章，点右上角写一篇');
            return;
        }

        box.innerHTML = ''
            + '<div class="table-card">'
            + '<table class="table">'
            + '<thead><tr>'
            + '  <th class="col-title">标题</th>'
            + '  <th>状态</th>'
            + '  <th class="col-num">浏览</th>'
            + '  <th class="col-num">赞</th>'
            + '  <th>发布时间</th>'
            + '  <th>最后修改</th>'
            + '  <th class="col-actions">操作</th>'
            + '</tr></thead>'
            + '<tbody>'
            + data.records.map(articleRow).join('')
            + '</tbody>'
            + '</table>'
            + '</div>'
            + '<p class="text-faint text-sm mt-2">共 ' + data.total + ' 篇'
            + (data.total > data.records.length
                ? '（当前显示前 ' + data.records.length + ' 篇）' : '')
            + '</p>';

        // 事件用委托绑到容器上，而不是给每一行的按钮单独绑。
        // 理由：列表是 innerHTML 重建的，逐个绑会随着重绘反复增加监听器
        box.addEventListener('click', onTableClick);
    }

    function articleRow(item) {
        const isDraft = item.status === 0;

        return ''
            + '<tr>'
            + '  <td class="col-title">'
            + '    <a class="title-link" href="/admin/work/articles/' + item.id + '/edit">'
            +        escapeHtml(item.title)
            + '    </a>'
            + '    <span class="slug">' + escapeHtml(item.slug) + '</span>'
            + '  </td>'
            + '  <td>'
            +      (isDraft
                    ? '<span class="badge badge-draft">草稿</span>'
                    : '<span class="badge badge-published">已发布</span>')
            + '  </td>'
            + '  <td class="col-num">' + item.viewCount + '</td>'
            + '  <td class="col-num">' + item.likeCount + '</td>'
            + '  <td class="text-sm text-dim">'
            +      (item.publishedAt ? formatDate(item.publishedAt) : '—')
            + '  </td>'
            + '  <td class="text-sm text-dim">' + formatDate(item.updatedAt) + '</td>'
            + '  <td class="col-actions">'
            +      (isDraft ? '' :
                    '<a class="btn btn-sm" href="/client/main/'
                    + encodeURIComponent(item.slug)
                    + '" target="_blank" rel="noopener">查看</a>')
            + '    <a class="btn btn-sm" href="/admin/work/articles/' + item.id + '/edit">编辑</a>'
            + '    <button class="btn btn-sm btn-danger" data-delete="' + item.id + '"'
            + '            data-title="' + escapeHtml(item.title) + '">删除</button>'
            + '  </td>'
            + '</tr>';
    }

    async function onTableClick(event) {
        const btn = event.target.closest('[data-delete]');
        if (!btn) {
            return;
        }

        const id = btn.dataset.delete;
        const title = btn.dataset.title;

        // 【必须二次确认】后端是真删，没有回收站、没有撤销
        if (!confirm('确定删除《' + title + '》吗？\n\n删了就没了，不能恢复。')) {
            return;
        }

        btn.disabled = true;
        btn.textContent = '删除中…';

        try {
            await api('/admin/api/articles/' + id, { method: 'DELETE' });
            toast('已删除', 'ok');

            // 删完重新拉一次列表，而不是把这行从 DOM 里移掉 ——
            // 重拉能顺带更新"共 N 篇"的数字，也不会出现分页错位
            const status = getQuery('status');
            const box = $('#articleTableBox');
            box.removeEventListener('click', onTableClick);
            loadAdminArticles(box, status);
        } catch (e) {
            toast('删除失败：' + e.message, 'error');
            btn.disabled = false;
            btn.textContent = '删除';
        }
    }

    /* ========================================================
       新建 / 编辑文章
       ======================================================== */
    function initEditPage() {
        // 同一个页面承担两件事，靠地址栏里有没有 id 区分：
        //   /admin/work/articles/new      → 新建
        //   /admin/work/articles/12/edit  → 编辑 12 号
        const id = getEditId();

        const titleInput = $('#titleInput');
        const form = $('#articleForm');

        if (id === null) {
            $('#pageTitle').textContent = '写新文章';
            $('#deleteBtn').classList.add('hidden');
            titleInput.focus();
        } else {
            $('#pageTitle').textContent = '编辑文章';
            $('#editId').textContent = id;
            loadArticleForEdit(id);
        }

        form.addEventListener('submit', function (event) {
            event.preventDefault();
            saveArticle(id);
        });

        if (id !== null) {
            $('#deleteBtn').addEventListener('click', function () {
                deleteFromEditPage(id);
            });
        }
    }

    /**
     * 从地址栏取出正在编辑的文章 id。
     * @returns {number|null} 新建时返回 null
     */
    function getEditId() {
        const parts = location.pathname.split('/').filter(Boolean);
        // /admin/work/articles/12/edit → ['admin','work','articles','12','edit']
        if (parts.length >= 5 && parts[4] === 'edit') {
            const id = parseInt(parts[3], 10);
            return isNaN(id) ? null : id;
        }
        return null;
    }

    async function loadArticleForEdit(id) {
        try {
            const data = await api('/admin/api/articles/' + id);

            $('#titleInput').value = data.title || '';
            $('#slugInput').value = data.slug || '';
            $('#summaryInput').value = data.summary || '';
            // contentMarkdown 只有后台接口才返回 —— 前台详情不给原文
            $('#contentInput').value = data.contentMarkdown || '';
            $('#statusInput').value = String(data.status);

            $('#metaBox').innerHTML = ''
                + '<div class="field">'
                + '  <label>创建于</label>'
                + '  <div class="text-dim text-sm">' + formatTime(data.createdAt) + '</div>'
                + '</div>'
                + '<div class="field">'
                + '  <label>最后修改</label>'
                + '  <div class="text-dim text-sm">' + formatTime(data.updatedAt) + '</div>'
                + '</div>'
                + '<div class="field">'
                + '  <label>数据</label>'
                + '  <div class="text-dim text-sm">'
                +      data.viewCount + ' 次浏览 · ' + data.likeCount + ' 个赞'
                + '  </div>'
                + '</div>';

        } catch (e) {
            showPageError($('#formBox'), '文章加载失败：' + e.message);
        }
    }

    async function saveArticle(id) {
        const btn = $('#saveBtn');

        const payload = {
            title: $('#titleInput').value.trim(),
            slug: $('#slugInput').value.trim(),
            summary: $('#summaryInput').value.trim(),
            content: $('#contentInput').value,
            status: parseInt($('#statusInput').value, 10)
        };

        // 先在本地拦一道，省一次必然失败的请求。
        // 服务端也会校验（@Valid），这里只是让提示更快出现
        if (!payload.title) {
            toast('标题不能为空', 'error');
            $('#titleInput').focus();
            return;
        }
        if (!payload.content.trim()) {
            toast('正文不能为空', 'error');
            $('#contentInput').focus();
            return;
        }

        btn.disabled = true;
        btn.textContent = '保存中…';

        try {
            if (id === null) {
                const newId = await api('/admin/api/articles', { method: 'POST', body: payload });
                toast('已创建', 'ok');
                // 建完跳到编辑页。用 replace 而不是 href，
                // 这样浏览器后退不会回到"新建"表单（那时它已经没意义了）
                location.replace('/admin/work/articles/' + newId + '/edit');
            } else {
                await api('/admin/api/articles/' + id, { method: 'PUT', body: payload });
                toast('已保存', 'ok');
                btn.disabled = false;
                btn.textContent = '保存';
            }
        } catch (e) {
            toast(e.message, 'error');
            btn.disabled = false;
            btn.textContent = '保存';
        }
    }

    async function deleteFromEditPage(id) {
        if (!confirm('确定删除这篇文章吗？\n\n删了就没了，不能恢复。')) {
            return;
        }
        try {
            await api('/admin/api/articles/' + id, { method: 'DELETE' });
            toast('已删除', 'ok');
            location.href = '/admin/work/articles';
        } catch (e) {
            toast('删除失败：' + e.message, 'error');
        }
    }

    /* ========================================================
       服务器状态
       ======================================================== */
    async function initSystemPage() {
        const box = $('#statusBox');
        showLoading(box, '正在检测（要连一次数据库和 Redis，稍等）…');

        try {
            const status = await api('/admin/api/system/status');
            renderSystemStatus(box, status);
        } catch (e) {
            showPageError(box, '状态读取失败：' + e.message, initSystemPage);
        }
    }

    function renderSystemStatus(box, s) {
        box.innerHTML = ''
            // ---- 第一排：内存和磁盘 ----
            + '<div class="stat-grid">'
            +   meterCard('JVM 堆内存',
                    s.jvmUsedMb + ' / ' + s.jvmMaxMb, 'MB', s.jvmUsagePercent)
            +   meterCard('系统内存',
                    s.systemUsedMemoryMb + ' / ' + s.systemTotalMemoryMb, 'MB',
                    s.systemMemoryUsagePercent)
            +   meterCard('磁盘',
                    s.diskUsedGb + ' / ' + s.diskTotalGb, 'GB', s.diskUsagePercent)
            +   cpuCard(s)
            + '</div>'

            // ---- 第二排：中间件连通性 ----
            + '<div class="card">'
            + '  <div class="card-title">服务连通性</div>'
            +    connItem('MySQL', s.mysqlOk, s.mysqlMessage)
            +    connItem('Redis', s.redisOk, s.redisMessage)
            + '</div>'

            // ---- 第三排：运行信息 ----
            + '<div class="card">'
            + '  <div class="card-title">运行信息</div>'
            + '  <table class="table">'
            + '    <tbody>'
            +       kvRow('应用名', s.applicationName)
            +       kvRow('已运行', s.uptimeText)
            +       kvRow('服务器时间', formatTime(s.serverTime))
            +       kvRow('Java 版本', s.javaVersion)
            +       kvRow('操作系统', s.osName)
            +       kvRow('CPU 核心', s.cpuCores + ' 核')
            +       kvRow('磁盘路径', s.diskPath)
            +       kvRow('博客开关', s.blogEnabled ? '已开启' : '已关闭')
            +       kvRow('当前登录会话', s.activeSessions + ' 个')
            + '    </tbody>'
            + '  </table>'
            + '</div>';
    }

    /**
     * 带进度条的数字卡片。
     *
     * 进度条的颜色按用量分档：超过 80% 变黄、超过 92% 变红 ——
     * 光看数字"3.1 / 3.6 GB"要反应一下，颜色不用。
     */
    function meterCard(label, value, unit, percentValue) {
        const pct = (percentValue === null || percentValue === undefined) ? 0 : percentValue;
        const level = pct >= 92 ? ' is-danger' : (pct >= 80 ? ' is-warn' : '');

        return ''
            + '<div class="stat-card">'
            + '  <div class="label">' + escapeHtml(label) + '</div>'
            + '  <div class="value">' + escapeHtml(value)
            +      ' <small>' + escapeHtml(unit) + '</small></div>'
            + '  <div class="sub">占用 ' + pct + '%</div>'
            + '  <div class="meter' + level + '"><i style="width:' + Math.min(pct, 100) + '%"></i></div>'
            + '</div>';
    }

    /** CPU 卡片单独写：它取不到值时要显示"--"而不是 0% */
    function cpuCard(s) {
        const unknown = (s.cpuLoadPercent === null || s.cpuLoadPercent < 0);
        return ''
            + '<div class="stat-card">'
            + '  <div class="label">CPU 使用率</div>'
            + '  <div class="value">' + (unknown ? '--' : s.cpuLoadPercent + ' <small>%</small>') + '</div>'
            + '  <div class="sub">' + s.cpuCores + ' 核</div>'
            + (unknown ? '' : '<div class="meter"><i style="width:'
                + Math.min(s.cpuLoadPercent, 100) + '%"></i></div>')
            + '</div>';
    }

    function connItem(name, ok, message) {
        return ''
            + '<div class="conn-item">'
            + '  <span class="conn-dot ' + (ok ? 'ok' : 'bad') + '"></span>'
            + '  <div>'
            + '    <div class="conn-name">' + escapeHtml(name) + '</div>'
            + '    <div class="conn-msg">' + escapeHtml(message || '') + '</div>'
            + '  </div>'
            + '</div>';
    }

    function kvRow(key, value) {
        return '<tr><th style="width:150px">' + escapeHtml(key) + '</th>'
            + '<td>' + escapeHtml(value === null || value === undefined ? '—' : String(value))
            + '</td></tr>';
    }

    /* ========================================================
       访问统计
       ======================================================== */
    async function initStatsPage() {
        const box = $('#statsBox');
        showLoading(box);

        try {
            const stats = await api('/admin/api/system/stats');
            renderStats(box, stats);
        } catch (e) {
            showPageError(box, '统计加载失败：' + e.message, initStatsPage);
        }
    }

    function renderStats(box, stats) {
        box.innerHTML = ''
            + '<div class="stat-grid">'
            +   statCard('已发布', stats.publishedArticles, '篇')
            +   statCard('草稿', stats.draftArticles, '篇')
            +   statCard('总浏览量', stats.totalViews, '次')
            +   statCard('总点赞数', stats.totalLikes, '个')
            + '</div>'

            + '<div class="card">'
            + '  <div class="card-title">浏览量最高的文章</div>'
            +    rankList(stats.topViewed, 'viewCount', '次浏览')
            + '</div>'

            + '<div class="card">'
            + '  <div class="card-title">点赞最多的文章</div>'
            +    rankList(stats.topLiked, 'likeCount', '个赞')
            + '</div>'

            + '<p class="text-faint text-sm mt-2">'
            + '这些数字来自 Redis 的计数器，点一次加一次，没有做任何去重。'
            + '</p>';
    }

    function rankList(items, valueField, unit) {
        if (!items || items.length === 0) {
            return '<div class="empty">还没有数据</div>';
        }

        // 只有一篇还排什么榜，直接把列表显示出来就好
        return '<ul class="rank-list">'
            + items.map(function (item, index) {
                return ''
                    + '<li>'
                    + '  <span class="rank-no' + (index < 3 ? ' top' : '') + '">'
                    +      (index + 1) + '</span>'
                    + '  <a class="rank-title" href="/client/main/'
                    +      encodeURIComponent(item.slug)
                    +      '" target="_blank" rel="noopener">'
                    +      escapeHtml(item.title) + '</a>'
                    + '  <span class="rank-value">' + item[valueField] + ' ' + unit + '</span>'
                    + '</li>';
            }).join('')
            + '</ul>';
    }
})();
