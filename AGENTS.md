# AGENTS.md

GithubUpdater：Windows 上的 GitHub 软件版本跟踪与下载工具。常驻 Java daemon 抓取 Release、下载资产、校验完整性并替换文件；界面由**浏览器**访问本地 HTTP 服务提供，关掉浏览器即释放全部界面内存。

**当前状态：阶段 1–6 全部完成**。骨架、数据层、配置层、GitHub 客户端、检查服务、下载器、SSE 推送、应用器、前端（Preact + htm 零构建）、系统托盘、**打包发布（jlink + jpackage + 开机自启）** 全部落地并真机验证。

**已实测通过的门槛**：**打包产物空载 77.2MB、跑完完整流程后稳态 119.6MB**。**阈值 150MB**（2026-11-02 由 120MB 放宽，见 [`docs/07-验收与风险.md`](docs/07-验收与风险.md) 的变更记录）。

**测试**：`mvn test` → **138 个单元测试全部通过**；前端用 `build/verify-ui.ps1`（Chrome DevTools Protocol）端到端验证。

**发布产物**：`build/GithubUpdater-0.1.0-win-x64.zip`，**59.5 MB**，解压即用，无需安装 Java。

**已验证的真实功能**：
- 下载 `jq-windows-amd64.exe`（1035264 字节），SHA-256 与官方 digest 完全一致，文件头为有效 PE
- 暂停于 16375 字节后续传，最终文件 SHA-256 仍与官方一致（证明不重下、不拼接损坏）
- **BINARY 替换**：23 字节占位 → 1035264 字节真实 PE，备份保留，回滚可完整恢复
- **占用检测**：Restart Manager 识别出持有句柄的进程并返回 423；`force:true` 可结束进程后替换
- **ARCHIVE 解压**：356 个条目解出，用户自放文件未被删
- **SOURCE**：真实 clone `jqlang/jq` 并检出 `jq-1.8.2`；有本地改动时返回 409 且改动完整保留
- **SSE**：10 个并发连接下 `/api/health` 仍 5ms 返回；连接存活 33 秒以上并收到心跳
- **UI**：列表/详情/设置/向导四页在真实 Chrome 里渲染正确，0 页面错误
- **打包产物**：安全三重防护、HTTPS、下载、应用、回滚、开机自启、路径自愈全部通过


设计文档是唯一权威来源，动手前必须先读：

| 文档 | 内容 |
|---|---|
| [`docs/01-架构设计.md`](docs/01-架构设计.md) | 进程模型、模块划分、10 条 ADR |
| [`docs/02-数据库设计.md`](docs/02-数据库设计.md) | 6 张表 DDL、PRAGMA、迁移策略 |
| [`docs/03-API接口.md`](docs/03-API接口.md) | REST 端点、SSE 事件、错误码、安全模型 |
| [`docs/04-后端设计.md`](docs/04-后端设计.md) | 四个服务的实现逻辑、并发模型 |
| [`docs/05-前端设计.md`](docs/05-前端设计.md) | Preact + htm、状态管理、虚拟滚动、设计令牌 |
| [`docs/06-构建与发布.md`](docs/06-构建与发布.md) | jlink 模块清单、jpackage 参数、开机自启 |
| [`docs/07-验收与风险.md`](docs/07-验收与风险.md) | 验收标准、测试策略、风险登记 |
| [`docs/08-阶段1验收记录.md`](docs/08-阶段1验收记录.md) | 阶段 1 内存实测、Javalin 7 API 差异 |
| [`docs/09-阶段2验收记录.md`](docs/09-阶段2验收记录.md) | 阶段 2 内存实测、4 个缺陷修复、TLS 中间人问题 |
| [`docs/10-阶段3验收记录.md`](docs/10-阶段3验收记录.md) | 阶段 3 下载器验证、暂停误报修复、阈值变更 |
| [`docs/11-阶段4验收记录.md`](docs/11-阶段4验收记录.md) | 阶段 4 应用器验证、JGit 四个坑、内存实测 |
| [`docs/12-阶段5验收记录.md`](docs/12-阶段5验收记录.md) | 阶段 5 前端/托盘验证、SSE 五次尝试、线程池优化 |
| [`docs/13-阶段6验收记录.md`](docs/13-阶段6验收记录.md) | 阶段 6 打包发布、JNA 短名路径坑、D3D 显存优化 |
| [`docs/14-托盘方案简化记录.md`](docs/14-托盘方案简化记录.md) | ADR-010 托盘改用原生菜单、四态图标、过度归因的教训 |

## 常用命令

环境已就绪：JDK 21.0.8 Temurin（`D:\dev_software\jdk21`）、Maven 3.10.0（`%MAVEN_HOME%` = `D:\JAVA_software\apache-maven-3.10.0`，本地仓库 `D:\JAVA_software\repository`）、git 2.55。

```powershell
mvn -v                              # 自检：必须显示 Maven 3.10.0 + Java version 21.0.8

.\run.ps1                           # 开发态一键启动：检查环境 → 编译 → 启动
.\run.ps1 -SkipBuild                # 跳过编译直接启动
.\run.ps1 -Minimized                # 静默启动（不自动开浏览器）

mvn -DskipTests package             # 编译 + 打包 → target/github-updater.jar
mvn test                            # 跑全部单元测试（当前 138 个）
mvn test -Dtest=AssetMatcherTest    # 跑单个测试类

powershell build/run-dev.ps1        # 启动 daemon（run.ps1 用等价参数，二选一）
powershell build/run-verify-ui.ps1  # CDP 端到端验证前端：自己起独立 headless Chrome，跑完自动清理
powershell build/probe-flex.ps1      # 窄窗溢出 / reduced-motion / 配色对比度
powershell build/jlink.ps1           # jlink 裁剪 JRE → build/runtime
powershell build/package.ps1         # jpackage app-image → build/out/GithubUpdater/
powershell build/package.ps1 -Zip    # 追加生成发布用 zip
```

**注意 `verify-ui.ps1` 不直接跑**：它需要 `-DebuggerUrl` 参数（一个已经连好的 CDP WebSocket 地址），
手工传很容易连到错误的页面。用 `run-verify-ui.ps1`，它会起一个独立实例并自动收尾。

**启动后如何找到面板地址**：优先读 `data/runtime.json`（`url` 字段），这是唯一真相源。
日志里也会打印 `服务已启动：http://127.0.0.1:<端口>`；**双击托盘图标**打开面板，右键只有一个 `Exit`。
端口由系统分配，每次不同。

**验证方式**：

1. `mvn -v` 版本正确
2. `mvn -DskipTests package` 成功，产出 `target/github-updater.jar`
3. 启动后 `GET /` 返回 200、页面内 `<meta name="api-token">` 有值
4. 带 token 请求 `/api/health` 返回 200；**不带 token 返回 401**；**伪造 `Host: evil.com` 返回 403**
5. **前端**：用 `build/run-verify-ui.ps1` 走 CDP，断言外壳/两栏/状态栏 + 版本刻度 + 详情 + 设置 + 主题切换 + 向导 + 0 页面错误
6. **前端弹性**：用 `build/probe-flex.ps1` 断言 1024/1280/1600 三档无横向溢出、reduced-motion 生效、两套主题对比度达标
7. **SSE 长连接**：`curl` 或 Java HttpClient 连 `/api/events`，确认 30 秒后仍未 EOF 且收到 `: keepalive`
8. 打包后 `build/out/GithubUpdater/GithubUpdater.exe` 能启动

## 必须注意的坑

### 环境与构建

- **改完环境变量必须新开终端**：`PATH` 用 `%MAVEN_HOME%\bin` 引用，值类型是 `ExpandString`，**进程启动时即完成展开**。改完变量后旧终端里 `mvn -v` 仍显示旧版本，这是排查时最容易误判的一点。同理，用 `[Environment]::GetEnvironmentVariable(...,'Machine')` 读到的是**展开后**的值，要看原始串必须用注册表 `GetValue('Path',$null,'DoNotExpandEnvironmentNames')`。
- **Javalin 7 的 API 与 6 不兼容，别照 6 的教程写**：路由与生命周期钩子全部移到 `config.routes` 上（`config.routes.get(...)`、`config.routes.beforeMatched(...)`），`Javalin` 实例只剩 `create/start/stop/port`；横幅开关在 `config.startup.showJavalinBanner`。**拿不准时用 `javap -classpath <javalin.jar> io.javalin.Javalin` 核对签名。**
- **不要用 Javalin 的 `staticFiles` 提供前端资源**：它用 `ClassLoader.getResources("/public")` 查目录条目，jpackage 启动器的 classloader 与 `java -jar` 行为不同，会抛 "Static resource directory does not exist"。改用 `config.routes.get("/assets/*", handler)` + `getResourceAsStream` 自行提供。**通配必须用 `/assets/*`，`{path}` 不跨 `/`，匹配不到多级路径。**
- **VSCode 里点 Run 报 `java.lang.Error: Unresolved compilation problems` 不是代码问题**：这是 Eclipse JDT 编译器（redhat.java 扩展）的特性——**遇到编译错误时它不拒绝生成 class，而是生成一个残缺 class**，内容等价于 `throw new Error("Unresolved compilation problems: ...")`。所以看到的是 `java.lang.Error` 而不是 `ClassNotFoundException`，极具误导性。
  - **根因**：项目里没有 `.project` / `.classpath`，说明 VSCode 把它当**隐式项目**（自己扫 `.java` 文件），而不是 Maven 项目。隐式项目**不读 `pom.xml`**，项目模型不完整，增量编译时会"找不到同项目里的类型"——`Main.java` 引用 `AutoStart` 就中招了。
  - **危害不止 VSCode**：隐式项目把编译产物写到 `target/classes`，**与 Maven 共用同一目录**。VSCode 一编译就覆盖掉 Maven 的正常 class，之后 `java -cp target/classes` 和打包产物都会带着这个残缺 class。
  - **确认方法**（别急着查代码，代码多半没问题）：
    ```powershell
    # 看 class 里有没有被植入错误
    javap -v target/classes/com/githubupdater/Main.class | Select-String 'Unresolved compilation'
    # 全量扫描
    Get-ChildItem target/classes -Recurse -Filter *.class | ForEach-Object {
      if ([System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($_.FullName)) -match 'Unresolved compilation') { $_.Name }
    }
    ```
  - **修复**：`.vscode/settings.json` 里设 `java.import.maven.enabled: true`，然后在命令面板执行 **`Java: Clean Java Language Server Workspace` → Reload and delete**，让它重新按 Maven 项目导入（之后会生成 `.project`/`.classpath`）。修完必须 `mvn clean compile` 把被污染的 `target/classes` 整个重建——**只删单个 class 不够**，因为可能有多个被污染。
  - **交叉验证技巧**：从 VSCode 的启动 argfile（`%TEMP%\cp_*.argfile`）里取出它用的完整 classpath，手动 `java -cp "<那串>" com.githubupdater.Main`。能起来就证明**代码和依赖都没问题，纯粹是 class 被污染**。
- **PowerShell 脚本必须存为 UTF-8 with BOM**：Windows PowerShell 5.1 按 GBK 解析无 BOM 的 UTF-8，中文注释会让字符串截断并报"缺少终止符"。`build/*.ps1` 全部是带 BOM 的。
- **用文本编辑工具改 `build/*.ps1` 会静默抹掉 BOM**：`edit`/`write` 这类工具按 UTF-8 读写时默认不写 BOM，改完中文注释还在、`git diff` 也正常，但 PowerShell 5.1 下会突然报"缺少终止符"。**改完必须复查**：
  ```powershell
  Get-ChildItem build -Filter '*.ps1' | ForEach-Object {
    $b = [System.IO.File]::ReadAllBytes($_.FullName)
    "{0} BOM={1}" -f $_.Name, ($b.Length -ge 3 -and $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF)
  }
  ```
  补回 BOM：`[System.IO.File]::WriteAllText($p, $text, (New-Object System.Text.UTF8Encoding($true)))`。更稳的验收方式是用 **PowerShell 5.1 的解析器**（不是 `pwsh` 7）跑一遍 `[System.Management.Automation.Language.Parser]::ParseFile()`——只有 5.1 才会暴露 GBK 误解析问题。
- **JVM 参数不能只调 `-Xmx`**：实测 `-Xmx96m` 时 Private Bytes 仍有 **149.5MB**（超阈值），因为差额主要来自原生内存（线程栈、代码缓存、Metaspace、direct memory、PerfData）。固化的完整参数集见 `build/run-dev.ps1` 与 `build/package.ps1`，**不要随手删减**。实测结果见 `docs/08-阶段1验收记录.md` 与 `docs/09-阶段2验收记录.md`。
- **不要指定 `-Xmn`**：实测 `-Xmn8m` 会让大 JSON 直接进老年代，峰值从 112MB 冲到 119MB。去掉后让 JVM 自适应新生代反而更省。
- **`MaxHeapFreeRatio` 能真正把内存还给 OS**：配 `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25`，GC 后空闲堆会被收缩归还。这是压住"堆只涨不落"的关键，不是可选优化。
- **不要尝试 `jpackage --type msi/exe`**：那需要 WiX，而 **JDK 21 只兼容 WiX 3**，装了 WiX 4/5/6 会直接失败（JDK-8319457）。本项目只做 `app-image`，**完全绕开 WiX**。
- **发布物是目录不是单文件 exe**：JVM 形态限制。用户拿到 zip，解压即用。想做真单 exe 只能上 GraalVM native-image，第一版不采用。
- **`jlink` 模块清单不能漏两项**：`java.net.http`（`HttpClient` 在这个模块里，不在 `java.base`）和 `jdk.crypto.ec`（HTTPS 必需）。漏掉后者的表现是握手失败且报错不直观。**发版前必须真实访问一次 GitHub 验证。**
- **`--compress=zip-6`**：旧写法 `--compress 2` 在 JDK 21+ 已废弃。
- **sqlite-jdbc 不要加 `natives-windows` classifier**：`classifier` 是**替换**主 artifact，而那个 jar 只含 DLL、**不含 `org.sqlite.JDBC` 驱动类**，会抛 `No suitable driver found for jdbc:sqlite:`。必须用主 jar（12MB，含驱动 + 全平台原生库）。另外它自 3.43.2.0 起**强依赖 `slf4j-api`**，缺失会抛 `NoClassDefFoundError`。
- **`maven-shade-plugin` 必须加 `ServicesResourceTransformer`**：SQLite 驱动通过 `META-INF/services/java.sql.Driver` 注册，不合并会导致 `No suitable driver found`。
- **`-Dsun.java2d.d3d=false` 等三个参数不能删**：这是内存优化收益最大的一项。AWT 初始化（托盘需要）会连带初始化 Java2D，默认走 D3D 并加载显卡驱动。实测 AMD 显卡下多加载 `AMDXN64.DLL`(39.1MB) + `networkdlllspx64.dll`(22.2MB)，空载 Private Bytes 122.8MB、跑完流程 156.5MB；关掉后 80.1MB / 119.6MB。详见 [`docs/13-阶段6验收记录.md`](docs/13-阶段6验收记录.md)。
- **打包产物必须设 `-Djava.io.tmpdir=$APPDIR/data/tmp`**：系统 TEMP 常是 8.3 短名路径（`C:\Users\ADMINI~1\...`），JNA 解压 `jnidispatch.dll` 后 `LoadLibrary` 加载它时会报**"拒绝访问"**——而该目录在 PowerShell 里明明可写（Administrator 有 FullControl）。这个错误信息极具误导性，会让人以为是权限或杀软问题。
- **`data/tmp` 必须在 JVM 启动前就存在**：`.cfg` 里的 `-Djava.io.tmpdir` 指向它，JVM 启动早期就要用（JNA 解压）。目录不存在时 JVM 打印 "java.io.tmpdir directory does not exist" 并可能失败。`package.ps1` 打包时预创建。
- **`sqlite-jdbc` 每次启动解压一个带随机 UUID 的新 DLL，且不清理**：文件名形如 `sqlite-3.54.1.0-<uuid>-sqlitejdbc.dll`，每个约 1MB。实测开发期堆到 **46.7MB / 96 个文件**。`Main.cleanupStaleNativeLibs()` 在启动时清理「本次启动之前」的残留——**只能在启动时清，退出时 DLL 仍被映射，Windows 拒绝删除**。如果又发现堆积，先确认这段逻辑没被误删。
- **PowerShell 里 `$ErrorActionPreference='Stop'` 会让原生命令的 stderr 变成终止性错误**：`java -version`、`mvn -v` 都把信息打到 stderr，直接 `& java -version 2>&1` 会抛异常。正确写法是临时放开策略：
  ```powershell
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $out = & $exe -version 2>&1 | Out-String
  $ErrorActionPreference = $prev
  ```
- **`-jar` 之前不能放程序参数**：`java -Xmx40m ... --minimized -jar app.jar` 会报 `Unrecognized option: --minimized`。程序参数必须在 jar 路径之后：`java @jvmArgs -jar $jar @appArgs`。
- **不要用 `.bat` 做带中文的启动脚本**：cmd.exe 按系统 ANSI 代码页（zh-CN 是 GBK）解析 `.bat`。无 BOM 的 UTF-8 会被当 GBK 读出乱码并被当成命令执行；全角标点会被误认为命令分隔符破坏脚本结构。更麻烦的是 `for /f '...'` 里不能放带引号的绝对路径（会报"系统找不到指定的路径"）。**用 `.ps1`**，编码问题只需 BOM，转义规则也简单得多。
- **`.ps1` 有 BOM 仍可能输出乱码——那是输出链路的编码不一致，不是脚本编码问题**：在 VSCode 里用 **Code Runner 扩展**跑 `run.ps1`，中文会变成 `寮€鍙戞€佸惎鍔?`；同一脚本在 `cmd` 窗口里完全正常。
  - **链路**：脚本里的中文字符串 →（PowerShell 编码）→ 字节流 →（Code Runner 解码）→ 显示。两端必须用同一套编码。
  - **VSCode 终端/输出面板按 UTF-8 解码**，而 Windows PowerShell 5.1 的控制台输出默认跟随系统 ANSI 代码页（936/GBK）。GBK 字节被当 UTF-8 解释 → 乱码。
  - **正确解法**：脚本开头把 `[Console]::OutputEncoding` 和 `$OutputEncoding` 都强制设为 **UTF-8 无 BOM**（`New-Object System.Text.UTF8Encoding $false`），与下游对齐。
  - **⚠️ 曾经写错过一次**：一开始"跟随系统 ANSI 代码页"设成 GBK，结果 Code Runner 那边**依然乱码**（因为 Code Runner 要的是 UTF-8），只是我当时的验证脚本用了 `StandardOutputEncoding=GBK` 自己解码，把错误掩盖了。**这个教训比结论本身重要：验证脚本必须用"下游真实使用的编码"去解码，否则得到的是假阳性。**
  - **判断方法**：抓输出的**原始字节**看十六进制。`E6 A0 87` = "标"的 UTF-8 编码；`B1 EA` = GBK 编码。按字节判断比按"能不能显示"判断可靠得多。
  - **不要用 `Start-Job` 验证编码**：PowerShell 的 Job 会对自己捕获的字节再解一次码，结果是 artifact，不代表真实终端也不代表 Code Runner。用 `cmd /c "... > file"` 抓原始字节。

- **`-Dfile.encoding=UTF-8` 管不到 `System.out` / `System.err`**：它只影响**文件读写**和 `String.getBytes()` 的默认字符集。日志走 stdout，所以只设 `file.encoding` 时，中文日志输出的仍是 **GBK 字节**，在按 UTF-8 解码的终端里就是 `����������Ŀ¼��`。
  - **JDK 19+ 的 stdout/stderr 编码独立**，由 `stdout.encoding` / `stderr.encoding` 决定，未指定时跟随操作系统 ANSI 代码页（简体中文 Windows = GBK）。
  - **完整的三件套**，少一个都会漏：
    ```
    -Dfile.encoding=UTF-8
    -Dstdout.encoding=UTF-8
    -Dstderr.encoding=UTF-8
    ```
  - 这三项已加进 `run.ps1`、`build/run-dev.ps1`、`build/package.ps1` 三处，**改一处必须同步另外两处**（否则用打包产物跑出来的日志又是乱码）。
  - **`slf4j-simple` 默认把日志打到 stderr**：抓日志要读 `RedirectStandardError`，读 stdout 会得到 0 字节（实测踩到）。
  - **验证方法**：写个最小 Java 程序打印中文，用同一套参数跑，然后分别按 UTF-8 与 GBK 解码比对。打印 `stdout.encoding` 的值可以直接确认参数是否生效。

- **排查"是不是代码改动引入的"之前，先看 `git status` 和文件修改时间**：上面这个乱码一度被怀疑是前端重构引入的，实际 `git status` 干净、`run.ps1` 最后修改时间早于重构。**先用时间戳排除，能省掉一整轮无效排查。**

- **`AutoStart.selfHeal()` 的异常必须兜住**：它是"锦上添花"的功能（修正自启路径），但抛异常会终止 main 线程，导致程序启动即崩溃。任何非核心功能都不该有这个权力。
- **验证打包产物必须在项目目录之外运行**：在 `build/out/` 下运行时，本会话的沙箱环境会限制注册表访问（`RegOpenKeyEx` 返回 5 ERROR_ACCESS_DENIED），表现为"开机自启启用失败"，**会误判成代码 bug**。复制到 `%TEMP%` 下再测。排查依据：`Get-Acl` 会看到一个未解析的 SID `S-1-4-*`。
- **`jlink --list-modules --module-path build/runtime` 显示的不是裁剪结果**：它会显示 70 个模块（完整 JDK 的解析视图）。真实清单要看 `build/runtime/release` 文件里的 `MODULES=` 行，或直接跑 `runtime/bin/java --list-modules`。实测是 14 个模块、48MB。

### 下载与文件替换（静默失败高发区）

- **`BodyHandlers.ofFile()` 是截断语义**，续传必须用 `ofInputStream()` 自己 `RandomAccessFile` 定位写入。用错会把已下载部分清空。
- **`BodyHandlers` 不检查状态码**，必须自己判断 206/200/416，否则会把 404 的错误页当文件内容写进去。
- **`If-Range` + ETag 是必须项不是优化项**：没有它，远端文件在两次下载间被替换（同名不同内容）时会拼出一个"看起来成功"的坏文件。**这是本项目最危险的静默失败。**
- **必须校验 `Content-Range` 的起点等于本地偏移**，否则偏移错位照样拼出坏文件。
- **收到 200 必须清空 `.part` 从头写**（服务器不支持续传，或 If-Range 判定文件已变）；**收到 416 必须重新探测远端长度**再决定"已完成"还是"重下"。
- **`ATOMIC_MOVE` 只在同卷内保证原子**，跨卷抛 `AtomicMoveNotSupportedException`。`.part`、`.ready`、目标文件必须同卷；跨卷必须先复制到目标盘的同级临时文件再改名。
- **Restart Manager 的 `RmGetList` 只注册文件，绝不注册目录**：注册目录会让它返回 `ERROR_ACCESS_DENIED(5)`，而官方对该错误码的定义是"A path registered is a session is a directory"。网上大量"返回 5 是权限不足"的说法是**误读**。
- **不用 `MoveFileEx + MOVEFILE_DELAY_UNTIL_REBOOT`**：它要求调用者属于管理员组，普通用户直接失败。本方案用"先重命名运行中的 exe，再写新文件"——Windows 允许重命名正在运行的 exe，无需重启也无需提权。
- **`ZipInputStream` 解压必须防 Zip Slip**：校验解压后路径仍在 staging 内，拒绝 `../` 穿越。
- **"只留最新一份"是留存策略，不是写入策略**：即使最终只有一个文件，写入仍走 `.part → 校验 → 原子改名`，绝不直接写最终文件名。
- **暂停不能只设标志，必须主动关流**：工作线程阻塞在 `in.read()` 时，`cancelFlag` 检查不到——网络慢时用户点暂停要等几十秒，体验上等同于"暂停无效"。正确做法是 `HttpFetcher.StreamAbortHandle`：暂停时主动 `close()` 底层流，让 `read()` 立即返回。**同时要在 `IOException` 捕获处先判断 `cancelFlag`**，否则会把"暂停"误报成 `NETWORK_ERROR`（实测踩到，任务变成 FAILED）。
- **暂停与取消必须用独立意图映射区分**：两者都是"停止下载"，但暂停要保留 `.part` 以便续传、取消要删除。工作线程无法自行判断用户意图，必须用 `cancelIntents` 显式记录。
- **`ProgressEvent` 用静态工厂而非构造器重载**：`(long speed)` 与 `(TaskState state)` 两个重载在调用点会歧义。改用 `ProgressEvent.progress(...)` / `ProgressEvent.stateChanged(...)`。

### GitHub API

- **匿名请求即使返回 304 也扣配额**：官方限定"304 免配额"必须**同时**满足"返回 304"且"带 `Authorization` 头"。所以 ETag 在匿名下只省流量不省配额，界面上不要宣传反了。
- **不要把 `/releases/latest` 当"最新版本号"**：作者可用 `make_latest` 手动指定旧 release，且它按 `created_at` 排序，而官方定义 `created_at` 是"该 release 所指向 commit 的日期"不是发布日。**版本判定只看 release `id` 是否变化**，不做语义版本比较。
- **直连 `browser_download_url` 不消耗 API 配额**；走 API asset 端点才白扣 1 次。默认直连。
- **下载会 302 跳到 `release-assets.githubusercontent.com`**（不是 `objects.githubusercontent.com`），签名 URL 有效期约 1 小时，**不能缓存复用**。
- **digest 覆盖不全**：GitHub 自 2025-06 起为手工上传的资产提供 sha256，**源码自动生成的 zip/tar 永远没有**，2025-04 前的老资产为 `null`。且删掉重传同名资产 digest 会变——它只证明"与你下载时 GitHub 记录一致"，不能证明"自发布后未被篡改"。

### 安全

- **本地 HTTP 服务必须做三重防护**：绑 `127.0.0.1`、校验 `Host` 头、Bearer token。`Host` 校验**不能省**——绑定本地挡不住 DNS rebinding，而主流框架默认都不做。
- **`GET /api/settings` 绝不返回 token 明文**，只返回是否已配置。
- **`targetDir` 必须过 `PathGuard`**：拒绝 `..` 穿越与系统目录，否则攻击者可让工具往任意位置写文件。

### 数据库

- **`PRAGMA foreign_keys = ON` 是连接级设置**，每条新连接都要执行，否则外键约束静默失效。
- **所有写操作必须串行化**到单一线程：SQLite 单写者模型，多线程直接写会频繁 `SQLITE_BUSY`，`busy_timeout` 只能缓解不能消除。
- **"插入后要拿到自增 id"必须用 `DbWriter.submitAndGet`，不能用 `submit`**：`submit` 是异步的，任务还没执行调用方就已经读走了结果变量，**静默返回 0**。这是不报错但功能全错的 bug。`submitAndGet` 内部有防死锁检查（禁止在 db-writer 线程内调用自身）。
- **进度落库要节流**（每 500ms），不是每个 chunk 都写。
- **迁移前自动备份**数据库；迁移失败必须拒绝启动，不带着损坏的库运行。
- **迁移只做加法**：新增表/列/索引，不改已有列语义，不删列。

### 网络与 TLS

- **`PKIX path building failed` 通常不是代码问题，是中间人代理**：某些加速器/代理会解密 HTTPS 并用自己的根证书重签。Windows 信任它（PowerShell 正常），但 Java 用自己的 cacerts 不信任，故握手失败。**排查方法**：用 `SslStream` 读服务器实际证书，看 `Issuer` 是不是正常 CA（正常应为 Sectigo/DigiCert 等）。关掉代理后即可恢复。
- **不要为了绕过它而把第三方根证书导入 JDK 全局 cacerts**：那会让整个 JVM 信任该中间人，你的 PAT 也会被它看到。正确做法是提供"自定义 CA 证书路径"配置项，让用户显式指定。

### 前端

- **vendor 里的 `.mjs` 必须本地化，不能从 CDN 加载**：本工具是离线桌面软件。四个文件（`preact.mjs`、`preact-hooks.mjs`、`htm.mjs`、`htm-preact.mjs`）提交进 git，`importmap` 指向 `/assets/vendor/`。**`htm-preact.mjs` 不能漏**——它从裸模块名 `htm` 和 `preact` 导入，importmap 里必须同时映射这两个。
- **htm 模板里不写复杂表达式**：每次渲染都会重新解析，逻辑提到外面。
- **`useStore` 的 selector 不能在渲染中内联定义**（每次都是新引用，导致反复重订阅），必须提升到模块作用域或用 `useCallback`。`store.js` 里已导出稳定的 `selectors` 对象，直接用。
- **进度更新不走全局 store 广播**：高频事件会拖垮列表渲染，用独立订阅通道 + `requestAnimationFrame` 合并（见 `progress.js`）。
- **`EventSource` 不能用**：它重连时不带 `Authorization` 头。必须用 `fetch` + `ReadableStream` 自己实现 SSE 读取。
- **列表虚拟滚动用 `aria-activedescendant` 而非移动真实焦点**：真实焦点会随被销毁的行一起丢失。
- **CSS 不许出现字面量**：所有间距、字号、颜色走 `base.css` 的 `:root` 令牌。审阅方法：grep 所有数字字面量，凡是不在 `:root` 里的都改成令牌。**1px 边框和百分比除外**（几何常量，不是设计决策）。
- **Bahnschrift 不含中文字形**：`--font-display` 只能挂在**纯 ASCII** 元素上（品牌字标、tag、版本号、配额、文件名）。挂到中文上会回落雅黑，在字重和基线上露破绽。页面标题默认走 `--font-ui`，只有纯 ASCII 的标题才加 `.content-title--ascii` 换字体。这类"字体回落"在截图里表现为字形突变，很容易被误认成渲染 bug。
- **行高必须从 `--h-row` 令牌读，不要在 JS 里硬编码**：虚拟滚动按固定行高算位置，CSS 改了行高而 JS 没跟上，所有行的位置整体错位。`ListView.js` 用 `readCssPx('--h-row', 64)`。同理行高不随断点变化。
- **`--measure`（68ch）是正文行宽上限，不是整页宽度上限**：拿它当 `.content-inner` 的 max-width 会让规则列表挤在左半边、右侧空出几百像素。结构区用 `--w-content`（940px）。
- **遮罩 `--scrim` 不能从 `--bg` 派生**：浅色主题下 `--bg` 本来就是亮的，`color-mix(--bg-sunken, transparent)` 混出来的是白色遮罩，背景完全不后退，抽屉看起来像浮在空中。遮罩必须是暗的，两套主题分别给值。
- **主题的媒体查询必须用 `:root:not([data-theme])` 限定**：否则 `prefers-color-scheme` 和显式 `data-theme` 会打架。首屏防闪烁用 `<head>` 里的**同步**脚本（`theme-boot.js`，非 module、非 defer）。
- **浅色令牌写了两遍**（`@media` 里一份、`[data-theme="light"]` 一份）：自定义属性无法批量赋值，只能复制。改一处必须同步改另一处，`verify-ui.ps1` 有背景色断言兜底。
- **`--text-faint` 承载 11px 的时间戳，必须 ≥ 4.5:1**：它曾经是 `#79847b`，浅色下只有 3.49:1。视觉上"淡一点更好看"，但小字达不到 AA 就是可读性缺陷。改配色后必须重算对比度（`build/probe-flex.ps1` 会打印）。
- **不用原生 `confirm()`**：它用操作系统对话框风格、阻塞、样式无法控制，是"网页套壳"最强的信号。用 `components/confirm.js` 的 `confirmAsync()`，`body` 传 htm 模板而不是字符串（进程列表需要 `<ul>`，`\n` 在对话框里不换行）。
- **`quota.remaining < 0` 表示"配额未知"，不是负数**：`Quota.unknown()` 的占位值是 -1。界面必须用 `formatQuota()` 与 `isQuotaLow()`，直接拼字符串会显示成 "-1/60"。
- **用 CDP 验证前端，不要靠截图**：`build/verify-ui.ps1` 通过 Chrome DevTools Protocol 真实点击并断言 DOM 状态。截图不能断言"按钮点下去有没有反应"，也不适合判断交互细节。
- **跑 `verify-ui.ps1` 必须用 `build/run-verify-ui.ps1` 包一层**：它起一个**独立**的 headless Chrome（随机端口 + 随机 `user-data-dir`），跑完只关自己启动的那个。直接复用 9222 会连到旧服务残留的页面，**所有数据类断言会假失败**（实测踩到，排查了很久）。**绝不要 `Stop-Process -Name chrome`**——那会连用户自己的浏览器一起杀掉。
- **CDP 的大响应（截图 base64）会被拆成多个 WebSocket 分片**：`ReceiveAsync` 必须累积到 `EndOfMessage` 才能拼成完整 JSON，否则永远等不到匹配的 `id`，表现为脚本挂死。
- **PowerShell 5.1 不支持 `||` 和三元 `?:`**：验证脚本里嵌 JS 时尤其容易忘。另外**单引号字符串不能包含单引号**，写 `querySelector('[aria-selected="true"]')` 会截断字符串并报"缺少终止符"。
- **`$Ws` 这类形参名会和脚本内部的 `$sw`（Stopwatch）冲突**（PowerShell 变量名大小写不敏感）：`verify-ui.ps1` 的形参已改名为 `$DebuggerUrl`。

### SSE（Javalin 的坑，试了五次才对）

- **Javalin 在请求处理结束时会无条件关闭响应**：`JavalinServlet` 的 `finally` 里固定执行 `ServletOutputStream.close()` + `AsyncContext.complete()`（字节码里三处重复）。所以**任何 handler 返回后连接都会被关闭**。
- **`ctx.res().startAsync()` 自己接管无效**：实测连接 78ms 后 EOF。
- **Javalin 的 `SseHandler` 也不行**：51ms 后 EOF，响应头还带 `Connection: close`。给它显式 timeout 也没用。
- **`ctx.async()` 回调里立即返回同样不行**：52ms 后 EOF。
- **唯一可行写法**：`ctx.async(cfg -> cfg.timeout = 0, runnable)`，且 **runnable 里必须一直不返回**（`while (!client.isClosed()) Thread.sleep(500)`）。
- **为什么这样不占请求线程**：`ctx.async()` 的回调跑在 `JavalinDefaultAsyncThreadPool` 上，与 Jetty 的请求线程池分开。实测 10 个并发 SSE 时普通 API 仍 5ms 返回。
- **代价是每个连接占一个异步线程**，所以 `SseHub.MAX_CLIENTS = 16` 是必要的上限，不是保守。
- **`check.done` 必须在 `finally` 里推**：只在成功路径推的话，检查抛异常时前端会永远显示"检查中"，按钮永远灰着。
- **`checkAll` 必须传 `onEach` 回调**：传 `null` 时每条结果都不会推给界面，用户点完只能干等。

### 线程池（内存优化收益最大的一项）

- **Jetty 12 默认 `maxThreads=200`、`minThreads=8`，启动就拉起 16 个线程**：对单用户桌面工具是纯浪费。实测收紧到 `min=4/max=12/idle=30s` 后，线程数 43→27，**Private Bytes 152.5MB→113.8MB，省 38.7MB**。
- **配置方式是 `cfg.jetty.threadPool = pool`**，不是 `modifyServer`——`Server.setThreadPool` 在 Jetty 12 里不存在，线程池必须在构造 `Server` 时传入，Javalin 通过这个字段处理。
- **SSE 不占 Jetty 线程**（走 Javalin 异步池），所以 `maxThreads=12` 对"浏览器几个请求 + 1 个 SSE"完全够用。

### 应用器与 Git（JGit 的坑最密集）

- **应用前必须再校验一次 digest**：`.ready` 文件在磁盘上可能被外部改动。"下载时校验过"不等于"应用时仍然有效"，这两件事必须分开做。
- **没有 digest 必须让用户显式确认**：`unverifiedConfirmed: true` 才继续。不能因为"上游没给校验和"就默认放行，那等于对这类资产完全不做完整性保证。
- **ARCHIVE 覆盖绝不删目标目录中多出的文件**：只覆盖同名文件。用户会在程序目录里放配置、插件、许可证，镜像式同步对他们等同于数据丢失。
- **`CloneCommand` 没有 `setConfig` 方法**：网上不少示例是错的。JGit 的 `CloneCommand` 只有 `setBranch`/`setBranchesToClone`/`setNoCheckout` 等，配置必须另想办法写（见下条）。
- **`core.autocrlf` 必须在 clone *之前* 生效**：`CloneCommand` 没有 `setConfig`，很多写法是"clone 完再设"，**那是无效的**——clone 时已按默认值处理了换行，之后所有 CRLF 文本文件都被判为 modified，于是下一次更新永远返回 `Dirty`，源码类彻底不可用。正确做法是在 clone 前通过 `SystemReader.getInstance().getUserConfig()` 写入（配合 `configureUserConfig` 指向数据目录，不污染用户全局 git 配置）。
- **clone 完再 checkout 到 tag 会报 `Checkout conflict with files`**：clone 默认已检出默认分支，工作区有全部文件，再切 tag 就冲突。**正确做法是 clone 时直接 `setBranch("refs/tags/" + tag)`**，JGit 会以 detached HEAD 检出该 tag。
- **`setNoCheckout(true)` 不能用来规避上面的冲突**：实测仍报同一个错误——工作区虽空，但 DirCache 未初始化。四种组合实测只有 `setBranch` 系列可行。
- **`JGitInternalException` 不是 `GitAPIException`**：它继承 `RuntimeException`，只 catch `GitAPIException` 会让它逃出去变成裸 HTTP 500。典型触发：clone 到"已存在且非空"的目录。**catch 必须同时兜 `RuntimeException`**，并且提前检查目录状态给出可操作的错误。
- **JGit 的 clone 超时与 `-Xss256k` 无关**：曾误判为线程栈太小，实测 512k/1m/2m 都会间歇失败，真实原因是 GitHub 连接超时。**不要去改固化 JVM 参数**。
- **`GitRunnerTest` 必须用本地仓库**：`git init` + 两个 tag 就能覆盖 clone/切换/Dirty 全部逻辑，且不消耗 GitHub 配额、不受网络影响。用真实仓库写单测是错的做法。

### 托盘（一次过度归因的教训）

- **`java.awt.PopupMenu` 在 Windows 上不由 Java 渲染**：它把菜单项交给 Win32 的 `TrackPopupMenu`，由 explorer.exe 用系统字体画。`MenuItem.setFont()` **只是被 Java 对象记录**（`getFont()` 能原样读回），对实际渲染毫无影响。这是中文显示成方块的真正原因。
- **`Font.canDisplayUpTo()` 和 `Graphics2D.drawString()` 证明不了托盘菜单能显示中文**：它们只证明"Java 能画中文"，完全没触及托盘菜单的真实渲染路径。当年就是靠这两个证据误判为"字体问题"，白改了一轮字体配置。
- **不要为了中文菜单改用 Swing 自绘**：这是本项目代价最高的一次过度归因。无主窗口的 Java 进程拿不到焦点，`JPopupMenu` 的"点外面自动关闭"完全失效，只能靠每 80ms 轮询 `GetAsyncKeyState` 补，而那套补丁引出了三个线上 bug（进程退不掉 / 菜单不隐藏 / 退出项失效）。
- **正确解法是降低需求**（ADR-010）：菜单只留 `Exit`（纯 ASCII，任何字体都能画），双击图标打开面板。原生菜单由系统托管，**自动隐藏、Esc 关闭、点外部关闭全部免费**。
- **当为让方案可用而不断打补丁时，回头质疑方案的前提**。中文菜单不是需求，只是当时顺手的选择；换成英文，整个问题就消失了。这个判断方式比任何一个具体技术结论都值钱。
- **`TrayIcon.setImage()` 可以动态换图标**，不必重建 `TrayIcon`。状态图标要**颜色 + 形状双重编码**（蓝点/下箭头/对勾/叉），色盲用户也能分辨，不能只靠颜色。

### 内存验收

- **必须看 `Private Bytes` / 提交内存，不能看任务管理器"内存"列**：后者是**活动专用工作集**，`EmptyWorkingSet` 能让它数字变好看，但对提交内存毫无影响——只是把页从工作集挪到 Standby 链表。用工作集验收等于允许任何人作弊。
- **实测基线**：
  | 阶段 / 启动方式 | Private Bytes |
  |---|---|
  | 阶段 1 骨架（`java -jar`） | 80.8–89.8 MB |
  | 阶段 1 发布产物（jlink 裁剪 JRE） | **57.9 MB** |
  | 阶段 2 接入 SQLite + HttpClient 后稳态 | **110.5 MB** |
  | 阶段 3 跑完真实下载后稳态 | **115.5 MB** |
  | 阶段 4 跑完四类真实应用操作后 | **121.8 MB** |
  | 阶段 5 前端 + 托盘 + SSE（**未调线程池时**） | **152.5 MB** ❌ 超阈值 |
  | 阶段 5 收紧 Jetty 线程池后空载 | **113.8 MB** |
  | 阶段 5 稳态（浏览器连着 + 完整 UI 操作） | **123.7 MB** |
  | 阶段 6 打包产物（**未关 D3D 时**） | **156.5 MB** ❌ 超阈值 |
  | 阶段 6 打包产物空载（关 D3D 后） | **80.1 MB** |
  | 阶段 6 打包产物稳态（跑完下载+应用+回滚） | **119.6 MB** |
  | 托盘简化后打包产物空载（ADR-010） | **77.2 MB** |

  裁剪后的 JRE 占用反而更低，发布产物优于开发环境。**每次修改 JVM 参数或新增重量级依赖后必须重测**：
  ```powershell
  $p = Get-Process -Id <pid>; $p.PrivateMemorySize64 / 1MB
  ```
- **测量 jpackage 产物时要取较大的那个进程**：它是两个进程——启动器（约 1.3MB）+ 实际 JVM。
- **下载器必须流式处理**：`HttpFetcher` 用 64KB 缓冲、`DigestVerifier` 流式读，所以 2MB 文件和 100MB 文件的内存占用相同。**任何"把文件读进 byte[]"的写法都会让大文件直接打爆堆**。
- **加业务功能后要重新验证**：阶段 2 接入 SQLite 后元空间从 15MB 涨到 28MB；阶段 3 加入下载器后升到 29MB；阶段 4 加入 JGit 后升到 121.8MB；阶段 5 加入前端 + 托盘 + SSE 后升到 123.7MB（**其中线程池优化省了 38.7MB，否则会是 152.5MB 直接超阈值**）；阶段 6 打包后 156.5MB（**关 D3D 省 36.9MB 后降到 119.6MB**）。**当前距 150MB 阈值有 30.4MB 余量**，超阈值时优先考虑：关 D3D（省 36.9MB）、收紧线程池（省 38.7MB）、JSON 改流式解析、或上 GraalVM native-image。
- **两次超阈值都是原生内存问题，不是堆问题**：阶段 5 的 152.5MB 来自 Jetty 默认 200 线程池，阶段 6 的 156.5MB 来自 AWT 拉起的 D3D 显卡驱动 DLL。**调 `-Xmx` 完全无效**，必须查"谁在加载什么"——`Get-Process` 的 `Modules` 列表比堆 dump 有用得多。


## 架构要点

- **单进程 daemon + 外部浏览器**。daemon 内部四层：传输（Javalin）→ 应用（四个 Service）→ 领域（纯逻辑无 IO）→ 基础设施（GitHub/HTTP/SQLite/Git/文件）。**领域层不依赖任何基础设施实现**，可在单元测试里直接跑。
- **不做任何后台定时轮询**（ADR-002）。世界只在用户点按钮时变化。关着面板不会发现新版——这是有意取舍，不是缺陷。
- **线程模型固定**：Jetty 线程池（**min 4 / max 12**，见"线程池"一节）处理 HTTP；
  1 个调度线程 + 最多 2 个下载工作线程；1 个检查线程（顺序执行）；1 个数据库写线程；
  AWT EDT 管托盘；Javalin 异步池承载 SSE 长连接（每个连接一个线程，上限 16）。
- **四类资产只是 `kind` 不同**：`SOURCE` / `ARCHIVE` / `BINARY` / `COLLECTION`，共用"条目 + 资产规则"模型，不要为它们写四套并行代码。
- **应用动作按 kind 分派**，`COLLECTION` 没有"应用"动作，终点是文件落盘。
- **源码类用 JGit 而非系统 git CLI**（ADR-007）：JGit 核心 2.8MB、纯 Java、许可证 BSD 系；git CLI 要么要求用户装 git，要么捆绑 GPLv2 的 Git for Windows。已知 Windows 陷阱：跨盘 `FileBasedConfig` 死锁、`.lock` 残留、checkout 遇占用、`core.autocrlf` 与 CLI 有差异——启动时都要处理。
- **`SOURCE` 类默认允许 `git checkout` 到 tag，但有本地改动必须中止**，绝不 stash/reset。不内置构建，留给 `after_apply_cmd` 钩子。
- **端口由系统分配**（绑 `0`），实际值写入 `data/runtime.json`，这是发现端口的唯一真相源。**双击托盘图标**打开面板，右键只有一个 `Exit`（见 ADR-010）。
- **便携模式**：所有可变数据在 exe 同级 `data/`。启动时**真实试写**检测目录可写（不查 ACL），失败即明确报错退出。开机自启要校验注册表路径与当前 exe 一致，不一致则自愈。
- **配置与状态分离**：`data/config.json` 人类可读可手改，`data/state.db` 存高频写入。两者独立版本号，各自迁移。

## 代码约定

- **注释与文档用中文**；技术名词、代码标识符、API 名称保留英文原文。
- **包结构**：`com.githubupdater.{config,transport,app,domain,infra,util}`，依赖方向只能由外向内。
- **API 响应统一格式**：成功 `{"ok": true, "data": {...}}`，失败 `{"ok": false, "error": {"code", "message", "detail"}}`。错误码见 `docs/03-API接口.md`，新增错误码必须同步文档。
- **时间戳一律 Unix 毫秒**（`long`），不用 ISO 字符串，前端 `new Date(ms)` 转换。
- **前端不用 TypeScript**，用 `// @ts-check` + JSDoc。**不引入构建步骤**。
- **CSS 不许出现字面量**：所有间距、字号、颜色走 `docs/05-前端设计.md` 里的设计令牌（`var(--space-*)`、`var(--text-*)`）。做不到就说明设计系统破功了，这是"界面丑"的真正来源。

## 提交规范

- 使用多行形式：首行 `type: 中文概要`（type 用 feat/fix/docs/refactor/chore/test），空一行后用 `- ` 列出要点
- 要点条数控制在 1-4 行，概括改动与行为影响，不写代码细节
- 只提交本次相关文件，不加多余文件
- 改动涉及设计决策时，同步更新 `docs/` 对应章节，并在此文件的"必须注意的坑"里补一条（如果它是踩过才知道的）
