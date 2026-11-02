# CDP 交互验证脚本。用 Chrome DevTools Protocol 真实操作页面。
# 参数：$url 面板地址，$ws CDP WebSocket 地址

param(
    [string]$Ws,
    [string]$BaseUrl
)

$ErrorActionPreference = 'Stop'

# 注意：$Ws 是 param 变量，不要用 $script:ws 覆盖它（PowerShell 变量名大小写不敏感）
$script:socket = [System.Net.WebSockets.ClientWebSocket]::new()
$script:ct = [System.Threading.CancellationToken]::None
$script:socket.ConnectAsync([Uri]$Ws, $script:ct).Wait()
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
    $buf = New-Object byte[] 4194304
    $seg = [ArraySegment[byte]]::new($buf)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        $res = $script:socket.ReceiveAsync($seg, $script:ct).Result
        if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { return $null }
        $txt = [System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count)
        try {
            $obj = $txt | ConvertFrom-Json
        } catch { continue }
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

Write-Host "=== CDP 已连接 ===" -ForegroundColor Cyan

# 先开启 Runtime 域
$id = Send-Cdp 'Runtime.enable' @{}
Recv-Until $id 3000 | Out-Null

# ── 1. 列表页 ────────────────────────────────────
$r = Eval "document.querySelectorAll('.entry-row').length"
Write-Host "1. 列表行数: $($r.value)"

$r = Eval "document.querySelector('.app-title').textContent.trim()"
Write-Host "   标题: $($r.value)"

$r = Eval "document.querySelectorAll('.status-dot--ok').length > 0"
Write-Host "   SSE 已连接: $($r.value)"

# ── 2. 点击条目进详情页 ──────────────────────────
$r = Eval "document.querySelector('.entry-row').click(); 'clicked'"
Start-Sleep -Milliseconds 800

$r = Eval "document.querySelectorAll('.rule-item').length"
Write-Host "2. 详情页规则数: $($r.value)"

$r = Eval "document.body.innerText.includes('资产规则')"
Write-Host "   有「资产规则」区块: $($r.value)"

$r = Eval "document.body.innerText.includes('基本信息')"
Write-Host "   有「基本信息」区块: $($r.value)"

$r = Eval "document.querySelectorAll('.rule-glob').length > 0 ? document.querySelector('.rule-glob').textContent : 'none'"
Write-Host "   规则 glob: $($r.value)"

# 备份按钮
$r = Eval @"
(() => {
  const btns = [...document.querySelectorAll('button')].filter(b => b.textContent.trim() === '备份');
  if (btns.length === 0) return 'no backup btn';
  btns[0].click();
  return 'clicked';
})()
"@
Write-Host "   点备份按钮: $($r.value)"
Start-Sleep -Milliseconds 800

$r = Eval "document.body.innerText.includes('可回滚的备份')"
Write-Host "   展开备份面板: $($r.value)"

# ── 3. 返回列表，进设置页 ────────────────────────
$r = Eval @"
(() => {
  const b = [...document.querySelectorAll('button')].find(x => x.textContent.includes('返回'));
  if (!b) return 'no back btn';
  b.click();
  return 'clicked';
})()
"@
Write-Host "3. 返回列表: $($r.value)"
Start-Sleep -Milliseconds 600

$r = Eval @"
(() => {
  const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '设置');
  if (!b) return 'no settings btn';
  b.click();
  return 'clicked';
})()
"@
Write-Host "   点设置: $($r.value)"
Start-Sleep -Milliseconds 1000

$r = Eval "document.body.innerText.includes('GitHub 访问令牌')"
Write-Host "   设置页已打开: $($r.value)"

$r = Eval "document.body.innerText.includes('匿名访问')"
Write-Host "   显示「匿名访问」: $($r.value)"

$r = Eval "document.querySelectorAll('.card').length"
Write-Host "   设置卡片数: $($r.value)"

# 检查配额不显示 -1
$r = Eval "document.body.innerText.includes('-1/')"
Write-Host "   未出现「-1/」: $(-not $r.value)"

# ── 4. 回列表，打开向导 ──────────────────────────
$r = Eval @"
(() => {
  const b = [...document.querySelectorAll('button')].find(x => x.textContent.includes('返回'));
  if (b) b.click();
  return 'back';
})()
"@
Start-Sleep -Milliseconds 600

$r = Eval @"
(() => {
  const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '新建条目');
  if (!b) return 'no wizard btn';
  b.click();
  return 'clicked';
})()
"@
Write-Host "4. 打开向导: $($r.value)"
Start-Sleep -Milliseconds 800

$r = Eval "document.querySelector('.dialog') !== null"
Write-Host "   对话框已显示: $($r.value)"

$r = Eval "document.body.innerText.includes('仓库地址或 owner/repo')"
Write-Host "   步骤 1 内容: $($r.value)"

$r = Eval "document.querySelectorAll('.wizard-step').length"
Write-Host "   向导步骤数: $($r.value)"

# 输入仓库地址但不提交（避免消耗配额）
$r = Eval @"
(() => {
  const inp = document.querySelector('.dialog .input');
  if (!inp) return 'no input';
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  setter.call(inp, 'https://github.com/jqlang/jq');
  inp.dispatchEvent(new Event('input', { bubbles: true }));
  return inp.value;
})()
"@
Write-Host "   输入仓库: $($r.value)"

# 关闭向导
$r = Eval @"
(() => {
  const b = [...document.querySelectorAll('.dialog-footer button')].find(x => x.textContent.trim() === '取消');
  if (!b) return 'no cancel';
  b.click();
  return 'closed';
})()
"@
Write-Host "   关闭向导: $($r.value)"
Start-Sleep -Milliseconds 500

$r = Eval "document.querySelector('.dialog') === null"
Write-Host "   对话框已关闭: $($r.value)"

# ── 5. 检查控制台错误 ────────────────────────────
$r = Eval "window.__errors ? window.__errors.length : 0"
Write-Host "5. 页面错误数: $($r.value)"

# 检查是否有未渲染的模板残留
$r = Eval "document.body.innerText.includes('undefined')"
Write-Host "   未出现 undefined: $(-not $r.value)"

$r = Eval "document.body.innerText.includes('[object Object]')"
Write-Host "   未出现 [object Object]: $(-not $r.value)"

$script:socket.Dispose()
Write-Host "`n=== 验证完成 ===" -ForegroundColor Cyan
