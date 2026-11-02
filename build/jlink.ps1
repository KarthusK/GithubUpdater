# jlink：裁剪 JRE
#
# 模块清单不能漏 java.net.http（HttpClient 在这个模块里，不在 java.base）
# 和 jdk.crypto.ec（Java 21 里仍是实模块，漏掉会导致 HTTPS 握手失败）。
# 详见 docs/06-构建与发布.md 与 docs/08-阶段1验收记录.md。

$ErrorActionPreference = 'Stop'

$modules = @(
    'java.base'
    'java.desktop'      # SystemTray / TrayIcon / Desktop.browse
    'java.sql'          # JDBC
    'java.naming'
    'java.net.http'     # HttpClient —— 极易漏
    'java.logging'
    'java.xml'
    'java.management'
    'jdk.unsupported'   # sun.misc.Unsafe，JGit 需要
    'jdk.crypto.ec'     # Java 21 必需，HTTPS 的 ECDHE
) -join ','

$jlink = Join-Path $env:JAVA_HOME 'bin\jlink.exe'
if (-not (Test-Path $jlink)) {
    Write-Error "未找到 jlink，请检查 JAVA_HOME（当前: $env:JAVA_HOME）"
    exit 1
}

$out = 'build\runtime'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }

Write-Host "裁剪 JRE -> $out" -ForegroundColor Cyan
& $jlink `
    --add-modules $modules `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=zip-6 `
    --output $out

if ($LASTEXITCODE -ne 0) { Write-Error "jlink 失败"; exit $LASTEXITCODE }

$size = (Get-ChildItem $out -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
Write-Host ("完成，体积 {0:N1} MB" -f $size) -ForegroundColor Green
