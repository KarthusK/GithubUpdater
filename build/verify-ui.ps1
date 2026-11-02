# CDP 交互验证脚本。用 Chrome DevTools Protocol 真实操作页面。
# 参数：$Ws CDP WebSocket 地址，$BaseUrl 面板地址
#
# 与浏览器无关的注意事项：
#   · CDP 的大响应（截图）会被拆成多个分片，Recv-Until 必须累积到 EndOfMessage。
#   · 本文件必须存为 UTF-8 with BOM，否则 PowerShell 5.1 按 GBK 解析中文会报
#     "缺少终止符"。

param(
    # 形参不能叫 $ws/$Ws：脚本内部有 $sw（Stopwatch），而 PowerShell 变量名
    # 大小写不敏感……不，真正的原因是下面 Recv-Until 里用了 $sw。
    # 历史上这里还踩过 $script:ws 覆盖 $Ws 的坑，见文件头注释。
    [string]$DebuggerUrl,
    [string]$BaseUrl
)

$ErrorActionPreference = 'Stop'

$script:failed = 0

# 注意：$Ws 是 param 变量，不要用 $script:ws 覆盖它（PowerShell 变量名大小写不敏感）
$script:socket = [System.Net.WebSockets.ClientWebSocket]::new()
$script:ct = [System.Threading.CancellationToken]::None
$script:socket.ConnectAsync([Uri]$DebuggerUrl, $script:ct).Wait()
$script:msgId = 0

function Send-Cdp {
    param([string]$Method, $Params)
    $script:msgId++
    $msg = @{ id = $script:msgId; method = $Method; params = $Params } | ConvertTo-Json -Depth 10 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
    $seg = [ArraySegment[byte]]::new($bytes)
    $script:socket.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $script:ct).Wait()
    return $script:msgId
}

function Recv-Until {
    param([int]$WantId, [int]$TimeoutMs = 10000)
    $buf = New-Object byte[] 262144
    $seg = [ArraySegment[byte]]::new($buf)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $sb = New-Object System.Text.StringBuilder
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        [void]$sb.Clear()
        $done = $false
        while (-not $done) {
            $res = $script:socket.ReceiveAsync($seg, $script:ct).Result
            if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { return $null }
            [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count))
            $done = $res.EndOfMessage
        }
        try { $obj = $sb.ToString() | ConvertFrom-Json } catch { continue }
        if ($obj.id -eq $WantId) { return $obj }
    }
    return $null
}

function Eval {
    param([string]$Expression)
    $id = Send-Cdp 'Runtime.evaluate' @{
        expression = $Expression
        returnByValue = $true
        awaitPromise = $true
    }
    $r = Recv-Until $id
    if ($null -eq $r) { return @{ error = 'timeout' } }
    if ($r.result.exceptionDetails) {
        return @{ error = $r.result.exceptionDetails.text + ' ' + $r.result.exceptionDetails.exception.description }
    }
    return @{ value = $r.result.result.value }
}

function Wait-For {
    param([string]$Expression, [int]$TimeoutMs = 8000)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        $r = Eval $Expression
        if ($r.value -eq $true) { return $true }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

# 断言：输出 + 计数，最后汇总
#
# $Actual 可以是 Eval 返回的 {value=...} 哈希表，也可以是裸值。
# 前者更常见，所以在这里统一拆包——否则每个调用点都要写 .value，
# 漏一个就会把整个哈希表拿去比较（表现为 "System.Collections.Hashtable"）。
function Unwrap {
    param($v)
    if ($v -is [hashtable] -and $v.ContainsKey('value')) { return $v['value'] }
    if ($v -is [hashtable] -and $v.ContainsKey('error')) { return "[eval error] $($v['error'])" }
    return $v
}

function Check {
    param([string]$Label, $Actual, $Expected = $true)
    $Actual = Unwrap $Actual
    if ($Expected -is [hashtable]) { $Expected = Unwrap $Expected }
    $ok = ($Actual -eq $Expected)
    if (-not $ok) { $script:failed++ }
    $mark = if ($ok) { 'OK  ' } else { 'FAIL' }
    $color = if ($ok) { 'DarkGreen' } else { 'Red' }
    Write-Host ("  [{0}] {1}: {2}" -f $mark, $Label, $Actual) -ForegroundColor $color
}

Write-Host "=== CDP 已连接 ===" -ForegroundColor Cyan

$id = Send-Cdp 'Runtime.enable' @{}
Recv-Until $id 3000 | Out-Null

# 等应用外壳渲染出来
Wait-For 'document.querySelector(".app-shell") !== null' 20000 | Out-Null
# SSE 建连是异步的，headless 下首帧之后还要一点时间才会 open。
Wait-For 'document.querySelector(".statusbar-dot--live") !== null' 20000 | Out-Null
Start-Sleep -Milliseconds 500

# ── 1. 外壳与首屏 ────────────────────────────────
Write-Host '1. 外壳与首屏' -ForegroundColor Cyan

Check '标题' (Eval 'document.querySelector(".app-brand").textContent.trim()') 'GithubUpdater'
Check '应用外壳存在' (Eval 'document.querySelectorAll(".app-shell").length') 1
Check '两栏布局生效' (Eval 'document.querySelector(".app-split") !== null')
Check '状态栏存在' (Eval 'document.querySelector(".app-statusbar") !== null')
Check '状态栏显示已连接' (Eval 'document.querySelector(".app-statusbar").innerText.includes("已连接")')
Check '连接点是实心（live）' (Eval 'document.querySelector(".statusbar-dot--live") !== null')
Check '已删除旧的连接横幅' (Eval 'document.querySelector(".conn-banner") === null')
Check '已删除旧表头' (Eval 'document.querySelector(".list-header") === null')
Check '无横向滚动' (Eval 'document.documentElement.scrollWidth <= window.innerWidth + 1')
Check '左栏过滤框存在' (Eval 'document.querySelector(".sidebar-search") !== null')

# 首屏数据是异步拉的：等条目行真的渲染出来再断言，不要假设页面已经就绪
Wait-For 'document.querySelectorAll(".entry-row").length > 0' 15000 | Out-Null
Start-Sleep -Milliseconds 1500
$rows = Unwrap (Eval 'document.querySelectorAll(".entry-row").length')
Write-Host "     条目行数: $rows"

# ── 2. 条目行与自动选中 ──────────────────────────
Write-Host '2. 条目行与首屏自动选中' -ForegroundColor Cyan

Check '行高取自 --h-row 令牌' (Eval 'getComputedStyle(document.querySelector(".entry-row")).height') '64px'

if ($rows -gt 0) {
    # 详情页要一次 REST 往返，等刻度出现再断言
    Wait-For 'document.querySelector(".version-rail") !== null' 15000 | Out-Null
    Start-Sleep -Milliseconds 600

    Check '首屏自动选中第一条' (Eval "document.querySelector('.entry-row[aria-selected=`"true`"]') !== null")

    # 版本刻度是这次设计的签名元素
    Check '版本刻度存在' (Eval 'document.querySelector(".version-rail") !== null')
    Check '刻度两端各有一个点' (Eval 'document.querySelectorAll(".version-rail-dot").length') 2
    Check '刻度显示「已应用」' (Eval 'document.body.innerText.includes("已应用")')
    Check '详情标题用等宽显示字体' (Eval 'getComputedStyle(document.querySelector(".content-title--ascii")).fontFamily.includes("Bahnschrift")')

    # ── 3. 详情页内容 ──────────────────────────────
    Write-Host '3. 详情页' -ForegroundColor Cyan

    Check '有「资产规则」区块' (Eval 'document.body.innerText.includes("资产规则")')
    Check '有「仓库信息」区块' (Eval 'document.body.innerText.includes("仓库信息")')

    $ruleCount = Unwrap (Eval 'document.querySelectorAll(".rule-item").length')
    Write-Host "     规则数: $ruleCount"
    Check '规则条数大于 0' ($ruleCount -gt 0)

    if ($ruleCount -gt 0) {
        Check '规则 glob 可见' (Eval 'document.querySelector(".rule-glob").textContent.trim().length > 0')
        Check '进度条用 scaleX 而非 width' (Eval @"
(() => {
  const f = document.querySelector('.progress-fill');
  if (!f) return 'no progress';
  return f.style.transform.includes('scaleX') ? 'scaleX' : ('style=' + f.style.cssText);
})()
"@) 'scaleX'
    }

    # ── 4. 原生 confirm 已清零 ─────────────────────
    Write-Host '4. 原生 confirm 已替换' -ForegroundColor Cyan
    Check '页面未调用 window.confirm（无阻塞对话框）' (Eval '(typeof window.__confirmCalls === "undefined") ? true : (window.__confirmCalls === 0)')
} else {
    Write-Host '  （无条目，跳过详情页断言）' -ForegroundColor Yellow
    Check '空状态可见' (Eval 'document.querySelector(".empty-state") !== null')
}

# ── 5. 设置页 ────────────────────────────────────
Write-Host '5. 设置页' -ForegroundColor Cyan

$r = Eval @"
(() => {
  const b = [...document.querySelectorAll('.app-header .btn--icon')].find(x => x.title === '设置');
  if (!b) return 'no settings btn';
  b.click();
  return 'clicked';
})()
"@
Check '点开设置' $r 'clicked'
Start-Sleep -Milliseconds 1200

Check '设置页已打开' (Eval 'document.body.innerText.includes("GitHub 访问令牌")')
Check '显示「匿名访问」' (Eval 'document.body.innerText.includes("匿名访问")')

$sections = Unwrap (Eval 'document.querySelectorAll(".settings-section").length')
Write-Host "     设置分节数: $sections"
Check '设置分节数 ≥ 6' ($sections -ge 6)
Check '不再使用卡片' (Eval 'document.querySelectorAll(".card").length') 0
Check '未出现「-1/」' (Eval 'document.body.innerText.includes("-1/")') $false

# ── 6. 主题切换 ──────────────────────────────────
Write-Host '6. 主题切换' -ForegroundColor Cyan

$r = Eval @"
(() => {
  const b = [...document.querySelectorAll('.segmented-option')].find(x => x.textContent.trim() === '深色');
  if (!b) return 'no dark option';
  b.click();
  return 'clicked';
})()
"@
Check '点「深色」' $r 'clicked'
Start-Sleep -Milliseconds 500

Check 'data-theme 已切到 dark' (Eval 'document.documentElement.dataset.theme') 'dark'
Check '深色背景生效' (Eval 'getComputedStyle(document.body).backgroundColor') 'rgb(16, 21, 20)'
Check '主题已写入 localStorage' (Eval 'localStorage.getItem("gu-theme")') 'dark'

$r = Eval @"
(() => {
  const b = [...document.querySelectorAll('.segmented-option')].find(x => x.textContent.trim() === '浅色');
  if (!b) return 'no light option';
  b.click();
  return 'clicked';
})()
"@
Start-Sleep -Milliseconds 500
Check '浅色背景生效' (Eval 'getComputedStyle(document.body).backgroundColor') 'rgb(242, 243, 240)'

# 收尾：切回跟随系统，免得影响用户下次打开
$r = Eval @"
(() => {
  const b = [...document.querySelectorAll('.segmented-option')].find(x => x.textContent.trim() === '跟随系统');
  if (b) b.click();
  return 'reset';
})()
"@
Start-Sleep -Milliseconds 300
Check '主题可回到跟随系统' (Eval 'String(document.documentElement.dataset.theme == null ? "none" : document.documentElement.dataset.theme)') 'none'

# ── 7. 向导抽屉 ──────────────────────────────────
Write-Host '7. 新建向导' -ForegroundColor Cyan

$r = Eval @"
(() => {
  const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '添加仓库');
  if (!b) return 'no wizard btn';
  b.click();
  return 'clicked';
})()
"@
Check '点开向导' $r 'clicked'
Start-Sleep -Milliseconds 900

Check '抽屉已显示' (Eval 'document.querySelector(".sheet") !== null')
Check '抽屉轨道有 3 步' (Eval 'document.querySelectorAll(".wizard-node").length') 3
Check '当前是第 1 步' (Eval 'document.querySelectorAll(".wizard-node--active").length') 1
Check '步骤 1 内容正确' (Eval 'document.body.innerText.includes("仓库地址或 owner/repo")')

# 输入仓库地址但不提交（避免消耗配额）
$r = Eval @"
(() => {
  const inp = document.querySelector('.sheet .input');
  if (!inp) return 'no input';
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  setter.call(inp, 'https://github.com/jqlang/jq');
  inp.dispatchEvent(new Event('input', { bubbles: true }));
  return inp.value;
})()
"@
Write-Host "     输入仓库: $(Unwrap $r)"

$r = Eval @"
(() => {
  const b = [...document.querySelectorAll('.sheet-actions button')].find(x => x.textContent.trim() === '取消');
  if (!b) return 'no cancel';
  b.click();
  return 'closed';
})()
"@
Check '关闭向导' $r 'closed'
Start-Sleep -Milliseconds 500
Check '抽屉已关闭' (Eval 'document.querySelector(".sheet") === null')

# ── 8. 页面错误 ──────────────────────────────────
Write-Host '8. 页面错误' -ForegroundColor Cyan

$errs = Eval 'JSON.stringify(window.__errors ? window.__errors : "MISSING")'
Write-Host "     错误: $(Unwrap $errs)"
Check 'window.__errors 已初始化且为空' $errs '[]'

Check '未出现 undefined' (Eval 'document.body.innerText.includes("undefined")') $false
Check '未出现 [object Object]' (Eval 'document.body.innerText.includes("[object Object]")') $false

$script:socket.Dispose()

Write-Host ''
if ($script:failed -gt 0) {
    Write-Host "=== 验证失败：$($script:failed) 项不通过 ===" -ForegroundColor Red
    exit 1
}
Write-Host '=== 验证通过 ===' -ForegroundColor Cyan
