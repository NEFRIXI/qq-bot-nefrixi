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
        { href: '/admin/work/users', text: '用户管理' },
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
            case 'users':
                initUsersPage();
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

        // 事件用委托绑到容器上，而不是给每一行的按钮单独绑 ——
        // 列表是 innerHTML 重建的，逐个绑会随着重绘反复增加监听器。
        //
        // 但委托本身也只能绑一次：这个方法每次刷新都会跑，
        // 不判断的话，点一次按钮会触发 N 次请求（N = 这个页面刷新过几次），
        // 表现为"删一篇文章，提示了三次已删除"。
        if (!box.dataset.delegated) {
            box.addEventListener('click', onTableClick);
            box.dataset.delegated = '1';
        }
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
            // 发布 / 转草稿直接在列表里切，不用进编辑页走一圈。
            // 为了点一个"发布"先把整篇正文读出来再提交，容易手滑
            +      (isDraft
                    ? '<button class="btn btn-sm btn-primary" data-toggle="' + item.id
                      + '" data-status="1">发布</button>'
                    : '<button class="btn btn-sm" data-toggle="' + item.id
                      + '" data-status="0">转草稿</button>')
            + '    <a class="btn btn-sm" href="/admin/work/articles/' + item.id + '/edit">编辑</a>'
            + '    <button class="btn btn-sm btn-danger" data-delete="' + item.id + '"'
            + '            data-title="' + escapeHtml(item.title) + '">删除</button>'
            + '  </td>'
            + '</tr>';
    }

    /**
     * 列表页直接发布或转回草稿。
     *
     * 只提交状态，不带正文 —— 走的是那个"只改一个字段"的 PATCH 接口。
     */
    async function toggleArticleStatus(btn) {
        const id = btn.dataset.toggle;
        const status = Number(btn.dataset.status);
        const word = (status === 1) ? '发布' : '转回草稿';

        if (!confirm('确定' + word + '这篇吗？')) {
            return;
        }

        btn.disabled = true;
        try {
            await api('/admin/api/articles/' + id + '/status', {
                method: 'PATCH',
                body: { status: status }
            });
            toast('已' + word, 'ok');

            // 重新拉一次列表，并且带上当前筛选条件：
            // 刚发布的文章可能已经不属于当前筛选了（比如正在看"草稿"）
            loadAdminArticles($('#articleTableBox'), getQuery('status'));
        } catch (e) {
            toast(word + '失败：' + e.message, 'error');
            btn.disabled = false;
        }
    }

    async function onTableClick(event) {
        // ---- 发布 / 转草稿 ----
        const toggleBtn = event.target.closest('[data-toggle]');
        if (toggleBtn) {
            await toggleArticleStatus(toggleBtn);
            return;
        }

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

    /** 环形仪表的半径与周长。SVG 的 viewBox 是 100×100，圆心在 (50,50) */
    const GAUGE_RADIUS = 42;
    const GAUGE_CIRCUM = 2 * Math.PI * GAUGE_RADIUS;      // ≈ 263.89

    function renderSystemStatus(box, s) {
        box.innerHTML = ''

            // ---- 第一排：四个环形仪表（有"占比"的指标） ----
            + '<div class="gauge-grid">'
            +   gaugeCard('JVM 堆内存', s.jvmUsagePercent,
                    s.jvmUsedMb + ' / ' + s.jvmMaxMb + ' MB', '按堆上限')
            +   gaugeCard('系统内存', s.systemMemoryUsagePercent,
                    s.systemUsedMemoryMb + ' / ' + s.systemTotalMemoryMb + ' MB',
                    '可用 ' + s.systemFreeMemoryMb + ' MB')
            +   gaugeCard('CPU 负载', s.cpuLoadPercent,
                    s.cpuCores + ' 核', '整机占用')
            +   gaugeCard('磁盘', s.diskUsagePercent,
                    s.diskUsedGb + ' / ' + s.diskTotalGb + ' GB',
                    '剩余 ' + s.diskUsableGb + ' GB')
            + '</div>'

            // ---- 第二排：没有"占比"可画、只有绝对值的计数类指标 ----
            + '<div class="stat-grid">'
            +   valueCard('线程数', s.threadCount || '—',
                    '历史峰值 ' + (s.threadPeakCount || '—') + ' 个')
            +   valueCard('登录会话', s.activeSessions + ' 个', '来自 Redis')
            +   valueCard('已运行', s.uptimeText || '—', '自上次启动')
            + '</div>'

            // ---- 第三排：中间件连通性 ----
            + '<div class="card">'
            + '  <div class="card-title">服务连通性</div>'
            +    connItem('MySQL', s.mysqlOk, s.mysqlMessage)
            +    connItem('Redis', s.redisOk, s.redisMessage)
            + '</div>'

            // ---- 第四排：运行信息 ----
            // 只保留卡片里没有的项。已运行/CPU 核心/会话数上面已经出现过了，
            // 同一屏重复显示一遍只会让人怀疑哪个才是准的
            + '<div class="card">'
            + '  <div class="card-title">运行信息</div>'
            + '  <table class="table">'
            + '    <tbody>'
            +       kvRow('应用名', s.applicationName)
            +       kvRow('服务器时间', formatTime(s.serverTime))
            +       kvRow('Java 版本', s.javaVersion)
            +       kvRow('操作系统', s.osName)
            +       kvRow('磁盘路径', s.diskPath)
            +       kvRow('博客开关', s.blogEnabled ? '已开启' : '已关闭')
            + '    </tbody>'
            + '  </table>'
            + '</div>';
    }

    /**
     * 环形仪表卡片。
     *
     * <p>画法：一个 SVG 圆环，dasharray 设成整个周长，
     * dashoffset 设成「周长 × (1 - 占用率)」，露出来的那段弧就是占用比例。
     *
     * <p>CPU 在部分容器/虚拟机环境里拿不到，后端给 -1。
     * 这种情况显示 "--" 而不是 0% —— 0% 会让人误以为机器完全空闲。
     *
     * @param name     指标名，如「JVM 堆内存」
     * @param pctValue 占用率 0~100；小于 0 表示取不到
     * @param detail   圆环下方的明细，如「512 / 2048 MB」
     * @param note     圆环中间的小字，如「按堆上限」
     */
    function gaugeCard(name, pctValue, detail, note) {
        const unknown = (pctValue === null || pctValue === undefined || pctValue < 0);
        const pct = unknown ? 0 : Math.min(Math.max(pctValue, 0), 100);
        const level = pct >= 92 ? ' is-danger' : (pct >= 80 ? ' is-warn' : '');
        const offset = GAUGE_CIRCUM * (1 - pct / 100);
        const shown = unknown ? '--' : Math.round(pct);

        return ''
            + '<div class="gauge-card">'
            + '  <div class="gauge' + level + '">'
            + '    <svg viewBox="0 0 100 100" aria-hidden="true">'
            + '      <circle class="gauge-track" cx="50" cy="50" r="' + GAUGE_RADIUS + '"/>'
            + '      <circle class="gauge-fill" cx="50" cy="50" r="' + GAUGE_RADIUS + '"'
            + '              stroke-dasharray="' + GAUGE_CIRCUM.toFixed(2) + '"'
            + '              stroke-dashoffset="' + offset.toFixed(2) + '"/>'
            + '    </svg>'
            + '    <div class="gauge-center">'
            + '      <b>' + shown + (unknown ? '' : '<small>%</small>') + '</b>'
            + '      <span>' + escapeHtml(note) + '</span>'
            + '    </div>'
            + '  </div>'
            + '  <div class="gauge-name">' + escapeHtml(name) + '</div>'
            + '  <div class="gauge-detail">' + escapeHtml(detail) + '</div>'
            + '</div>';
    }

    /** 纯数值卡片：线程数、会话数、运行时长这类没有"占比"可画的指标 */
    function valueCard(label, value, sub) {
        return ''
            + '<div class="stat-card">'
            + '  <div class="label">' + escapeHtml(label) + '</div>'
            + '  <div class="value">' + escapeHtml(String(value)) + '</div>'
            + '  <div class="sub">' + escapeHtml(sub) + '</div>'
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
       用户管理
       --------------------------------------------------------
       只有两个动作：看列表、封禁/解封。

       【为什么不提供"删除用户"】
       删掉之后他发过的评论和帖子会变成"已注销用户"——数据还在但没法追溯。
       封禁是可逆的（停用，必要时恢复），历史内容完整保留。
       真要清理是另一个需要慎重设计的动作，不该顺手放在这张表里。
       ======================================================== */
    async function initUsersPage() {
        const box = $('#usersBox');
        showLoading(box, '正在读取用户…');

        const refreshBtn = $('#refreshBtn');
        if (refreshBtn) {
            refreshBtn.addEventListener('click', function () {
                loadUsers(box, 1);
            });
        }

        await loadUsers(box, 1);
    }

    /** 拉一页用户并渲染 */
    async function loadUsers(box, page) {
        try {
            const data = await api('/admin/api/users?page=' + page + '&size=20');
            renderUsers(box, data);
        } catch (e) {
            showPageError(box, '用户列表读取失败：' + e.message, function () {
                loadUsers(box, page);
            });
        }
    }

    /**
     * 三个状态的文案和确认提示。
     *
     * 集中放一处：原来这些字符串散在按钮渲染和确认框两处，
     * 加"禁言"这一档时就得两个地方对着改，很容易漏一边。
     */
    const STATUS_WORD = { 0: '恢复', 1: '封禁', 2: '禁言' };

    const STATUS_TIP = {
        0: '',
        1: '\n\n封禁后他会立刻掉线，并且在解封前无法再登录。',
        2: '\n\n禁言后他还能登录、能浏览，但发不了帖也评不了论。'
    };

    /** 状态徽章：0 正常 / 1 已封禁 / 2 已禁言 */
    function statusBadge(status) {
        if (status === 1) {
            return '<span class="badge badge-danger">已封禁</span>';
        }
        if (status === 2) {
            return '<span class="badge badge-warn">已禁言</span>';
        }
        return '<span class="badge badge-ok">正常</span>';
    }

    /**
     * 这一行该显示哪几个操作按钮。
     *
     * 只给"当前状态的反向操作"：如果一排四个按钮里有三个点了没反应，
     * 那不叫功能丰富，那叫让人猜。
     *
     * 删除永远在最后，而且永远是红色 —— 它是这一排里唯一一个
     * 不可逆的操作（虽然是软删除，但界面上用户不知道）。
     */
    function actionButtons(u, status) {
        const id = u.id;
        const html = [];

        // 恢复：封禁或禁言状态下才有
        if (status !== 0) {
            html.push('<button class="btn btn-sm" data-uid="' + id
                + '" data-status="0">恢复</button>');
        }

        // 禁言：正常和封禁状态下都可以设
        if (status !== 2) {
            html.push('<button class="btn btn-sm" data-uid="' + id + '" data-status="2"'
                + ' title="能登录、能浏览，但发不了帖也评不了论">禁言</button>');
        }

        // 封禁：正常和禁言状态下都可以设
        if (status !== 1) {
            html.push('<button class="btn btn-sm btn-danger" data-uid="' + id + '" data-status="1"'
                + ' title="立刻踢下线，且无法再登录">封禁</button>');
        }

        html.push('<button class="btn btn-sm btn-danger" data-del="' + id + '"'
            + ' data-name="' + escapeHtml(u.username) + '">删除</button>');

        return html.join('');
    }

    /** 渲染表格 + 绑定操作 + 绑定翻页 */
    function renderUsers(box, data) {
        const records = (data && data.records) || [];
        if (records.length === 0) {
            box.innerHTML = '<div class="card"><p class="empty">还没有用户注册</p></div>';
            return;
        }

        const rows = records.map(function (u) {
            const status = Number(u.status) || 0;

            return ''
                + '<tr>'
                + '  <td>' + escapeHtml(u.username) + '</td>'
                + '  <td>' + escapeHtml(u.nickname || '—') + '</td>'
                + '  <td>' + statusBadge(status) + '</td>'
                + '  <td>' + escapeHtml(formatTime(u.createdAt)) + '</td>'
                + '  <td class="col-actions">' + actionButtons(u, status) + '</td>'
                + '</tr>';
        }).join('');

        box.innerHTML = ''
            + '<div class="table-card">'
            + '  <table class="table">'
            + '    <thead>'
            + '      <tr>'
            + '        <th>用户名</th><th>昵称</th><th>状态</th><th>注册时间</th>'
            + '        <th class="col-actions">操作</th>'
            + '      </tr>'
            + '    </thead>'
            + '    <tbody>' + rows + '</tbody>'
            + '  </table>'
            + '</div>'
            + pagerHtml(data);

        // ---- 改状态：封禁 / 禁言 / 恢复 ----
        box.querySelectorAll('[data-uid]').forEach(function (btn) {
            btn.addEventListener('click', async function () {
                const status = Number(btn.dataset.status);
                const uid = btn.dataset.uid;
                const word = STATUS_WORD[status] || '修改';

                if (!confirm('确定把这个人设为「' + word + '」吗？'
                    + (STATUS_TIP[status] || ''))) {
                    return;
                }

                btn.disabled = true;
                try {
                    await api('/admin/api/users/' + uid + '/status', {
                        method: 'PATCH',
                        body: { status: status }
                    });
                    toast('已' + word, 'ok');
                    await loadUsers(box, (data && data.current) || 1);
                } catch (e) {
                    toast(word + '失败：' + e.message, 'error');
                    btn.disabled = false;
                }
            });
        });

        // ---- 删除（软删除）----
        box.querySelectorAll('[data-del]').forEach(function (btn) {
            btn.addEventListener('click', async function () {
                const uid = btn.dataset.del;
                const name = btn.dataset.name || '这个用户';

                // 把"删了会发生什么"直接写在确认框里，而不是只问一句"确定吗"。
                // 用户最容易怕的就是"我的文章会不会一起没了"，
                // 这个疑虑要在点下去之前回答掉
                if (!confirm('确定删除「' + name + '」吗？\n\n'
                    + '· 他发过的文章和评论都会保留，不会跟着消失\n'
                    + '· 数据库里只是打了个删除标记，需要时可以恢复\n'
                    + '· 这个用户名之后不能再被注册')) {
                    return;
                }

                btn.disabled = true;
                try {
                    await api('/admin/api/users/' + uid, { method: 'DELETE' });
                    toast('已删除', 'ok');
                    await loadUsers(box, (data && data.current) || 1);
                } catch (e) {
                    toast('删除失败：' + e.message, 'error');
                    btn.disabled = false;
                }
            });
        });

        // ---- 翻页 ----
        box.querySelectorAll('[data-page]').forEach(function (a) {
            a.addEventListener('click', function (e) {
                e.preventDefault();
                loadUsers(box, Number(a.dataset.page));
            });
        });
    }

    /** 简单的上一页/下一页。用户量不大，不做页码条 */
    function pagerHtml(data) {
        const pages = (data && data.pages) || 1;
        const current = (data && data.current) || 1;
        if (pages <= 1) {
            return '';
        }

        let html = '<div class="pager">';
        if (current > 1) {
            html += '<a href="#" data-page="' + (current - 1) + '">上一页</a>';
        }
        html += '<span class="pager-info">' + current + ' / ' + pages + '</span>';
        if (current < pages) {
            html += '<a href="#" data-page="' + (current + 1) + '">下一页</a>';
        }
        html += '</div>';
        return html;
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
        const published = Number(stats.publishedArticles) || 0;
        const draft = Number(stats.draftArticles) || 0;

        box.innerHTML = ''
            // ---- 第一排：四个总量 ----
            + '<div class="stat-grid">'
            +   statCard('已发布', published, '篇')
            +   statCard('草稿', draft, '篇')
            +   statCard('总浏览量', stats.totalViews, '次')
            +   statCard('总点赞数', stats.totalLikes, '个')
            + '</div>'

            // ---- 第二排：环形图 + 两个条形排行 ----
            + '<div class="stat-row">'
            + '  <div class="card">'
            + '    <div class="card-title">发布状态</div>'
            +      donut(published, draft)
            + '  </div>'
            + '  <div class="card">'
            + '    <div class="card-title">浏览量排行</div>'
            +      barRank(stats.topViewed, 'viewCount', '次')
            + '  </div>'
            + '  <div class="card">'
            + '    <div class="card-title">点赞排行</div>'
            +      barRank(stats.topLiked, 'likeCount', '个')
            + '  </div>'
            + '</div>'

            + '<p class="text-faint text-sm mt-2">'
            + '这些数字来自 Redis 的计数器，点一次加一次，没有做任何去重。'
            + '</p>';
    }

    /**
     * 环形图：已发布和草稿的比例。
     *
     * 用 SVG 的 stroke-dasharray 画，不引图表库 ——
     * 后台就这一张饼图，为它拉一个几十 KB 的依赖不值。
     *
     * 原理：圆周长 2πr，把整段虚线设成"实线长 + 空白长"，
     * 实线占多少，圆环就画多少。
     */
    function donut(published, draft) {
        const total = published + draft;
        if (total === 0) {
            return '<div class="empty">还没有文章</div>';
        }

        const R = 52;
        const C = 2 * Math.PI * R;              // 周长 ≈ 326.7
        const ratio = published / total;
        const dash = (ratio * C).toFixed(1);

        return ''
            + '<div class="donut-wrap">'
            + '  <svg class="donut" viewBox="0 0 120 120" role="img"'
            +       ' aria-label="已发布 ' + published + ' 篇，草稿 ' + draft + ' 篇">'
            + '    <circle class="donut-bg" cx="60" cy="60" r="' + R + '"/>'
            + '    <circle class="donut-fg" cx="60" cy="60" r="' + R + '"'
            +           ' stroke-dasharray="' + dash + ' ' + C.toFixed(1) + '"/>'
            + '  </svg>'
            + '  <div class="donut-center">'
            + '    <b>' + Math.round(ratio * 100) + '%</b>'
            + '    <span>已发布</span>'
            + '  </div>'
            + '</div>'
            + '<ul class="legend">'
            + '  <li><i class="legend-dot legend-on"></i>已发布 ' + published + ' 篇</li>'
            + '  <li><i class="legend-dot legend-off"></i>草稿 ' + draft + ' 篇</li>'
            + '</ul>';
    }

    /**
     * 条形排行。
     *
     * 条长以榜首为 100% 基准，不用绝对值 ——
     * 一篇 1000 次浏览的和一篇 5 次的并排，后者会细成一条看不见的线。
     * 最低给 2%，保证"有数据"这件事本身看得出来。
     */
    function barRank(items, valueField, unit) {
        if (!items || items.length === 0) {
            return '<div class="empty">还没有数据</div>';
        }

        const max = items.reduce(function (m, item) {
            return Math.max(m, Number(item[valueField]) || 0);
        }, 0) || 1;

        return '<ul class="bar-rank">'
            + items.map(function (item, index) {
                const v = Number(item[valueField]) || 0;
                const pct = Math.max((v / max) * 100, 2);

                return ''
                    + '<li>'
                    + '  <div class="bar-head">'
                    + '    <span class="bar-no' + (index < 3 ? ' top' : '') + '">'
                    +      (index + 1) + '</span>'
                    + '    <a class="bar-title" href="/client/main/'
                    +      encodeURIComponent(item.slug)
                    +      '" target="_blank" rel="noopener">'
                    +      escapeHtml(item.title) + '</a>'
                    + '    <span class="bar-value">' + v + ' ' + unit + '</span>'
                    + '  </div>'
                    + '  <div class="bar-track">'
                    + '    <i class="bar-fill" style="width:' + pct.toFixed(1) + '%"></i>'
                    + '  </div>'
                    + '</li>';
            }).join('')
            + '</ul>';
    }
})();
