# ============================================================
# 端到端测试 —— 真的起一个应用，把接口都跑一遍
# ------------------------------------------------------------
# 用法：应用启动好之后执行本脚本
#   pwsh -File data-test/e2e-test.ps1
#
# 【为什么不把密码写在脚本里】
# 管理员密码从 application-local.yml 里现场读出来，
# 脚本本身和命令行里都不出现明文 —— 这份文件是要留在仓库里的，
# 密码写进去就等于提交了密码。
# ============================================================

$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8082'

$script:pass = 0
$script:fail = 0

function Step($name) {
    Write-Host ""
    Write-Host "── $name " -ForegroundColor Cyan -NoNewline
    Write-Host ("─" * [Math]::Max(1, 60 - $name.Length)) -ForegroundColor DarkGray
}

function Check($desc, $ok, $detail) {
    if ($ok) {
        $script:pass++
        Write-Host "   [OK]   $desc" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
        if ($detail) { Write-Host "          $detail" -ForegroundColor DarkYellow }
    }
}

# ============================================================
# 0. 读取管理员账号（从配置里读，不硬编码）
# ============================================================
$yamlPath = Join-Path $PSScriptRoot '..\src\main\resources\application-local.yml'
$lines = Get-Content $yamlPath

$adminUser = $null
$adminPass = $null
$inBlog = $false
$inAdmin = $false

foreach ($line in $lines) {
    if ($line -match '^\s{2}blog:\s*$') { $inBlog = $true; continue }
    if ($inBlog -and $line -match '^\s{4}admin:\s*$') { $inAdmin = $true; continue }
    if ($inAdmin) {
        # 去掉 yaml 里可能存在的引号：password: "xxx" 和 password: xxx 都要能读
        if ($line -match '^\s+username:\s*(\S+)') {
            $adminUser = $Matches[1] -replace '^["'']|["'']$', ''
        }
        if ($line -match '^\s+password:\s*(\S+)') {
            $adminPass = $Matches[1] -replace '^["'']|["'']$', ''
            break
        }
    }
}

if (-not $adminUser -or -not $adminPass) {
    Write-Host "读不到管理员账号密码，检查 application-local.yml 里的 app.blog.admin 段" -ForegroundColor Red
    exit 1
}

Write-Host "目标：$base" -ForegroundColor White
Write-Host "管理员账号已从配置读取：$adminUser（密码不回显）" -ForegroundColor DarkGray

# ============================================================
# 1. 服务是否活着
# ============================================================
Step '1. 服务可达性'
try {
    $welcome = Invoke-WebRequest "$base/client" -UseBasicParsing -TimeoutSec 10
    Check 'GET /client 欢迎页返回 200' ($welcome.StatusCode -eq 200) "实际 $($welcome.StatusCode)"
} catch {
    Write-Host "   服务没起来，后面的测试没法做：$_" -ForegroundColor Red
    exit 1
}

# ============================================================
# 2. 未登录访问受保护接口 —— 必须被拦住
# ============================================================
Step '2. 未登录访问 /admin/api/me（期望业务码 401）'
$r = Invoke-RestMethod "$base/admin/api/me" -Method GET
Check '业务码是 401' ($r.code -eq 401) "实际 code=$($r.code) message=$($r.message)"

Step '3. 用错误密码登录（期望失败，且不透露是用户名还是密码错）'
$bad = Invoke-RestMethod "$base/admin/api/login" -Method POST `
    -ContentType 'application/json; charset=utf-8' `
    -Body (@{ username = $adminUser; password = 'definitely-wrong-password' } | ConvertTo-Json)
Check '登录被拒绝' ($bad.code -ne 0) "实际 code=$($bad.code)"
Check '没有返回令牌' (-not $bad.data.token) '竟然返回了 token'
Write-Host "   提示语：$($bad.message)" -ForegroundColor DarkGray

# ============================================================
# 4. 正常登录
# ============================================================
Step '4. 正确密码登录'
$login = Invoke-RestMethod "$base/admin/api/login" -Method POST `
    -ContentType 'application/json; charset=utf-8' `
    -Body (@{ username = $adminUser; password = $adminPass } | ConvertTo-Json)

Check '登录成功' ($login.code -eq 0) "code=$($login.code) message=$($login.message)"
Check '拿到了令牌' ([bool]$login.data.token) '没有 token'

$token = $login.data.token
$auth = @{ Authorization = "Bearer $token" }
Write-Host "   令牌长度 $($token.Length)，有效期 $($login.data.expireSeconds) 秒，昵称 $($login.data.nickname)" -ForegroundColor DarkGray

Step '5. 带上令牌访问 /admin/api/me'
$me = Invoke-RestMethod "$base/admin/api/me" -Headers $auth
Check '能取到当前身份' ($me.code -eq 0 -and $me.data.username -eq $adminUser) "code=$($me.code)"
Check '没有把密码哈希一起返回' ($null -eq $me.data.passwordHash) '响应里出现了 passwordHash'

# ============================================================
# 6. 清理上一次测试留下的文章
# ============================================================
Step '6. 清理旧的测试文章'
# 这个脚本会重复跑，上一轮留下的文章必须先删掉，
# 否则同名的 slug 会撞车（slug 是唯一的），后面全是连锁失败
$testSlugs = @('hello-world', 'draft-should-hide')
$existing = Invoke-RestMethod "$base/admin/api/articles?size=50" -Headers $auth
foreach ($item in $existing.data.records) {
    if ($testSlugs -contains $item.slug) {
        Invoke-RestMethod "$base/admin/api/articles/$($item.id)" -Method DELETE -Headers $auth | Out-Null
        Write-Host "   删掉了上一轮留下的 $($item.slug) (id=$($item.id))" -ForegroundColor DarkGray
    }
}

# ============================================================
# 7. 新建文章（发布）
# ============================================================
Step '7. 新建一篇已发布的文章'

# 用数组 join 拼 Markdown，避免 PowerShell 把反引号当转义符
$content = @(
    '# 第一篇',
    '',
    '这是**加粗**和 `行内代码`。',
    '',
    '- 列表第一项',
    '- 列表第二项',
    '',
    '> 一行引用',
    '',
    '```java',
    'System.out.println("hello");',
    '```',
    '',
    '<script>alert("xss")</script>'
) -join "`n"

$createBody = @{
    title   = '端到端测试文章'
    slug    = 'hello-world'
    summary = ''
    content = $content
    status  = 1
} | ConvertTo-Json

$created = Invoke-RestMethod "$base/admin/api/articles" -Method POST `
    -Headers $auth -ContentType 'application/json; charset=utf-8' -Body $createBody

Check '创建成功' ($created.code -eq 0) "code=$($created.code) message=$($created.message)"
$articleId = $created.data
Write-Host "   新文章 id = $articleId" -ForegroundColor DarkGray

Step '8. 校验必填项（空标题应当被拒绝）'
$badBody = @{ title = ''; slug = ''; summary = ''; content = 'x'; status = 1 } | ConvertTo-Json
$badCreate = Invoke-RestMethod "$base/admin/api/articles" -Method POST `
    -Headers $auth -ContentType 'application/json; charset=utf-8' -Body $badBody
Check '空标题被拒绝' ($badCreate.code -ne 0) "竟然创建成功了"
Write-Host "   提示语：$($badCreate.message)" -ForegroundColor DarkGray

Step '9. 校验 slug 格式（大写和空格应当被拒绝）'
$badSlugBody = @{ title = 'x'; slug = 'Bad Slug'; summary = ''; content = 'x'; status = 0 } | ConvertTo-Json
$badSlug = Invoke-RestMethod "$base/admin/api/articles" -Method POST `
    -Headers $auth -ContentType 'application/json; charset=utf-8' -Body $badSlugBody
Check '非法 slug 被拒绝' ($badSlug.code -ne 0) "竟然通过了"

# ============================================================
# 10. 前台列表
# ============================================================
Step '10. 前台文章列表（不带令牌）'
$list = Invoke-RestMethod "$base/client/api/articles"
Check '能匿名读到列表' ($list.code -eq 0) "code=$($list.code)"
$found = $list.data.records | Where-Object { $_.slug -eq 'hello-world' }
Check '列表里有刚发的文章' ([bool]$found) "没找到 hello-world"
Check '列表项不含正文（省流量）' ($null -eq $found.content) '竟然带了 content 字段'

# ============================================================
# 11. 详情 + 浏览量累加
# ============================================================
Step '11. 文章详情，以及浏览量是不是每次都 +1'
$d1 = Invoke-RestMethod "$base/client/api/articles/hello-world"
Check '详情能读到' ($d1.code -eq 0) "code=$($d1.code) message=$($d1.message)"

$v1 = $d1.data.viewCount
$d2 = Invoke-RestMethod "$base/client/api/articles/hello-world"
$v2 = $d2.data.viewCount
$d3 = Invoke-RestMethod "$base/client/api/articles/hello-world"
$v3 = $d3.data.viewCount

Write-Host "   连续读三次，浏览量：$v1 → $v2 → $v3" -ForegroundColor DarkGray
Check '每次访问都 +1（不去重）' (($v2 - $v1) -eq 1 -and ($v3 - $v2) -eq 1) `
    "不是每次+1：$v1 / $v2 / $v3"

Check '返回了渲染好的 HTML' ([bool]$d3.data.contentHtml) 'contentHtml 是空的'
Check '前台详情【不】返回 Markdown 原文（省流量）' (-not $d3.data.contentMarkdown) `
    '前台详情不该带 contentMarkdown，那是编辑时才需要的'

Step '12. XSS 防护：正文里的 <script> 应该是转义过的'
Check 'script 标签被转义了' `
    ($d3.data.contentHtml -notmatch '<script>alert') `
    '正文里出现了未转义的 <script>！'

Step '13. Markdown 真的被渲染了'
Check '生成了 <h1>' ($d3.data.contentHtml -match '<h1') '没找到 h1'
Check '生成了 <ul>/<li>' ($d3.data.contentHtml -match '<li>') '没找到 li'
Check '生成了 <pre><code>' ($d3.data.contentHtml -match '<pre>') '没找到 pre'
Check '生成了 <blockquote>' ($d3.data.contentHtml -match '<blockquote>') '没找到 blockquote'

Step '14. 草稿不该被前台看到'
$draftBody = @{
    title = '这是一篇草稿'; slug = 'draft-should-hide'; summary = ''
    content = '草稿内容'; status = 0
} | ConvertTo-Json
$draft = Invoke-RestMethod "$base/admin/api/articles" -Method POST `
    -Headers $auth -ContentType 'application/json; charset=utf-8' -Body $draftBody
Check '草稿创建成功' ($draft.code -eq 0) "code=$($draft.code) message=$($draft.message)"
$draftId = $draft.data

$draftPublic = Invoke-RestMethod "$base/client/api/articles/draft-should-hide"
Check '匿名读草稿返回 404' ($draftPublic.code -eq 404) "实际 code=$($draftPublic.code)"

$draftAdmin = Invoke-RestMethod "$base/admin/api/articles/$draftId" -Headers $auth
Check '登录后能读到草稿' ($draftAdmin.code -eq 0) "code=$($draftAdmin.code)"

# ============================================================
# 15. 点赞 —— 点一次加一次，不去重
# ============================================================
Step '15. 点赞（用户要求：点一下就 +1，不做任何识别）'
$like1 = Invoke-RestMethod "$base/client/api/articles/$articleId/like" -Method POST
$like2 = Invoke-RestMethod "$base/client/api/articles/$articleId/like" -Method POST
$like3 = Invoke-RestMethod "$base/client/api/articles/$articleId/like" -Method POST

Write-Host "   连点三次，返回的点赞数：$($like1.data) → $($like2.data) → $($like3.data)" -ForegroundColor DarkGray
Check '每次调用都 +1' (($like2.data - $like1.data) -eq 1 -and ($like3.data - $like2.data) -eq 1) `
    "不是每次+1"

Step '16. 给不存在的文章点赞应当被拒绝'
$ghost = Invoke-RestMethod "$base/client/api/articles/99999999/like" -Method POST
Check '不存在的文章返回 404' ($ghost.code -eq 404) "实际 code=$($ghost.code)"

# ============================================================
# 17. 后台列表能看到草稿
# ============================================================
Step '17. 后台列表'
$adminList = Invoke-RestMethod "$base/admin/api/articles?size=50" -Headers $auth
Check '能读到后台列表' ($adminList.code -eq 0) "code=$($adminList.code)"
$hasDraft = $adminList.data.records | Where-Object { $_.slug -eq 'draft-should-hide' }
Check '后台列表里有草稿' ([bool]$hasDraft) '草稿没出现在后台列表里'

$adminDraftOnly = Invoke-RestMethod "$base/admin/api/articles?status=0&size=50" -Headers $auth
Check '按草稿筛选有效' `
    (($adminDraftOnly.data.records | Where-Object { $_.status -ne 0 }).Count -eq 0) `
    '筛选结果里混进了非草稿'

$adminPubOnly = Invoke-RestMethod "$base/admin/api/articles?status=1&size=50" -Headers $auth
Check '按已发布筛选有效' `
    (($adminPubOnly.data.records | Where-Object { $_.status -ne 1 }).Count -eq 0) `
    '筛选结果里混进了草稿'

# ============================================================
# 18. 编辑文章
# ============================================================
Step '18. 修改文章标题'
$updateBody = @{
    title   = '端到端测试文章（已改名）'
    slug    = 'hello-world'
    summary = '这是手动写的摘要'
    content = $content
    status  = 1
} | ConvertTo-Json

$updated = Invoke-RestMethod "$base/admin/api/articles/$articleId" -Method PUT `
    -Headers $auth -ContentType 'application/json; charset=utf-8' -Body $updateBody
Check '更新成功' ($updated.code -eq 0) "code=$($updated.code) message=$($updated.message)"

$afterUpdate = Invoke-RestMethod "$base/admin/api/articles/$articleId" -Headers $auth
Check '标题变了' ($afterUpdate.data.title -eq '端到端测试文章（已改名）') "实际：$($afterUpdate.data.title)"
Check '摘要变了' ($afterUpdate.data.summary -eq '这是手动写的摘要') "实际：$($afterUpdate.data.summary)"
Check '改完之后浏览量和点赞没丢' ($afterUpdate.data.viewCount -ge 3 -and $afterUpdate.data.likeCount -ge 3) `
    "浏览=$($afterUpdate.data.viewCount) 赞=$($afterUpdate.data.likeCount)"
Check '后台接口能读到 Markdown 原文' ([bool]$afterUpdate.data.contentMarkdown) `
    'contentMarkdown 是空的 —— 编辑页面就打不开了'

Step '19. slug 冲突应当被拒绝'
$conflictBody = @{
    title = '另一篇'; slug = 'hello-world'; summary = ''
    content = 'x'; status = 0
} | ConvertTo-Json
$conflict = Invoke-RestMethod "$base/admin/api/articles" -Method POST `
    -Headers $auth -ContentType 'application/json; charset=utf-8' -Body $conflictBody
Check '重复 slug 被拒绝' ($conflict.code -ne 0) "竟然允许了两个同名 slug"
Write-Host "   提示语：$($conflict.message)" -ForegroundColor DarkGray

# ============================================================
# 20. 服务器状态
# ============================================================
Step '20. 服务器状态接口'
$status = Invoke-RestMethod "$base/admin/api/system/status" -Headers $auth
Check '能读到状态' ($status.code -eq 0) "code=$($status.code) message=$($status.message)"
Check 'MySQL 显示连通' ($status.data.mysqlOk -eq $true) "mysqlOk=$($status.data.mysqlOk) msg=$($status.data.mysqlMessage)"
Check 'Redis 显示连通' ($status.data.redisOk -eq $true) "redisOk=$($status.data.redisOk) msg=$($status.data.redisMessage)"
Check 'JVM 内存有值' ($status.data.jvmMaxMb -gt 0) "jvmMaxMb=$($status.data.jvmMaxMb)"
Check '磁盘有值' ($status.data.diskTotalGb -gt 0) "diskTotalGb=$($status.data.diskTotalGb)"
Check '运行时长有值' ([bool]$status.data.uptimeText) 'uptimeText 空的'

Write-Host "   MySQL : $($status.data.mysqlMessage)" -ForegroundColor DarkGray
Write-Host "   Redis : $($status.data.redisMessage)" -ForegroundColor DarkGray
Write-Host "   JVM   : $($status.data.jvmUsedMb)/$($status.data.jvmMaxMb) MB，CPU $($status.data.cpuLoadPercent)%，已运行 $($status.data.uptimeText)" -ForegroundColor DarkGray

# ============================================================
# 21. 访问统计
# ============================================================
Step '21. 访问统计接口'
$stats = Invoke-RestMethod "$base/admin/api/system/stats" -Headers $auth
Check '能读到统计' ($stats.code -eq 0) "code=$($stats.code)"
Check '总浏览量大于 0' ($stats.data.totalViews -gt 0) "totalViews=$($stats.data.totalViews)"
Check '总点赞数大于 0' ($stats.data.totalLikes -gt 0) "totalLikes=$($stats.data.totalLikes)"
Check '排行榜有内容' ($stats.data.topViewed.Count -gt 0) '排行榜是空的'

Write-Host "   已发布 $($stats.data.publishedArticles) 篇 / 草稿 $($stats.data.draftArticles) 篇" -ForegroundColor DarkGray
Write-Host "   总浏览 $($stats.data.totalViews) / 总赞 $($stats.data.totalLikes)" -ForegroundColor DarkGray
if ($stats.data.topViewed.Count -gt 0) {
    Write-Host "   浏览第一：$($stats.data.topViewed[0].title)（$($stats.data.topViewed[0].viewCount) 次）" -ForegroundColor DarkGray
}

# ============================================================
# 22. 前台页面能打开
# ============================================================
Step '22. 前台页面路由'
foreach ($path in @('/client', '/client/main', '/client/main/hello-world')) {
    try {
        $resp = Invoke-WebRequest "$base$path" -UseBasicParsing -TimeoutSec 10
        Check "GET $path 返回 200" ($resp.StatusCode -eq 200) "实际 $($resp.StatusCode)"
    } catch {
        Check "GET $path 返回 200" $false "$_"
    }
}

Step '23. 静态资源能取到'
foreach ($path in @('/css/common.css', '/css/main.css', '/css/admin.css',
                    '/js/common.js', '/js/main.js', '/js/admin.js')) {
    try {
        $resp = Invoke-WebRequest "$base$path" -UseBasicParsing -TimeoutSec 10
        Check "GET $path" ($resp.StatusCode -eq 200) "实际 $($resp.StatusCode)"
    } catch {
        Check "GET $path" $false "$_"
    }
}

Step '24. 后台页面路由'
foreach ($path in @('/admin/login', '/admin', '/admin/work/articles',
                    '/admin/work/articles/new', "/admin/work/articles/$articleId/edit",
                    '/admin/work/system', '/admin/work/stats')) {
    try {
        $resp = Invoke-WebRequest "$base$path" -UseBasicParsing -TimeoutSec 10
        Check "GET $path 返回 200" ($resp.StatusCode -eq 200) "实际 $($resp.StatusCode)"
    } catch {
        Check "GET $path 返回 200" $false "$_"
    }
}

# ============================================================
# 25. 登出后旧令牌必须失效
# ============================================================
Step '25. 登出，然后拿旧令牌再试一次'
$logout = Invoke-RestMethod "$base/admin/api/logout" -Method POST -Headers $auth
Check '登出成功' ($logout.code -eq 0) "code=$($logout.code)"

$afterLogout = Invoke-RestMethod "$base/admin/api/me" -Headers $auth
Check '旧令牌已失效（401）' ($afterLogout.code -eq 401) "实际 code=$($afterLogout.code) —— 令牌没被吊销！"

Step '26. 前台接口不受令牌失效影响'
$stillPublic = Invoke-RestMethod "$base/client/api/articles"
Check '匿名读列表仍然正常' ($stillPublic.code -eq 0) "code=$($stillPublic.code)"

# ============================================================
# 收尾
# ============================================================
Write-Host ""
Write-Host ("=" * 62) -ForegroundColor DarkGray
if ($script:fail -eq 0) {
    Write-Host "全部通过：$($script:pass) 项" -ForegroundColor Green
} else {
    Write-Host "通过 $($script:pass) 项，失败 $($script:fail) 项" -ForegroundColor Red
}
Write-Host ("=" * 62) -ForegroundColor DarkGray

exit $script:fail
