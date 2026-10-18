<#
.SYNOPSIS
    检查外挂音乐目录和歌单，能不能真的从服务器取到。

.DESCRIPTION
    rail-test.mjs 验的是"逻辑对不对"（点哪张卡放哪首、位移算得准不准）。
    但它跑在 node 里，用的是假浏览器，**根本不会去请求 mp3**。

    这个脚本补上那一半：真的发 HTTP 请求，确认
      1. 外挂目录真的挂上了（不是还在从 jar 里读）
      2. 中文文件名能被正确编码和解析（空格、×、Ⅳ、括号都是坑）
      3. Content-Type 是 audio/mpeg（不对的话浏览器可能不认）
      4. 支持 Range 请求（浏览器拖进度条靠的就是它）
      5. 不存在的文件老实返回 404，而不是被当成某个默认资源
      6. 歌单里写的文件名和磁盘上的文件一一对应

.PARAMETER BaseUrl
    目标地址。本地默认 8082；要验服务器就传 http://111.231.80.13:8080

.PARAMETER MusicList
    歌单文件路径（相对项目根目录）

.PARAMETER MusicDir
    外挂音乐目录（相对项目根目录）

.PARAMETER JarMusicDir
    jar 内那份 music 在构建产物里的位置。用来判断"外挂是不是真的生效了"

.EXAMPLE
    pwsh -File data-test/music-url-test.ps1
    pwsh -File data-test/music-url-test.ps1 -BaseUrl http://111.231.80.13:8080
#>
param(
    [string]$BaseUrl     = 'http://127.0.0.1:8082',
    [string]$MusicList   = 'music/list.json',
    [string]$MusicDir    = 'music',
    [string]$JarMusicDir = 'target/classes/static/music'
)

$ErrorActionPreference = 'Stop'
$pass = 0
$fail = 0
$details = @()

function Check([string]$desc, [bool]$ok, [string]$detail = '') {
    if ($ok) {
        $script:pass++
        Write-Host "   [OK]   $desc" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
        if ($detail) { Write-Host "          $detail" -ForegroundColor DarkYellow }
        $script:details += "$desc -- $detail"
    }
}

function Step([string]$name) {
    Write-Host ""
    Write-Host ("── {0} {1}" -f $name, ('─' * [Math]::Max(1, 56 - $name.Length))) -ForegroundColor Cyan
}

# 逐段编码：不能对整个 URL 用 EscapeDataString，那会把 / 也编掉
function Encode-Path([string]$path) {
    (($path -split '/') | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/'
}

function Curl-Get([string]$fullUrl, [string]$extraArgs) {
    $args = @('-s', '-o', 'NUL', '--max-time', '25', '-w', '%{http_code}|%{content_type}|%{size_download}')
    if ($extraArgs) { $args += $extraArgs }
    $args += $fullUrl
    return (& curl.exe @args 2>&1) -join ''
}

# ============================================================
# 0. 解析歌单
# ============================================================
Step '0. 解析歌单'

if (-not (Test-Path -LiteralPath $MusicList)) {
    Write-Host "   找不到歌单文件 $MusicList" -ForegroundColor Red
    Write-Host "   外挂目录建好了吗？里面应该有 list.json 和一堆 mp3。" -ForegroundColor DarkYellow
    exit 2
}

try {
    $raw  = Get-Content -LiteralPath $MusicList -Raw -Encoding UTF8
    $list = @($raw | ConvertFrom-Json)
} catch {
    Write-Host "   $MusicList 不是合法 JSON：$($_.Exception.Message)" -ForegroundColor Red
    exit 2
}

Write-Host ("   共 {0} 首曲目" -f $list.Count)
if ($list.Count -eq 0) {
    Write-Host "   歌单是空的，没什么可测的" -ForegroundColor Yellow
    exit 0
}

# 拼出每首的访问路径
$songs = @()
foreach ($item in $list) {
    $p = $item.url
    if (-not $p -and $item.file) { $p = '/music/' + $item.file }
    if (-not $p) { continue }
    $songs += [pscustomobject]@{
        Title = if ($item.title) { $item.title } else { '(无标题)' }
        Path  = $p
        File  = $item.file
    }
}

Write-Host ("   其中 {0} 首有可访问路径" -f $songs.Count)

# ============================================================
# 1. 外挂目录到底生效了没有
# ============================================================
Step '1. 外挂目录是否真的生效'

# list.json 只在 jar 外面，jar 里没有它。
# 所以能取到 = 外挂目录确实挂上了。
$probe = $BaseUrl + '/music/list.json'
$r = Curl-Get $probe ''
$code = ($r -split '\|')[0]
Check "GET /music/list.json 能取到（实际 $code）" ($code -eq '200') `
    "返回 $code。这个文件只在 jar 外面的 music/ 里，取不到说明外挂目录没挂上"

# 如果 jar 里也有同名 mp3，就可能掩盖"外挂失效" —— 那种情况下页面照样能放歌，
# 但放的是 jar 里那份旧文件，改了外挂目录却没反应，很难查。
$jarMp3 = @(Get-ChildItem -LiteralPath $JarMusicDir -Filter *.mp3 -File -ErrorAction SilentlyContinue)
Check "jar 内的 music 目录里没有 mp3（避免掩盖外挂失效）" ($jarMp3.Count -eq 0) `
    ("jar 里还有 " + $jarMp3.Count + " 个 mp3：" + (($jarMp3 | ForEach-Object { $_.Name }) -join '、') `
     + "。这些会顶在前面或兜在后面，让外挂改了看不出效果")

# ============================================================
# 2. 每首歌都要能取到
# ============================================================
Step '2. 逐个请求（真的发 HTTP）'

$fetched = @()
foreach ($s in $songs) {
    $full  = $BaseUrl + (Encode-Path $s.Path)
    $r     = Curl-Get $full ''
    $parts = $r -split '\|'
    $hc    = $parts[0]
    $ctype = if ($parts.Count -gt 1) { $parts[1] } else { '' }
    $size  = if ($parts.Count -gt 2) { [int]$parts[2] } else { 0 }

    $fetched += [pscustomobject]@{
        Title = $s.Title; Path = $s.Path; Code = $hc; Type = $ctype; Size = $size
    }

    $label = "{0}  ←  {1}" -f $s.Title, $s.Path
    if ($hc -ne '200') {
        Check $label $false "HTTP $hc（文件不在外挂目录里？还是名字对不上？）"
        continue
    }
    if ($ctype -notmatch 'audio|mpeg|octet-stream') {
        Check "$label  [Content-Type=$ctype]" $false "类型不对，浏览器可能不认"
        continue
    }
    if ($size -le 0) {
        Check "$label  [0 字节]" $false "拿到的是空文件"
        continue
    }
    Check ("{0}  [{1}, {2:N1} MB]" -f $label, $ctype, ($size / 1MB)) $true
}

# ============================================================
# 3. Range 请求 —— 浏览器拖进度条靠它
# ============================================================
Step '3. Range 请求（拖进度条 / 边下边播）'

$first = $fetched | Where-Object { $_.Code -eq '200' } | Select-Object -First 1
if ($first) {
    $full = $BaseUrl + (Encode-Path $first.Path)

    $r  = (& curl.exe -s -o NUL --max-time 25 -H 'Range: bytes=0-1023' `
          -w '%{http_code}|%{size_download}' $full 2>&1) -join ''
    $rp = $r -split '\|'
    $rc = $rp[0]
    $rs = if ($rp.Count -gt 1) { [int]$rp[1] } else { 0 }

    Check "Range: bytes=0-1023 返回 206（实际 $rc）" ($rc -eq '206') `
        "返回 $rc。不是 206 的话浏览器可能不能拖动进度条"
    Check "只回了 1024 字节（实际 $rs）" ($rs -eq 1024) "回了 $rs 字节，Range 没生效"

    $hdr = (& curl.exe -s -I --max-time 25 $full 2>&1) -join "`n"
    Check '响应头里有 Accept-Ranges: bytes' ($hdr -match 'Accept-Ranges:\s*bytes') `
        '没有这个头，部分浏览器会退化成一次性下载整个文件'
} else {
    Check '有可测的曲目来做 Range 检查' $false '没有一首取到 200，跳过'
}

# ============================================================
# 4. 不存在的文件必须 404
# ============================================================
Step '4. 不存在的文件要老实 404'

$ghost = $BaseUrl + '/music/' + [uri]::EscapeDataString('这首歌根本不存在.mp3')
$g = (& curl.exe -s -o NUL --max-time 25 -w '%{http_code}' $ghost 2>&1) -join ''
Check "不存在的文件返回 404（实际 $g）" ($g -eq '404') `
    "返回 $g —— 如果返回 200，说明有兜底逻辑在乱放行"

# ============================================================
# 5. 歌单和磁盘文件一一对应
# ============================================================
Step '5. 歌单和磁盘文件（$MusicDir/）是否对得上'

if (Test-Path -LiteralPath $MusicDir) {
    $onDisk = @(Get-ChildItem -LiteralPath $MusicDir -Filter *.mp3 -File |
                ForEach-Object { $_.Name } | Sort-Object)

    $inList = @($songs | Where-Object { $_.File } | ForEach-Object { $_.File } | Sort-Object)

    $missing = @($inList | Where-Object { $onDisk -notcontains $_ })
    $orphan  = @($onDisk | Where-Object { $inList -notcontains $_ })

    Check "歌单引用的每个文件都在磁盘上（共 $($onDisk.Count) 个 mp3）" ($missing.Count -eq 0) `
        ("缺：" + ($missing -join '、'))
    Check '磁盘上没有白放着的 mp3（放了却没写进歌单）' ($orphan.Count -eq 0) `
        ("多出：" + ($orphan -join '、'))

    # 带 url 的（外链）不该在磁盘上找
    $withUrl = @($songs | Where-Object { -not $_.File })
    if ($withUrl.Count -gt 0) {
        Write-Host ("   （有 {0} 首用的是 url 外链，不检查磁盘）" -f $withUrl.Count) -ForegroundColor DarkGray
    }
} else {
    Check "找到外挂音乐目录（$MusicDir）" $false '路径算错了？'
}

# ============================================================
Write-Host ""
Write-Host ('=' * 62)
if ($fail -eq 0) {
    Write-Host "全部通过：$pass 项" -ForegroundColor Green
} else {
    Write-Host "通过 $pass 项，失败 $fail 项" -ForegroundColor Red
    Write-Host ""
    Write-Host "失败清单：" -ForegroundColor Red
    $details | ForEach-Object { Write-Host "  · $_" -ForegroundColor DarkYellow }
}
Write-Host ('=' * 62)

exit $fail
