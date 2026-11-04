#Requires -Version 7.0
# 启动 GithubUpdater (dev)
#
# 要求 PowerShell 7+（pwsh），文件以 UTF-8 无 BOM 保存。
# run.ps1 用等价参数，日常开发二选一即可。

Write-Host "=== 启动 GithubUpdater (dev) ===" -ForegroundColor Cyan

$jar = "target\github-updater.jar"
if (-not (Test-Path $jar)) {
    Write-Error "未找到 $jar，请先执行: mvn -DskipTests package"
    exit 1
}

# JVM 参数已按 docs/09-阶段2验收记录.md 的实测结果固化。
#
# 关键认知（踩过的坑）：
# 1. 只调 -Xmx 压不动 Private Bytes。阶段 1 首次尝试 -Xmx96m 时仍有 149.5MB，
#    因为差额主要是原生内存（线程栈、代码缓存、Metaspace、direct memory、PerfData）。
# 2. 阶段 2 接入 SQLite + HttpClient 后，元空间从 15MB 涨到 28MB，
#    需要把 MaxMetaspaceSize 收紧。
# 3. 不要指定 -Xmn。实测 -Xmn8m 会让大 JSON 直接进老年代，峰值冲到 119MB；
#    去掉后让 JVM 自适应，峰值降到 112MB。
# 4. MaxHeapFreeRatio 收紧能让 GC 后主动把空闲堆还给 OS。
$jvmArgs = @(
    '-Xmx40m'                       # 堆上限。业务实际用量约 18MB
    '-Xms8m'                        # 初始堆调小
    '-XX:+UseSerialGC'              # 单用户桌面场景，元数据占用最少
    '-XX:MaxMetaspaceSize=48m'      # 限制元空间（实测用到约 28MB）
    '-XX:ReservedCodeCacheSize=24m' # 限制 JIT 代码缓存
    '-XX:MaxDirectMemorySize=4m'    # 限制 NIO direct buffer
    '-Xss256k'                      # 线程栈（约 30 个线程，收益明确）
    '-XX:-UsePerfData'              # 关闭性能计数器共享内存
    '-XX:CICompilerCount=2'         # 减少编译器线程
    '-XX:+UseCompressedOops'        # 小堆下用压缩指针
    '-XX:MinHeapFreeRatio=10'       # 堆占用低于 10% 时收缩
    '-XX:MaxHeapFreeRatio=25'       # 空闲超过 25% 时归还给 OS

    # 编码三件套。file.encoding 不影响 System.out/err ——
    # 日志走 stdout，只设 file.encoding 时中文日志仍是 GBK 字节，
    # 在按 UTF-8 解码的终端/输出面板里会变成乱码。详见 run.ps1 的注释。
    '-Dfile.encoding=UTF-8'
    '-Dstdout.encoding=UTF-8'
    '-Dstderr.encoding=UTF-8'

    # 关掉 Java2D 的 D3D/OpenGL 管线 —— 内存优化收益最大的一项。
    # AWT 初始化（托盘需要）会连带初始化 Java2D，默认走 D3D 并加载显卡驱动。
    # 实测 AMD 显卡下会多加载 AMDXN64.DLL(39MB) + networkdlllspx64.dll(22MB)，
    # 关掉后空载 Private Bytes 从 122.8MB 降到 80.1MB，稳态 156.5MB → 119.6MB。
    # 详见 docs/13-阶段6验收记录.md。
    '-Dsun.java2d.d3d=false'
    '-Dsun.java2d.opengl=false'
    '-Dsun.java2d.noddraw=true'
)

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { $java = 'java' }

Write-Host "启动…… 参数: $($jvmArgs -join ' ')" -ForegroundColor DarkGray
Write-Host "端口由系统分配，启动后看日志里的『服务已启动』一行" -ForegroundColor DarkGray
& $java @jvmArgs -jar $jar
