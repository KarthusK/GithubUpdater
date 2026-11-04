#Requires -Version 7.0
# 启动一个独立的 headless Chrome 跑 verify-ui.ps1，结束后只关闭**自己启动的**那个实例。
#
# 要求 PowerShell 7+（pwsh），文件以 UTF-8 无 BOM 保存。
#
# 为什么不复用 9222：
#   · 用户自己的 Chrome 可能占着 9222，复用会连到错误的页面
#   · 连到旧服务残留页会让所有数据类断言假失败（踩过）
#
# 为什么用随机端口 + 独立 user-data-dir：
#   同一个端口上的旧实例会让 /json/list 返回多个 page，选错就是连错页面。
#   随机端口保证本次连接唯一对应本次启动的实例。
#
# 注意：绝不调用 Stop-Process -Name chrome —— 那会连用户的浏览器一起杀掉。

param(
    [string]$Url,
    [int]$Width = 1600,
    [int]$Height = 900
)

$ErrorActionPreference = 'Stop'

if (-not $Url) {
    $Url = (Get-Content (Join-Path $PSScriptRoot '..\data\runtime.json') | ConvertFrom-Json).url
}
$Url = $Url.TrimEnd('/') + '/'

$chrome = 'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe'
if (-not (Test-Path $chrome)) {
    Write-Host "找不到 Chrome: $chrome" -ForegroundColor Red
    exit 1
}

$port = Get-Random -Minimum 9400 -Maximum 9900
$profile = Join-Path $env:TEMP ("gu-verify-" + [guid]::NewGuid().ToString('N').Substring(0, 8))

Write-Host "启动 headless Chrome（端口 $port，profile $profile）" -ForegroundColor DarkGray

$proc = Start-Process -FilePath $chrome -ArgumentList @(
    '--headless=new',
    '--disable-gpu',
    '--hide-scrollbars',
    "--remote-debugging-port=$port",
    "--user-data-dir=$profile",
    '--no-first-run',
    '--no-default-browser-check',
    "--window-size=$Width,$Height",
    $Url
) -PassThru -WindowStyle Hidden

try {
    # 等调试端口就绪，并确认 target 是本页面
    $target = $null
    for ($i = 0; $i -lt 80; $i++) {
        Start-Sleep -Milliseconds 250
        try {
            $list = Invoke-RestMethod -Uri "http://127.0.0.1:$port/json/list" -TimeoutSec 3
            $target = $list | Where-Object { $_.type -eq 'page' -and $_.url -like "$($Url.TrimEnd('/'))*" } | Select-Object -First 1
            if ($target) { break }
        } catch { }
    }

    if (-not $target) {
        Write-Host '无法连接到调试目标（target 未出现）' -ForegroundColor Red
        exit 1
    }

    Write-Host "目标页面: $($target.url)" -ForegroundColor DarkGray
    Write-Host ''

    & (Join-Path $PSScriptRoot 'verify-ui.ps1') -DebuggerUrl $target.webSocketDebuggerUrl -BaseUrl $Url
    $code = $LASTEXITCODE
}
finally {
    # 只关自己启动的进程树。Chrome 会派生子进程，主进程退出后子进程通常自行退出。
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Milliseconds 400
    Remove-Item $profile -Recurse -Force -ErrorAction SilentlyContinue
}

exit $code
