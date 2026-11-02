#Requires -Version 5.1
<#
.SYNOPSIS
    GithubUpdater 开发态一键启动：检查环境 → 编译 → 启动。

.DESCRIPTION
    给"刚拿到代码、想跑起来看看"的人用。三件事全包：
      1. 检查 JDK / Maven 版本，缺什么直接说清楚去哪装
      2. 编译（首次会下载依赖）
      3. 用固化 JVM 参数启动 daemon

    为什么不只做"启动"：
      新人拿到代码第一步必然是编译。让脚本把编译也做了，双击一次就能跑，
      比"先看 README 找到 mvn 命令再回来"顺得多。

    为什么不在脚本里做 jlink/jpackage：
      那是发版流程，产物 76MB、耗时几分钟，日常开发不需要。
      需要打包请用 build/package.ps1。

.PARAMETER SkipBuild
    跳过编译，直接启动（改了代码后想快速重启时用）。

.PARAMETER Minimized
    静默启动：不自动打开浏览器（等价于 --minimized）。

.EXAMPLE
    .\run.ps1

.EXAMPLE
    .\run.ps1 -SkipBuild -Minimized
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$Minimized
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# ──────────────────────────── 输出辅助 ────────────────────────────

function Write-Step($n, $text) {
    Write-Host "[$n/3] " -ForegroundColor Cyan -NoNewline
    Write-Host $text
}

function Write-Ok($text) {
    Write-Host "      $text" -ForegroundColor DarkGray
}

function Write-Fail($title, $detail, $hint) {
    Write-Host ''
    Write-Host "[错误] $title" -ForegroundColor Red
    Write-Host ''
    if ($detail) {
        Write-Host "  $detail" -ForegroundColor Yellow
        Write-Host ''
    }
    if ($hint) {
        foreach ($line in $hint) { Write-Host "  $line" }
        Write-Host ''
    }
    exit 1
}

# ──────────────────────────── 横幅 ────────────────────────────

Write-Host ''
Write-Host '============================================================' -ForegroundColor DarkCyan
Write-Host '  GithubUpdater — 开发态启动' -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor DarkCyan
Write-Host ''

# ──────────────────────────── 1. 检查 JDK ────────────────────────────

# 优先用 JAVA_HOME，回退到 PATH 里的 java。
# 不直接用 `java -version` 的文本解析：不同厂商输出格式略有差异，
# 而 -XshowSettings:properties 的 java.specification.version 是标准格式。
$javaExe = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    'java'
}

if (-not (Get-Command $javaExe -ErrorAction SilentlyContinue)) {
    Write-Fail '找不到 Java' "尝试执行的路径：$javaExe" @(
        '请安装 JDK 21（Temurin 推荐）：'
        '  https://adoptium.net/temurin/releases/?version=21'
        ''
        '安装后设置环境变量 JAVA_HOME 指向安装目录，例如：'
        '  D:\dev_software\jdk21'
        ''
        '注意：改完环境变量必须【新开一个终端】才生效，'
        '      PATH 在进程启动时就已展开，旧终端看不到新值。'
    )
}

$javaMajor = $null
try {
    # 注意：java 把版本信息打到 stderr，而 $ErrorActionPreference='Stop' 会让
    # 原生命令的 stderr 变成终止性错误。这里必须临时放开为 Continue，
    # 并用 2>&1 合并两路输出后再匹配。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $settings = & $javaExe -XshowSettings:properties -version 2>&1 | Out-String
    $ErrorActionPreference = $prevEap

    if ($settings -match 'java\.specification\.version\s*=\s*(\d+)') {
        $javaMajor = [int]$matches[1]
    }
} catch {
    Write-Fail 'Java 无法执行' $_.Exception.Message @(
        "路径：$javaExe"
        '这个文件可能损坏，或不是真正的 JDK（比如只是个 JRE 目录）。'
    )
}

if (-not $javaMajor) {
    Write-Fail '无法确定 Java 版本' "路径：$javaExe" @(
        '请确认这是完整的 JDK 21，而不是 JRE 或残缺安装。'
    )
}

Write-Step 1 "Java 版本：$javaMajor"

if ($javaMajor -lt 21) {
    Write-Fail "需要 JDK 21 或更高，当前是 $javaMajor" '' @(
        '本项目用了 record、switch 表达式等较新的语法，'
        '低于 21 会直接编译失败。'
        ''
        '下载：https://adoptium.net/temurin/releases/?version=21'
    )
}

# ──────────────────────────── 2. 检查 Maven ────────────────────────────

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    Write-Fail '找不到 Maven（mvn）' '' @(
        '请安装 Maven 3.6.3 或更高版本：'
        '  https://maven.apache.org/download.cgi'
        ''
        '安装后把 %MAVEN_HOME%\bin 加入 PATH，并【新开终端】验证：mvn -v'
        ''
        '注意：实测 Maven 3.6.0 无法编译 release 21，需要 3.6.3 以上。'
        '      本项目使用 3.10.0。'
    )
}

# 提取 Maven 版本，低于 3.6.3 直接拒绝。
# 3.6.0 会报 "release version 21 not supported"，但那个错误信息
# 出现在编译阶段，混在一堆输出里很难注意到，所以在这里提前拦。
$mvnVer = $null
try {
    # 同 Java 版本检测：原生命令的 stderr 在 Stop 策略下会抛终止错误
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $mvnOut = & mvn -v 2>&1 | Out-String
    $ErrorActionPreference = $prevEap

    if ($mvnOut -match 'Apache Maven\s+(\d+)\.(\d+)\.(\d+)') {
        $mvnVer = [version]"$($matches[1]).$($matches[2]).$($matches[3])"
    }
} catch {
    # mvn -v 失败不阻断，后面编译会给出更具体的错误
}

Write-Step 2 "Maven 版本：$(if ($mvnVer) { $mvnVer } else { '未知' })"

if ($mvnVer -and $mvnVer -lt [version]'3.6.3') {
    Write-Fail "Maven 版本过低：$mvnVer" '' @(
        '需要 3.6.3 或更高（推荐 3.10.0）。'
        ''
        'Maven 3.6.0 编译 release 21 会失败，报 "release version 21 not supported"。'
        '下载：https://maven.apache.org/download.cgi'
    )
}

# ──────────────────────────── 3. 编译 ────────────────────────────

$jar = 'target\github-updater.jar'

if ($SkipBuild) {
    Write-Step 3 '跳过编译（-SkipBuild）'
    if (-not (Test-Path $jar)) {
        Write-Fail '找不到已编译的 jar' "期望路径：$jar" @(
            '因为指定了 -SkipBuild，但没有可用的编译产物。'
            '请去掉 -SkipBuild 重新运行，或先手动执行：mvn -DskipTests package'
        )
    }
} else {
    Write-Step 3 '编译中（首次会下载依赖，可能需要几分钟）……'
    Write-Host ''

    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        Write-Fail '编译失败' '请查看上面的错误信息。' @(
            '常见原因：'
            '  - 网络问题导致依赖下载失败（检查能否访问 Maven 中央仓库）'
            '  - JDK 版本不对（必须是 21）'
            '  - Maven 版本过低（需要 3.6.3+）'
            ''
            '想跳过编译直接启动，加 -SkipBuild 参数。'
        )
    }

    if (-not (Test-Path $jar)) {
        Write-Fail '编译完成但没有生成 jar' "期望路径：$jar" @(
            '这通常意味着 pom.xml 的 finalName 被改过。'
        )
    }
}

# ──────────────────────────── 4. 启动 ────────────────────────────

# JVM 参数是逐项实测调出来的，不要随手删减。
#
# 关键认知：只调 -Xmx 压不动 Private Bytes。差额主要来自原生内存
# （线程栈、代码缓存、Metaspace、direct memory、PerfData）。
# 详见 docs/08 与 docs/09 两份验收记录。
$jvmArgs = @(
    '-Xmx40m'                        # 堆上限，业务实际用量约 18MB
    '-Xms8m'                         # 初始堆调小
    '-XX:+UseSerialGC'               # 单用户桌面场景，元数据占用最少
    '-XX:MaxMetaspaceSize=48m'       # 实测用到约 30MB，28m 会 OOM
    '-XX:ReservedCodeCacheSize=24m'  # 限制 JIT 代码缓存
    '-XX:MaxDirectMemorySize=4m'     # 限制 NIO direct buffer
    '-Xss256k'                       # 线程栈（约 30 个线程，收益明确）
    '-XX:-UsePerfData'               # 关闭性能计数器共享内存
    '-XX:CICompilerCount=2'          # 减少编译器线程
    '-XX:+UseCompressedOops'         # 小堆下用压缩指针
    '-XX:MinHeapFreeRatio=10'        # 堆占用低于 10% 时收缩
    '-XX:MaxHeapFreeRatio=25'        # 空闲超过 25% 时归还给 OS
    '-Dfile.encoding=UTF-8'

    # 关掉 Java2D 的 D3D/OpenGL 管线 —— 内存优化收益最大的一项。
    # AWT 初始化（托盘需要）会连带初始化 Java2D，默认走 D3D 并加载显卡驱动。
    # 实测 AMD 显卡下多加载 AMDXN64.DLL(39MB) + networkdlllspx64.dll(22MB)，
    # 关掉后空载 122.8MB → 80.1MB，稳态 156.5MB → 119.6MB。
    # 详见 docs/13-阶段6验收记录.md。
    '-Dsun.java2d.d3d=false'
    '-Dsun.java2d.opengl=false'
    '-Dsun.java2d.noddraw=true'
)

Write-Host ''
Write-Host '============================================================' -ForegroundColor DarkCyan
Write-Host '  启动中……' -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor DarkCyan
Write-Host ''
Write-Host '  面板地址会在启动后打印在下面，也可以随时查看：' -ForegroundColor Gray
Write-Host '    data\runtime.json  中的 url 字段' -ForegroundColor DarkGray
Write-Host ''
Write-Host '  关闭浏览器不会停止程序。要退出请右键托盘图标选 Exit。' -ForegroundColor Gray
Write-Host '  按 Ctrl+C 可强制停止。' -ForegroundColor Gray
Write-Host ''
Write-Host '------------------------------------------------------------' -ForegroundColor DarkGray
Write-Host ''

# 程序参数必须放在 -jar <jar> 之后，否则会被当成 JVM 选项。
# 踩过：把 --minimized 追加进 $jvmArgs 会得到
#   java -Xmx40m ... --minimized -jar xxx.jar
# 报 "Unrecognized option: --minimized"。
$appArgs = @()
if ($Minimized) {
    $appArgs += '--minimized'
}

& $javaExe @jvmArgs -jar $jar @appArgs

# 正常退出（用户从托盘选了 Exit）
Write-Host ''
Write-Host '程序已退出。' -ForegroundColor DarkGray
