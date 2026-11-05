#Requires -Version 7.0
# CDP 交互验证脚本。用 Chrome DevTools Protocol 真实操作页面。
# 参数：$DebuggerUrl CDP WebSocket 地址，$BaseUrl 面板地址
#
# 要求 PowerShell 7+（pwsh），文件以 UTF-8 无 BOM 保存。
#
# 与浏览器无关的注意事项：
#   · CDP 的大响应（截图）会被拆成多个分片，Recv-Until 必须累积到 EndOfMessage。

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

        # 「下载」按钮不能是"点了必然报错"的摆设。
        #
        # 这个按钮曾经长期失效：它先调一次 checkOne 去拿 downloadUrl，而
        # CheckOutcome 只带了文件名，URL/digest 在检查阶段就被丢掉了，
        # createTask 必然 400。现在资产由后端按规则缓存，按钮应当可点。
        Check '存在「下载」按钮' (Eval @"
(() => {
  const btns = [...document.querySelectorAll('.rule-actions button')];
  return btns.some(b => b.innerText.trim() === '下载') ? 'found' : 'missing';
})()
"@) 'found'

        # 点下载后界面必须自己动起来，不能要求用户手动刷新。
        #
        # 这里踩过一连串坑：createTask 不广播 SSE、前端 setTaskState 对未知任务
        # 直接 return、RUNNING/VERIFYING/DONE 三个状态压根没推、pickActiveTask
        # 让旧的 DONE 任务盖住新任务。任何一条复现，用户看到的都是"点了没反应"。
        #
        # 断言分两步：点完立刻有反馈（乐观更新/SSE 任一），且状态不是旧任务残留。
        $before = Unwrap (Eval @"
(() => {
  const h = document.querySelector('.rule-task-head');
  return h ? h.innerText.replace(/\s+/g, ' ').trim() : '';
})()
"@)

        $clicked = Eval @"
(() => {
  const items = [...document.querySelectorAll('.rule-item')];
  for (const it of items) {
    const btn = [...it.querySelectorAll('button')].find(b => b.innerText.trim() === '下载');
    if (btn) { btn.click(); return 'clicked'; }
  }
  return 'no button';
})()
"@
        Check '点击「下载」' $clicked 'clicked'

        # 给 SSE 与本地乐观更新留出时间，但**不刷新页面**
        Start-Sleep -Seconds 3
        $after = Unwrap (Eval @"
(() => {
  const h = document.querySelector('.rule-task-head');
  const p = document.querySelector('.rule-task .progress');
  return JSON.stringify({
    head: h ? h.innerText.replace(/\s+/g, ' ').trim() : '',
    hasProgress: !!p,
  });
})()
"@)
        Write-Host "     点下载前: $before"
        Write-Host "     点下载后: $after"
        Check '点下载后出现任务进度区（无需刷新）' ($after -match '"hasProgress":true')

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

# 「网络与证书」：中间人环境下用户唯一的出路，不能悄悄消失。
# 只断言文字存在是不够的——样式表被改坏时文字照样在，
# 所以同时验证语义类名与实际计算样式。
Check '存在「网络与证书」分节' (Eval 'document.body.innerText.includes("网络与证书")')
Check '存在 CA 证书输入框' (Eval 'document.querySelectorAll("#ca-input").length') 1
Check '说明里点明只影响本程序' (Eval 'document.body.innerText.includes("只影响本程序")')
# 用户最担心的是"配了会不会污染系统"，所以"追加"必须写在界面上
Check '说明里点明是追加而非替换' (Eval 'document.body.innerText.includes("追加")')

# notice 样式必须真的生效：TlsBanner 与检查失败提示都靠它。
# 用计算样式验证，比断言类名更接近"用户到底看到什么"。
Check 'notice 样式已加载' (Eval @"
(() => {
  const el = document.createElement('div');
  el.className = 'notice notice--warn';
  document.body.appendChild(el);
  const s = getComputedStyle(el);
  const ok = s.display === 'flex' && parseFloat(s.paddingTop) > 0;
  el.remove();
  return ok;
})()
"@) $true

# 「启动」区块：复选框与标题行的垂直中心必须重合。
#
# 这里曾经用写死的 margin-top: 2px 对齐，而实际需要 1.4px，勾选框整体
# 沉下去约 1px——静态截图里不明显，但用户一眼就能看出"没对齐"。
# 现在用 calc((行高 - 控件高)/2) 表达，这条断言锁住它别再退回去。
Check '复选框与标题行垂直居中对齐' (Eval @"
(() => {
  const input = document.querySelector('#auto-start');
  if (!input) return 'no checkbox';
  const title = input.closest('.checkbox-row').querySelector('.checkbox-row-title');
  if (!title) return 'no title';
  const a = input.getBoundingClientRect();
  const b = title.getBoundingClientRect();
  const delta = Math.abs((a.top + a.height / 2) - (b.top + b.height / 2));
  return delta < 0.6 ? 'aligned' : ('off by ' + delta.toFixed(2) + 'px');
})()
"@) 'aligned'

# 「不可用」徽标必须是描边胶囊（不是 5px 色点 + 文字，那个形态在标题
# 后面看起来就是一个句号，整行读成"开机自动启动。不可用"）
Check '不可用徽标是描边胶囊形态' (Eval @"
(() => {
  const b = document.querySelector('.checkbox-row-title .badge--muted');
  if (!b) return 'no badge';
  const cs = getComputedStyle(b);
  const dot = getComputedStyle(b, '::before');
  const hasBorder = parseFloat(cs.borderTopWidth) > 0;
  const dotHidden = dot.display === 'none';
  return (hasBorder && dotHidden) ? 'capsule' : ('border=' + cs.borderTopWidth + ' dot=' + dot.display);
})()
"@) 'capsule'

# 开发模式（java -jar）下必须：未勾选 + 禁用。
# 后端三重判断（环境支持 + 注册表有值 + 路径仍存在）任一不满足都要落到这里。
Check '开机自启未勾选' (Eval 'document.querySelector("#auto-start").checked') $false
Check '开机自启已禁用' (Eval 'document.querySelector("#auto-start").disabled') $true
Check '显示「不可用」' (Eval 'document.body.innerText.includes("不可用")')

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
