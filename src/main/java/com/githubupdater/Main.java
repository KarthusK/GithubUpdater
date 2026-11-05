package com.githubupdater;

import com.githubupdater.app.ApplyService;
import com.githubupdater.app.CheckService;
import com.githubupdater.app.DownloadService;
import com.githubupdater.app.QuotaService;
import com.githubupdater.app.RuntimeInfo;
import com.githubupdater.config.AppConfig;
import com.githubupdater.config.ConfigStore;
import com.githubupdater.infra.db.SqliteStore;
import com.githubupdater.infra.github.GithubClient;
import com.githubupdater.infra.http.HttpFetcher;
import com.githubupdater.infra.http.RateLimiter;
import com.githubupdater.transport.TrayManager;
import com.githubupdater.transport.WebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 程序入口。
 *
 * <p>启动顺序见 docs/01-架构设计.md 第 6 节：
 * <ol>
 *   <li>定位 data/ 目录，<b>真实试写</b>检测可写性（不查 ACL）</li>
 *   <li>加载 config.json</li>
 *   <li>打开 SQLite，检查 schemaVersion，需要则迁移</li>
 *   <li>启动 HTTP 服务（端口由系统分配）</li>
 * </ol>
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /** 应用版本号。与 pom.xml 的 project.version 保持一致。 */
    public static final String APP_VERSION = "0.1.0";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = resolveDataDir();
        boolean minimized = hasFlag(args, com.githubupdater.infra.win.AutoStart.MINIMIZED_FLAG);

        log.info("GithubUpdater 启动，数据目录：{}，静默模式：{}", dataDir, minimized);

        selfCheck(dataDir);

        ConfigStore configStore = new ConfigStore(dataDir);
        AppConfig config = configStore.load();
        log.info("配置已加载：schemaVersion={} 已配置Token={} 镜像={}",
                config.schemaVersion, config.hasToken(), config.mirrorEnabled);

        SqliteStore store;
        try {
            store = SqliteStore.open(dataDir);
        } catch (Exception e) {
            fatal("数据库初始化失败：" + e.getMessage());
            System.exit(1);
            return;
        }

        if (!store.integrityCheck()) {
            fatal("数据库完整性检查失败，请从 state.db.bak.* 恢复后重试");
            store.close();
            System.exit(1);
            return;
        }

        // 自定义 CA（中间人代理环境）。加载失败只告警不阻断启动——
        // 证书配错属于用户配置问题，不该让整个程序起不来。
        Path customCaPath = config.resolveCustomCaFile(dataDir);
        com.githubupdater.infra.http.TlsTrust.Outcome tlsTrust =
                com.githubupdater.infra.http.TlsTrust.fromFile(customCaPath);
        if (tlsTrust.isFailure()) {
            log.warn("自定义 CA 配置无效：{}（将使用系统默认信任库）", tlsTrust.detail());
        }
        javax.net.ssl.SSLContext sslContext = tlsTrust.context();

        GithubClient github = new GithubClient(() -> config.githubToken, sslContext);
        QuotaService quota = new QuotaService(config);
        CheckService checkService = new CheckService(store, github, quota);

        // 下载器：全局限速器由配置驱动（0 表示不限速）
        RateLimiter rateLimiter = new RateLimiter(
                config.globalRateLimitKBps > 0 ? config.globalRateLimitKBps * 1024L : 0);
        HttpFetcher fetcher = new HttpFetcher(rateLimiter, sslContext);
        DownloadService downloadService = new DownloadService(store, fetcher, rateLimiter, config);
        ApplyService applyService = new ApplyService(store, config);

        // JGit 的用户配置必须指向数据目录：仓库与默认配置位置跨盘时，
        // 首次写文件系统属性会死锁（见 AGENTS.md）
        com.githubupdater.infra.git.GitRunner.configureUserConfig(dataDir);

        WebServer server = new WebServer(store, config, configStore, github, quota,
                checkService, downloadService, applyService, tlsTrust, dataDir, customCaPath);
        server.start();

        // 端口由系统分配，必须落盘——否则用户关掉日志窗口就再也找不到面板地址
        RuntimeInfo runtimeInfo = new RuntimeInfo(dataDir);
        runtimeInfo.write(server.port(), APP_VERSION, dataDir);

        // 托盘：install 内部会判断 SystemTray.isSupported()，不支持时只记日志不失败
        //
        // 退出路径要特别小心，这里踩过一个死锁：
        //   System.exit 在 AWT EDT 上调用 → JVM 启动 shutdown hook 并等待它完成
        //   → hook 里 tray.uninstall() 调 SystemTray.remove，那需要 EDT
        //   → EDT 正卡在 System.exit 里等 hook，双方互等，进程永远不退出。
        //   现象就是日志停在"正在关闭……"之后再无输出，进程也不消失。
        //
        // 解法：不注册 shutdown hook，退出时自己有序关闭，最后 halt() 强制终止。
        //
        // 用数组持有引用是为了让退出回调能拿到 TrayManager 实例本身——
        // 回调在构造时就要传进去，那时变量还没赋值，直接引用会编译失败。
        TrayManager[] trayRef = new TrayManager[1];

        TrayManager tray = new TrayManager(server.baseUrl(), () -> {
            log.info("用户从托盘退出，开始有序关闭");

            // 托盘移除必须先做，且它内部会切到 EDT 执行
            TrayManager self = trayRef[0];
            if (self != null) {
                self.uninstall();
            }

            // 关闭动作放到独立线程：当前线程是 EDT，在 EDT 上做阻塞关闭会让
            // AWT 无法响应，而且 exit/halt 的时机也难控制。
            Thread t = new Thread(() -> {
                try {
                    runtimeInfo.delete();
                    server.stop();
                    store.close();
                    github.close();
                    fetcher.close();
                } catch (Exception e) {
                    log.warn("关闭过程中出错：{}", e.getMessage());
                }
                log.info("已退出");
                // halt 而非 exit：跳过 shutdown hook，立即终止。
                // 否则 Jetty / AWT / SSE 异步池里任何残留的非守护线程
                // 都会让 JVM 迟迟不退出。
                Runtime.getRuntime().halt(0);
            }, "shutdown");
            t.setDaemon(true);
            t.start();
        });
        trayRef[0] = tray;

        // 下载状态驱动托盘图标（空闲 / 下载中 / 完成 / 失败）。
        //
        // 这是与 SSE 并列的第二个进度监听器——两者互不影响：
        // SSE 推给浏览器，托盘只换图标。监听器内部已兜异常，
        // 托盘出问题不会影响下载。
        downloadService.addProgressListener(event -> {
            boolean isProgress = event.isProgress();
            boolean isDone = event.state() == com.githubupdater.domain.TaskState.DONE;
            boolean isFailed = event.state() == com.githubupdater.domain.TaskState.FAILED;
            if (isProgress || isDone || isFailed) {
                tray.onDownloadEvent(isProgress, isDone, isFailed);
            } else if (event.state() != null) {
                // 暂停 / 取消 / 排队等状态：回到空闲
                tray.onIdle();
            }
        });

        // -Dgu.notray=true 可完全跳过托盘初始化。
        //
        // 两个用途：
        //   1. 无 GUI 的部署环境（Windows Server Core）里 AWT 初始化会失败或很慢
        //   2. 内存诊断时用来量化 AWT 子系统的成本
        boolean trayOk = !Boolean.getBoolean("gu.notray") && tray.install();

        log.info("服务已启动：{}", server.baseUrl());
        if (trayOk) {
            log.info("已最小化到托盘，双击图标打开面板，右键可退出");
        } else {
            log.info("托盘不可用，请手动访问上面的地址（也记录在 data/runtime.json）");
        }

        // 开机自启的路径自愈。
        //
        // 便携模式下用户可能移动整个目录，注册表里的旧绝对路径就失效了，
        // 自启会静默失败——用户开机发现工具没启动却不知道原因。
        // 每次启动都比对并修正，用户无需干预。
        com.githubupdater.infra.win.AutoStart.selfHeal();

        // 非静默模式（用户双击启动）时自动打开浏览器。
        // 静默模式（开机自启，带 --minimized）不弹浏览器——
        // 开机时突然弹出浏览器是骚扰，用户想要时自己点托盘。
        if (!minimized && trayOk) {
            tray.openPanel();
        }

        // 不注册 shutdown hook。
        //
        // 托盘退出走上面的有序关闭 + halt()；Ctrl+C / 关控制台窗口由 JVM 直接终止。
        // 注册 hook 反而有害：它会和托盘退出路径重复关闭同一个 WebServer 与
        // SQLite 连接，而且在 EDT 上 System.exit 时 hook 与 SystemTray.remove
        // 会互相等待（见上面的注释）。
        //
        // 数据安全上没有问题：SQLite 用 WAL + synchronous=NORMAL，
        // 每个写操作提交即落盘，进程被强杀不会丢已提交的数据。

        Thread.currentThread().join();
    }

    /**
     * 判断命令行里是否带某个标志。
     *
     * @param args 命令行参数
     * @param flag 要检查的标志，如 {@code --minimized}
     */
    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String a : args) {
            // 允许 --flag=value 形式，取等号前的部分比较
            String key = a == null ? "" : a.split("=", 2)[0];
            if (flag.equalsIgnoreCase(key.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 定位数据目录：便携模式，全部数据放 exe 同级 {@code data/}。
     *
     * <p>jpackage 会设置 {@code jpackage.app-path} 指向 exe；直接 {@code java -jar}
     * 时用工作目录下的 {@code data/}。
     */
    private static Path resolveDataDir() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path exeDir = Path.of(appPath).toAbsolutePath().getParent();
            return exeDir.resolve("data");
        }
        return Path.of("data").toAbsolutePath();
    }

    /**
     * 启动自检。
     *
     * <p>目录可写性必须用<b>真实试写</b>判断，不能查 ACL：ACL 分析要展开组继承、
     * 处理 DENY ACE，任一环节出错就误判；试写还能顺带识别"实际写进了
     * VirtualStore 重定向"这个坑。
     */
    private static void selfCheck(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            Path probe = dataDir.resolve(".write-probe-" + System.nanoTime());
            Files.writeString(probe, "ok");
            Files.delete(probe);
        } catch (Exception e) {
            fatal("数据目录不可写：" + dataDir + "\n" + e.getMessage()
                    + "\n请检查目录权限，或把程序移到可写位置（如用户目录下）。");
            System.exit(1);
        }

        // 原生库（JNA 的 jnidispatch.dll、SQLite 的 sqlitejdbc.dll）会解压到
        // 临时目录再 LoadLibrary。系统 TEMP 在部分环境下不可用——实测打包产物里
        // 系统 TEMP 是 8.3 短名路径（C:\Users\ADMINI~1\...），JNA 加载 DLL 时
        // 报"拒绝访问"，而同一个目录在 PowerShell 里明明可写。
        //
        // 统一放到 data/tmp 既避开短名路径问题，也让原生库集中在一处便于排查。
        //
        // 注意：java.io.tmpdir 在 JVM 启动时就被 File.createTempFile 等处缓存，
        // 运行时改系统属性对已初始化的组件无效。所以这里只是兜底——
        // 真正生效的是 package.ps1 里传的 -Djava.io.tmpdir（启动时生效）。
        // org.sqlite.tmpdir 是 sqlite-jdbc 每次现读的，运行时设置有效。
        Path tmp = dataDir.resolve("tmp");
        try {
            Files.createDirectories(tmp);
            System.setProperty("org.sqlite.tmpdir", tmp.toString());
            if (System.getProperty("java.io.tmpdir") == null
                    || !Files.isWritable(Path.of(System.getProperty("java.io.tmpdir")))) {
                System.setProperty("java.io.tmpdir", tmp.toString());
                log.warn("系统临时目录不可写，已切换到 {}", tmp);
            }
        } catch (Exception e) {
            log.warn("无法创建 data/tmp，原生库将使用系统临时目录：{}", e.getMessage());
        }

        // 清理上一次遗留的原生库解压产物。
        //
        // sqlite-jdbc 每次启动都会把 jar 里的 sqlitejdbc.dll 解压成一个
        // **带随机 UUID 的新文件名**（形如 sqlite-3.54.1.0-<uuid>-sqlitejdbc.dll），
        // 而且用完不删。于是每启动一次就多 1MB，实测开发期堆到了 46.7MB / 96 个文件。
        //
        // 为什么不在退出时删：JVM 进程退出时 DLL 仍被映射，Windows 会拒绝删除
        // （正在使用的文件无法删除）。所以只能在**下次启动时**清上一轮的残留。
        //
        // 安全性：只删「本次启动之前」就已存在的文件，绝不碰本次刚解压的。
        // 通过文件名前缀 + 修改时间双重判断，避免误删别的程序放在这里的文件。
        cleanupStaleNativeLibs(tmp);

        try {
            long freeBytes = Files.getFileStore(dataDir).getUsableSpace();
            if (freeBytes < 1024L * 1024 * 1024) {
                log.warn("磁盘剩余空间不足 1GB（{} MB），大文件下载可能失败",
                        freeBytes / (1024 * 1024));
            }
        } catch (Exception e) {
            log.debug("读取磁盘剩余空间失败：{}", e.getMessage());
        }
    }

    /**
     * 清理上一次运行遗留的原生库解压产物。
     *
     * <p><b>为什么需要这个</b>：{@code sqlite-jdbc} 每次启动都把 jar 内的
     * {@code sqlitejdbc.dll} 解压成<b>带随机 UUID 的新文件名</b>，且用完不删。
     * 每启动一次就多约 1MB，实测开发期堆积到 46.7MB / 96 个文件。
     *
     * <p><b>为什么只能在启动时清</b>：进程退出时 DLL 仍被映射，Windows 拒绝删除
     * 正在使用的文件。所以清的是"上一轮的残留"，不是本次的。
     *
     * <p><b>安全性</b>：
     * <ul>
     *   <li>只处理 {@code sqlite-} 开头、{@code -sqlitejdbc.dll} 结尾的文件，
     *       以及同名 {@code .lck} 锁文件</li>
     *   <li>只删「本次启动之前」就已存在的——本次刚解压的必然更新，
     *       用启动时刻做分界，不会误删自己</li>
     *   <li>删除失败只记 debug 日志，不影响启动（可能有别的实例正在用）</li>
     * </ul>
     *
     * @param tmp 临时目录（{@code data/tmp}）
     */
    private static void cleanupStaleNativeLibs(Path tmp) {
        if (!Files.isDirectory(tmp)) {
            return;
        }
        // 分界点：启动时刻。早于它的才是"上一轮残留"。
        // 留 5 秒余量，避免时钟精度或文件系统时间戳粒度导致误判。
        long cutoff = System.currentTimeMillis() - 5_000;

        int removed = 0;
        long freedBytes = 0;
        try (var stream = Files.list(tmp)) {
            for (Path f : (Iterable<Path>) stream::iterator) {
                String name = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                boolean isNativeLib = name.startsWith("sqlite-")
                        && (name.endsWith("-sqlitejdbc.dll") || name.endsWith("-sqlitejdbc.dll.lck"));
                if (!isNativeLib) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(f).toMillis() > cutoff) {
                        continue;   // 本次启动产生的，不能删
                    }
                    long size = Files.size(f);
                    if (Files.deleteIfExists(f)) {
                        removed++;
                        freedBytes += size;
                    }
                } catch (Exception e) {
                    // 被别的实例占用，或权限不足——不影响启动
                    log.debug("清理 {} 失败：{}", f.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("遍历临时目录失败：{}", e.getMessage());
        }

        if (removed > 0) {
            log.info("已清理 {} 个残留原生库文件，释放 {} MB",
                    removed, freedBytes / 1024 / 1024);
        }
    }

    private static void fatal(String message) {
        log.error(message);
        System.err.println(message);
    }
}
