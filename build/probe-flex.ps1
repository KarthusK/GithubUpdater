#Requires -Version 7.0
# 弹性检查：reduced-motion、配色对比度、窄窗溢出。
# 要求 PowerShell 7+（pwsh），文件以 UTF-8 无 BOM 保存。
param([string]$Url)

$ErrorActionPreference = 'Stop'
if (-not $Url) { $Url = (Get-Content (Join-Path $PSScriptRoot '..\data\runtime.json') | ConvertFrom-Json).url }
$Url = $Url.TrimEnd('/') + '/'

$chrome = 'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe'
$port = Get-Random -Minimum 9900 -Maximum 9999
$profile = Join-Path $env:TEMP ("gu-flex-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$proc = Start-Process -FilePath $chrome -ArgumentList @(
    '--headless=new', '--disable-gpu', '--hide-scrollbars',
    "--remote-debugging-port=$port", "--user-data-dir=$profile",
    '--no-first-run', '--window-size=1600,900', $Url
) -PassThru -WindowStyle Hidden

$target = $null
for ($i = 0; $i -lt 80; $i++) {
    Start-Sleep -Milliseconds 250
    try {
        $list = Invoke-RestMethod -Uri "http://127.0.0.1:$port/json/list" -TimeoutSec 3
        $target = $list | Where-Object { $_.type -eq 'page' -and $_.url -like "$($Url.TrimEnd('/'))*" } | Select-Object -First 1
        if ($target) { break }
    } catch { }
}
if (-not $target) { Write-Host 'no target'; exit 1 }

$socket = [System.Net.WebSockets.ClientWebSocket]::new()
$ct = [System.Threading.CancellationToken]::None
$socket.ConnectAsync([Uri]$target.webSocketDebuggerUrl, $ct).Wait()
$script:msgId = 0

function Send-Cdp {
    param([string]$Method, $Params)
    $script:msgId++
    $msg = @{ id = $script:msgId; method = $Method; params = $Params } | ConvertTo-Json -Depth 12 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
    $socket.SendAsync([ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait()
    return $script:msgId
}
function Recv-Until {
    param([int]$WantId, [int]$TimeoutMs = 15000)
    $buf = New-Object byte[] 262144
    $seg = [ArraySegment[byte]]::new($buf)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $sb = New-Object System.Text.StringBuilder
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        [void]$sb.Clear()
        $done = $false
        while (-not $done) {
            $res = $socket.ReceiveAsync($seg, $ct).Result
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
    $id = Send-Cdp 'Runtime.evaluate' @{ expression = $Expression; returnByValue = $true; awaitPromise = $true }
    $r = Recv-Until $id
    if ($null -eq $r) { return '[timeout]' }
    if ($r.result.exceptionDetails) { return '[throw] ' + $r.result.exceptionDetails.text }
    return $r.result.result.value
}

$id = Send-Cdp 'Runtime.enable' @{}
Recv-Until $id 5000 | Out-Null

Start-Sleep -Seconds 4

Write-Host '=== 窄窗溢出检查 ===' -ForegroundColor Cyan
foreach ($w in @(1024, 1280, 1600)) {
    $id = Send-Cdp 'Emulation.setDeviceMetricsOverride' @{ width = $w; height = 800; deviceScaleFactor = 1; mobile = $false }
    Recv-Until $id 5000 | Out-Null
    Start-Sleep -Milliseconds 500
    $r = Eval 'document.documentElement.scrollWidth + "/" + window.innerWidth'
    $parts = $r -split '/'
    $ok = [int]$parts[0] -le [int]$parts[1] + 1
    $mark = if ($ok) { 'OK  ' } else { 'FAIL' }
    Write-Host ("  [{0}] {1}px 视口 scrollWidth/innerWidth = {2}" -f $mark, $w, $r)
}

$id = Send-Cdp 'Emulation.setDeviceMetricsOverride' @{ width = 1600; height = 900; deviceScaleFactor = 1; mobile = $false }
Recv-Until $id 5000 | Out-Null

Write-Host ''
Write-Host '=== reduced-motion 检查 ===' -ForegroundColor Cyan
$id = Send-Cdp 'Emulation.setEmulatedMedia' @{ features = @(@{ name = 'prefers-reduced-motion'; value = 'reduce' }) }
Recv-Until $id 5000 | Out-Null
Start-Sleep -Milliseconds 600

$dur = Eval 'getComputedStyle(document.querySelector(".btn")).transitionDuration'
Write-Host "  .btn transitionDuration = $dur"
$ok = ($dur -match '1e-05s|0\.00001s|^0s$|0\.01ms')
$mark = if ($ok) { 'OK  ' } else { 'FAIL' }
Write-Host ("  [{0}] 过渡已被压到接近 0" -f $mark)

$anim = Eval 'getComputedStyle(document.querySelector(".statusbar-dot--live")).animationDuration'
Write-Host "  .statusbar-dot animationDuration = $anim"

# 抽屉动画时长
$openWizard = Eval "(()=>{const b=[...document.querySelectorAll('button')].find(x=>x.textContent.trim()==='添加仓库'); if(b) b.click(); return 'x';})()"
Start-Sleep -Milliseconds 700
$sheetAnim = Eval '(()=>{const s=document.querySelector(".sheet"); return s ? getComputedStyle(s).animationDuration : "no sheet";})()'
Write-Host "  .sheet animationDuration = $sheetAnim"
$ok2 = ($sheetAnim -match '1e-05s|0\.00001s|^0s$|0\.01ms')
$mark2 = if ($ok2) { 'OK  ' } else { 'FAIL' }
Write-Host ("  [{0}] 抽屉动画已被压到接近 0" -f $mark2)

Write-Host ''
Write-Host '=== 深色主题对比度（相对亮度比）===' -ForegroundColor Cyan
$id = Send-Cdp 'Emulation.setEmulatedMedia' @{ features = @() }
Recv-Until $id 5000 | Out-Null

$contrast = Eval @"
(() => {
  const px = s => (s.match(/[\d.]+/g) || []).map(Number);
  const lum = ([r,g,b]) => {
    const f = c => { c = c/255; return c <= 0.03928 ? c/12.92 : Math.pow((c+0.055)/1.055, 2.4); };
    return 0.2126*f(r) + 0.7152*f(g) + 0.0722*f(b);
  };
  const ratio = (a, b) => {
    const l1 = lum(px(a)), l2 = lum(px(b));
    const hi = Math.max(l1,l2), lo = Math.min(l1,l2);
    return ((hi + 0.05) / (lo + 0.05)).toFixed(2);
  };
  const cs = getComputedStyle(document.body);
  const bg = cs.backgroundColor;
  const fg = cs.color;
  const el = document.querySelector('.section-meta') || document.querySelector('.content-subtitle');
  const dim = el ? getComputedStyle(el).color : fg;
  return JSON.stringify({
    theme: document.documentElement.dataset.theme || 'system',
    bodyBg: bg, bodyFg: fg,
    body: ratio(bg, fg),
    dim: ratio(bg, dim)
  });
})()
"@
Write-Host "  $contrast"

$socket.Dispose()
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 300
Remove-Item $profile -Recurse -Force -ErrorAction SilentlyContinue
