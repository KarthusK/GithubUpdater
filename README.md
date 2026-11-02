# GithubUpdater

> Windows 上的 GitHub 软件版本跟踪与下载工具。常驻托盘后台抓取 Release、下载资产、校验完整性并替换文件，界面用浏览器打开。

GitHub 下载慢、资产类型杂、想挂后台慢慢下——这个工具就是干这个的。它常驻托盘，下载和校验都在后台跑，你随时用浏览器打开面板看进度、选资产、执行更新。**关掉浏览器就是真的关掉界面**，daemon 内存不受影响。

## 核心功能

| 功能 | 说明 |
|---|---|
| 🔍 版本跟踪 | 按 release `id` 判断是否有新版，不做语义版本比较（作者可用 `make_latest` 指定旧 release，语义比较会误报） |
| 📦 四类资产 | `BINARY` 单文件替换 / `ARCHIVE` 压缩包解压 / `SOURCE` Git 源码切 tag / `COLLECTION` 只归档不应用（如 APK） |
| ⏸️ 断点续传 | 暂停保留 `.part`，续传用 `If-Range` + ETag 防拼坏；实测暂停于 16375 字节后续传，SHA-256 仍与官方一致 |
| ✅ 完整性校验 | SHA-256 是唯一成功判据。无 digest 时必须用户显式确认，绝不默认放行 |
| 🔄 备份与回滚 | 替换前自动备份，一键回滚。占用检测用 Restart Manager，被占用时返回 423 而不是硬写 |
| 🖥️ 托盘四态 | 空闲 / 下载中 / 完成 / 失败，颜色 + 形状双重编码，色盲也能分辨 |
| 📡 实时进度 | SSE 推送，10 个并发连接下普通 API 仍 5ms 返回 |
| 🚀 开机自启 | 写 HKCU Run 项，移动目录后路径自动自愈 |

## 快速开始

### 方式一：下载发布包（普通用户）

1. 下载并解压 `GithubUpdater-0.1.0-win-x64.zip`（约 60MB，含裁剪后的 JRE）
2. 双击 `GithubUpdater.exe`
3. 浏览器会自动打开面板；地址也写在 `data/runtime.json` 的 `url` 字段

**首次使用建议配置 GitHub Token**。匿名只有 60 次/小时配额，配了 PAT 是 5000 次/小时。在面板「设置」里填写。

> - 解压即用，**不需要安装 Java**
> - 关闭浏览器不会停止程序，它常驻托盘。要退出请右键托盘图标选 `Exit`
> - 托盘菜单只有 `Exit` 一项，**双击图标**打开面板（原因见下文「为什么托盘菜单是英文」）
> - 个别杀软可能对未签名的程序误报，添加信任即可

### 方式二：从源码运行（开发者）

**环境要求**：JDK 21、Maven 3.6.3+（本项目用 3.10.0）

```powershell
.\run.ps1
```

这个脚本会：检查 JDK/Maven 版本 → 编译 → 启动。缺什么会直接告诉你装哪个版本，而不是丢一堆看不懂的编译错误。

```powershell
.\run.ps1 -SkipBuild          # 跳过编译直接启动（改完代码想快速重启时用）
.\run.ps1 -Minimized          # 静默启动，不自动打开浏览器
```

> **PowerShell 执行策略拦住时**（首次运行常见）：
> ```powershell
> powershell -ExecutionPolicy Bypass -File .\run.ps1
> ```

也可以手动来：

```powershell
mvn -DskipTests package       # 编译
mvn test                      # 跑测试（138 个）
powershell build/run-dev.ps1  # 启动
```

## 技术栈

| 组件 | 选择 | 为什么 |
|---|---|---|
| 运行时 | JDK 21 (Temurin) | 记录类、switch 表达式、`java.net.http` |
| HTTP 服务 | Javalin 7（Jetty 12） | 轻量，单用户本地服务够用 |
| 数据库 | SQLite（sqlite-jdbc） | 零配置、单文件、便携 |
| HTTP 客户端 | `java.net.http.HttpClient` | 内置，无需额外依赖 |
| Git | JGit | 纯 Java，不用要求用户装 git（ADR-007） |
| 前端 | Preact + htm | **零构建步骤**，改完刷新即可，不需要 npm |
| 托盘 | AWT `SystemTray` + 原生 `PopupMenu` | 见 ADR-010 |
| 打包 | jlink + jpackage `app-image` | 完全绕开 WiX（ADR-006） |

## 设计要点

**后台 daemon + 浏览器前端**。daemon 是纯 Java 进程，只做四件事：抓 Release、下载、校验、替换文件。界面完全交给浏览器。

这是它和 Electron/Tauri/JavaFX 方案的根本区别：那类框架要么自带渲染进程，要么把界面嵌在同一个进程里，**关窗之后内存降不干净**。

**实测内存**：打包产物空载 **77.2MB**、跑完完整流程稳态 **119.6MB**（阈值 150MB）。

两次压内存都是靠"找对原生内存来源"而不是调堆：

- 阶段 5：Jetty 默认 200 线程池 → 收紧到 max 12，省 38.7MB
- 阶段 6：AWT 初始化连带加载 D3D 显卡驱动 DLL → 关掉，省 36.9MB

**不做后台轮询**（ADR-002）。世界只在用户点按钮时变化。关着面板不会发现新版——这是有意取舍，不是缺陷。

## 项目结构

```
GithubUpdater/
├── run.ps1                    ← 开发态一键启动
├── pom.xml
├── src/main/java/com/githubupdater/
│   ├── config/                ← 配置读写与迁移
│   ├── transport/             ← Javalin 路由、SSE、托盘
│   ├── app/                   ← Check/Download/Apply/Quota 四个服务
│   ├── domain/                ← 纯逻辑，无 IO，可直接单测
│   ├── infra/                 ← github / http / db / fs / git / win
│   └── util/
├── src/main/resources/public/ ← 前端（打进 jar，零构建）
├── src/test/java/             ← 138 个测试
├── build/
│   ├── jlink.ps1              ← 裁剪 JRE
│   ├── package.ps1            ← jpackage app-image
│   ├── run-dev.ps1            ← 开发期启动（run.ps1 用等价参数）
│   ├── verify-ui.ps1          ← CDP 端到端验证前端
│   └── README.txt             ← 随包分发给用户
└── build/                     ← 构建脚本（见下节）
```

## 构建与发布

```powershell
powershell build/jlink.ps1           # 裁剪 JRE → build/runtime（48.0 MB，14 模块）
powershell build/package.ps1         # jpackage → build/out/GithubUpdater/（76.2 MB）
powershell build/package.ps1 -Zip    # 追加生成发布 zip（59.5 MB）
```

**发布物是目录不是单文件 exe**。这是 JVM 形态限制：jlink 裁剪出的 runtime 必须是目录，jpackage 的 exe 只是引导程序。用户拿到 zip 解压即用。

> **不要尝试 `--type msi/exe`**：需要 WiX，而 JDK 21 只兼容 WiX 3，装了 WiX 4/5/6 会直接失败（JDK-8319457）。

## 开发说明

### 关键设计决策

代码里到处能看到一些"为什么不那样做"的注释，它们对应几条经过实测验证的决策：

| 决策 | 一句话理由 |
|---|---|
| 浏览器当界面，不做内置 GUI | 关掉浏览器就是真的释放界面内存，Electron/JavaFX 做不到 |
| 不做后台定时轮询 | 匿名配额只有 60 次/小时，扛不住；世界只在用户点按钮时变化 |
| 版本判定只看 release `id` | 作者可用 `make_latest` 指定旧 release，语义版本比较会误报 |
| 用 JGit 而非系统 git CLI | 不要求用户装 git，也不用捆绑 GPLv2 的 Git for Windows |
| 只做 `app-image`，不做安装器 | JDK 21 只兼容 WiX 3，装了 WiX 4/5/6 直接失败 |
| 托盘菜单是英文 `Exit` | 原生菜单无法正确渲染中文，改用 Swing 自绘的代价是三个线上 bug |

### 协作约定与踩坑

[AGENTS.md](AGENTS.md) 是本项目的开发约定与踩坑清单——里面记的都是**踩过才知道**的东西（比如为什么不能加 `-Xmn`、为什么 `sqlite-jdbc` 不能加 `natives-windows` classifier、为什么 SSE 试了五次才通）。

**动代码前建议先看一遍**，能省掉大量重复排查。

### 设计文档

完整的架构设计、数据库设计、API 接口、前后端设计、验收记录目前**暂未公开**——项目还在开发阶段，文档改动频繁。

这些文档在本地 `docs/` 目录下，等进入稳定期再决定是否开放。

## 常见问题

**Q：找不到面板地址？**
读 `data/runtime.json` 的 `url` 字段，或看启动日志里的 `服务已启动：http://127.0.0.1:<端口>`。端口由系统分配，每次不同。双击托盘图标也会打开。

**Q：下载很慢 / 卡住？**
本机网络对 `github.com` 主域可能超时（`api.github.com` 通常正常）。在设置里启用镜像，实测可用 `ghfast.top`、`gh-proxy.org`。镜像默认关闭，因为多一层信任链。

**Q：提示 `PKIX path building failed`？**
通常是中间人代理（加速器/杀软）在解密 HTTPS。关掉它即可，**不要**把第三方根证书导入 JDK 全局 cacerts——那会让整个 JVM 信任该中间人，你的 Token 也会被它看到。

**Q：关掉浏览器后还在跑，怎么退出？**
右键托盘图标选 `Exit`。这是设计如此：界面和 daemon 是两个独立的东西。

**Q：为什么托盘菜单是英文 `Exit`？**
中文菜单在 Windows 原生托盘菜单里会显示成方块。原因是 `java.awt.PopupMenu` 在 Windows 上不由 Java 渲染——它把菜单项交给 Win32 的 `TrackPopupMenu`，由 explorer.exe 用系统字体画，所以 `MenuItem.setFont()` 只是被 Java 对象记录下来，对实际渲染毫无影响。

改用 Swing 自绘菜单能让中文正常显示，但代价极高：无主窗口的 Java 进程拿不到焦点，菜单"点外面自动关闭"完全失效，只能靠每 80ms 轮询 `GetAsyncKeyState` 补上，而那套补丁又引出了三个新问题（进程退不掉、菜单不隐藏、退出项失效）。

**换成一个英文单词，问题就消失了**：`Exit` 是纯 ASCII，任何字体都能画；而原生菜单由系统托管，自动隐藏、Esc 关闭、点外部关闭全部免费。这是一次"降低需求优于提高复杂度"的取舍。

**Q：在 VSCode 里点 Run 报 `Unresolved compilation problems`？**
不是代码问题，是 Eclipse JDT 编译器把错误"编译进"了 class——它遇到编译错误时仍会生成 class，内容等价于 `throw new Error("Unresolved compilation problems: ...")`。

原因通常是 VSCode 把项目当成"隐式项目"（自己扫 `.java` 文件）而不是 Maven 项目，增量编译时找不到同项目里的类型，并把残缺 class 写进了 Maven 共用的 `target/classes`。

**修复**：命令面板执行 `Java: Clean Java Language Server Workspace` → Reload and delete，然后 `mvn clean compile`。详见 [AGENTS.md](AGENTS.md) 的「环境与构建」一节。

## 明确不做

| 不做 | 理由 |
|---|---|
| 后台定时检查 | 匿名配额扛不住，且违反 ADR-002 |
| 工具自更新 | 第一版最易出事的部分，对核心痛点零贡献 |
| 跨平台 | 只做 Windows |
| 内置构建 | `SOURCE` 类只更新到 tag，构建交给 `after_apply_cmd` 钩子 |
| APK 签名验证 | 需要用户装 Android SDK，默认关闭的可选项 |
| 安装器（MSI/EXE） | 需要 WiX，与便携模式冲突 |

## 许可

MIT
