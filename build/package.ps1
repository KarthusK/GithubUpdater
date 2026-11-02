# jpackage：生成 app-image
#
# 只做 app-image，不做 msi/exe 安装器——后者需要 WiX，而 JDK 21 只兼容 WiX 3。
# 详见 docs/06-构建与发布.md。

param(
    [switch]$Zip
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path 'target\github-updater.jar')) {
    Write-Error "未找到 target\github-updater.jar，请先执行: mvn -DskipTests package"
    exit 1
}
if (-not (Test-Path 'build\runtime')) {
    Write-Error "未找到 build\runtime，请先执行: powershell build\jlink.ps1"
    exit 1
}

$version = '0.1.0'
$appName = 'GithubUpdater'
$dest = 'build\out'
$image = Join-Path $dest $appName

if (Test-Path $image) { Remove-Item -Recurse -Force $image }

# 关键：--input 必须是只含业务 jar 的干净目录。
# 若直接指向 target，shade 插件留下的 original-github-updater.jar（空壳）
# 也会被收进 classpath，导致 Javalin 解析静态资源时先命中空 jar 而报
# "Static resource directory '/public' does not exist"。
$stage = 'build\stage'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item 'target\github-updater.jar' (Join-Path $stage 'github-updater.jar') -Force

# JVM 参数必须与 build/run-dev.ps1 保持一致，按 docs/09-阶段2验收记录.md 固化。
#
# 关键认知：只调 -Xmx 压不动 Private Bytes。
# 阶段 1 首次 -Xmx96m 时仍有 149.5MB；阶段 2 接入 SQLite 后元空间涨到 28MB。
# 不要指定 -Xmn：实测会让大 JSON 直入老年代，峰值冲到 119MB。
$javaOptions = @(
    '-Xmx40m'
    '-Xms8m'
    '-XX:+UseSerialGC'
    '-XX:MaxMetaspaceSize=48m'
    '-XX:ReservedCodeCacheSize=24m'
    '-XX:MaxDirectMemorySize=4m'
    '-Xss256k'
    '-XX:-UsePerfData'
    '-XX:CICompilerCount=2'
    '-XX:+UseCompressedOops'
    '-XX:MinHeapFreeRatio=10'
    '-XX:MaxHeapFreeRatio=25'
    '-Dfile.encoding=UTF-8'
    # 关掉 Java2D 的 Direct3D 管线。
    #
    # 这是本项目内存优化收益最大的一项，且原因非常隐蔽：
    # AWT 初始化（托盘需要）会连带初始化 Java2D 图形子系统，
    # 而它默认走 D3D 管线，会加载显卡驱动 DLL。
    #
    # 实测（本机 AMD 显卡）：开启 D3D 时进程加载 90 个 DLL、共 141.7MB，
    # 其中 AMDXN64.DLL 39.1MB + networkdlllspx64.dll 22.2MB —— 两个第三方
    # DLL 就占 61MB，而它们对"画一个托盘图标"毫无必要。
    # 关掉后 DLL 降到 70 个、68.5MB，空载 Private Bytes 122.8MB → 80.1MB，
    # 跑完完整流程的稳态 156.5MB → 119.6MB（省 36.9MB）。
    #
    # 本工具不画复杂图形（托盘图标是 16×16 的纯色块，Swing 菜单是平面文字），
    # 软件渲染完全够用，且不受用户显卡驱动质量影响（AMD/NVIDIA/Intel 的
    # D3D 初始化开销差异很大，关掉它让内存表现变得可预测）。
    '-Dsun.java2d.d3d=false'
    '-Dsun.java2d.opengl=false'
    '-Dsun.java2d.noddraw=true'
    # 原生库（JNA 的 jnidispatch.dll、SQLite 的 sqlitejdbc.dll）解压目录。
    #
    # 踩过的坑：打包产物启动时 JNA 报
    # "Failed to create temporary file for jnidispatch.dll: 拒绝访问"，
    # 而系统 TEMP 明明可写（Administrator 有 FullControl）。
    # 实测把 tmpdir 指到 data/tmp 后立刻正常。
    # 原因：系统 TEMP 常是 8.3 短名路径（如 C:\Users\ADMINI~1\...），
    # JNA 解压 DLL 后要 LoadLibrary 加载它，短名路径在部分环境下会失败。
    #
    # 注意：这个目录必须在 JVM 启动前就存在，否则 JVM 会打印
    # "java.io.tmpdir directory does not exist" 并可能回退到不可用位置。
    # 所以在下面打包完成后会预创建它（见脚本末尾）。
    '-Djava.io.tmpdir=$APPDIR/data/tmp'
    '-Dorg.sqlite.tmpdir=$APPDIR/data/tmp'
)

$optArgs = @()
foreach ($o in $javaOptions) { $optArgs += '--java-options'; $optArgs += $o }

$jpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
if (-not (Test-Path $jpackage)) {
    Write-Error "未找到 jpackage，请检查 JAVA_HOME"
    exit 1
}

Write-Host "打包 app-image -> $image" -ForegroundColor Cyan

$iconArg = @()
if (Test-Path 'build\icon.ico') { $iconArg = @('--icon', 'build\icon.ico') }

& $jpackage `
    --type app-image `
    --name $appName `
    --app-version $version `
    --input $stage `
    --main-jar github-updater.jar `
    --main-class com.githubupdater.Main `
    --runtime-image build\runtime `
    --dest $dest `
    @iconArg `
    @optArgs

if ($LASTEXITCODE -ne 0) { Write-Error "jpackage 失败"; exit $LASTEXITCODE }

# 预创建 data/tmp。
#
# .cfg 里 -Djava.io.tmpdir=$APPDIR/data/tmp 指向这个目录，而 JVM 在
# 启动最早期就要用它（JNA 解压 DLL）。目录不存在时 JVM 会打印
# "java.io.tmpdir directory does not exist"，JNA 仍可能失败。
# 打包时就建好，用户解压即用。
$dataTmp = Join-Path $image 'data\tmp'
New-Item -ItemType Directory -Path $dataTmp -Force | Out-Null
Write-Host "已预创建 $dataTmp" -ForegroundColor DarkGray

# 把使用说明放进产物根目录。
# 用户解压后只看到 exe 和几个目录，不知道要配 Token、不知道端口在哪，
# 这个文件是降低支持成本最有效的一件事。
if (Test-Path 'build\README.txt') {
    Copy-Item 'build\README.txt' (Join-Path $image 'README.txt') -Force
    Write-Host "已复制 README.txt" -ForegroundColor DarkGray
}

$size = (Get-ChildItem $image -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
Write-Host ("完成，体积 {0:N1} MB" -f $size) -ForegroundColor Green

if ($Zip) {
    $zipPath = "build\$appName-$version-win-x64.zip"
    if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
    Compress-Archive -Path $image -DestinationPath $zipPath
    $zsize = (Get-Item $zipPath).Length / 1MB
    Write-Host ("已生成 {0}（{1:N1} MB）" -f $zipPath, $zsize) -ForegroundColor Green
}
