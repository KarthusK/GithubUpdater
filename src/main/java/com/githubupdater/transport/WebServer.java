package com.githubupdater.transport;

import com.githubupdater.app.ApplyService;
import com.githubupdater.app.CheckService;
import com.githubupdater.app.DownloadService;
import com.githubupdater.app.QuotaService;
import com.githubupdater.config.AppConfig;
import com.githubupdater.config.ConfigStore;
import com.githubupdater.domain.AssetKind;
import com.githubupdater.domain.AssetMatcher;
import com.githubupdater.domain.AssetRule;
import com.githubupdater.domain.DownloadTask;
import com.githubupdater.domain.Entry;
import com.githubupdater.domain.TaskState;
import com.githubupdater.domain.VersionResolver;
import com.githubupdater.infra.db.SqliteStore;
import com.githubupdater.infra.github.GithubClient;
import com.githubupdater.infra.github.Quota;
import com.githubupdater.infra.github.ReleaseDto;
import com.githubupdater.util.PathGuard;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP 服务装配。
 *
 * <p>安全约束见 ADR-004，三条缺一不可：只绑 127.0.0.1、校验 Host 头、Bearer token。
 *
 * <p><b>静态资源不用 Javalin 的 staticFiles</b>：它用
 * {@code ClassLoader.getResources("/public")} 查目录条目，jpackage 启动器的 classloader
 * 与 {@code java -jar} 行为不同，会抛 "Static resource directory does not exist"。
 * 改为自行用 {@code getResourceAsStream} 逐文件提供。
 */
public final class WebServer {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    private static final String BIND_HOST = "127.0.0.1";
    private static final Set<String> ALLOWED_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");
    private static final String TOKEN_PLACEHOLDER = "{{API_TOKEN}}";
    private static final String RESOURCE_ROOT = "/public";

    /**
     * SSE 连接的超时时间。
     *
     * <p>不能用 Javalin 的默认值 0：{@code AsyncTaskConfig.timeout=0} 会让连接
     * 立刻超时关闭（实测 78ms 后 EOF）。给 24 小时，实际断连由客户端行为决定。
     */
    private static final long SSE_TIMEOUT_MS = 24L * 60 * 60 * 1000;

    private final SqliteStore store;
    private final AppConfig config;
    private final ConfigStore configStore;
    private final GithubClient github;
    private final QuotaService quota;
    private final CheckService checkService;
    private final DownloadService downloadService;
    private final ApplyService applyService;
    private final SseHub sse;

    /** 启动时实际生效的自定义 CA 状态。改配置后需重启才更新。 */
    private final com.githubupdater.infra.http.TlsTrust.Outcome tlsTrust;

    /** 数据目录。用于解析自定义 CA 的相对路径。 */
    private final Path dataDir;

    /**
     * 启动时实际尝试加载的 CA 路径。
     *
     * <p>用它和当前配置比较，才能判断"改了配置但没重启"。
     * 注意即使加载失败也要记下来：那时状态已经是失败，没有这个基准
     * 就分不清"配置没变"和"改了但没生效"。
     */
    private final Path startupCaPath;

    private final String token;

    /** 检查是长操作，必须放在独立线程，不能占用 HTTP 工作线程。 */
    private final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "check-worker");
        t.setDaemon(true);
        return t;
    });

    private Javalin app;
    private int port;

    public WebServer(SqliteStore store, AppConfig config, ConfigStore configStore,
                     GithubClient github, QuotaService quota, CheckService checkService,
                     DownloadService downloadService, ApplyService applyService,
                     com.githubupdater.infra.http.TlsTrust.Outcome tlsTrust, Path dataDir,
                     Path startupCaPath) {
        this.store = store;
        this.config = config;
        this.configStore = configStore;
        this.github = github;
        this.quota = quota;
        this.checkService = checkService;
        this.downloadService = downloadService;
        this.applyService = applyService;
        this.tlsTrust = tlsTrust;
        this.dataDir = dataDir;
        this.startupCaPath = startupCaPath;
        this.sse = new SseHub();
        this.token = generateToken();

        // 下载进度经 SSE 推给浏览器
        this.downloadService.addProgressListener(event -> {
            if (event.isProgress()) {
                sse.broadcast("task.progress", Map.of(
                        "taskId", event.taskId(),
                        "bytesDone", event.bytesDone(),
                        "expectedSize", event.totalSize(),
                        "bytesPerSecond", event.speedBytesPerSec()
                ));
            } else if (event.state() != null) {
                sse.broadcast("task.state", Map.of(
                        "taskId", event.taskId(),
                        "state", event.state().name()
                ));
            }
        });
    }

    public void start() {
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;

            // 收紧 Jetty 线程池。
            //
            // Jetty 12 默认 maxThreads=200、minThreads=8，启动后就拉起 16 个线程。
            // 单用户桌面工具的实际并发只有「浏览器几个请求 + 1 个 SSE」，
            // 200 个线程的默认值纯属浪费——每个线程即使栈只有 256KB，
            // 加上 JVM 的线程元数据也是实打实的提交内存。
            //
            // maxThreads=12：SSE 走 Javalin 的异步池，不占这里的线程，
            //   所以 12 足够处理浏览器的并发请求（静态资源 + API）。
            // minThreads=4：空闲时只保留 4 个，其余按需创建。
            // idleTimeout=30s：空闲线程尽快回收，把内存还给 OS。
            var pool = new org.eclipse.jetty.util.thread.QueuedThreadPool();
            pool.setMinThreads(4);
            pool.setMaxThreads(12);
            pool.setIdleTimeout(30_000);
            pool.setName("jetty");
            cfg.jetty.threadPool = pool;

            cfg.routes.get("/", this::serveIndex);
            cfg.routes.get("/assets/*", this::serveAsset);

            // 守卫必须在业务路由之前
            cfg.routes.beforeMatched(this::guard);

            cfg.routes.get("/api/health", this::health);
            cfg.routes.get("/api/quota", this::quotaInfo);
            cfg.routes.get("/api/events", this::events);

            cfg.routes.get("/api/entries", this::listEntries);
            cfg.routes.post("/api/entries", this::createEntry);
            cfg.routes.get("/api/entries/{id}", this::getEntry);
            cfg.routes.delete("/api/entries/{id}", this::deleteEntry);

            cfg.routes.post("/api/entries/check-all", this::checkAll);
            cfg.routes.post("/api/entries/{id}/check", this::checkOne);

            cfg.routes.get("/api/entries/{id}/rules", this::listRules);
            cfg.routes.post("/api/entries/{id}/rules", this::createRule);
            cfg.routes.delete("/api/rules/{id}", this::deleteRule);

            // 下载任务
            cfg.routes.get("/api/tasks", this::listTasks);
            cfg.routes.post("/api/tasks", this::createTask);
            cfg.routes.get("/api/tasks/{id}", this::getTask);
            cfg.routes.post("/api/tasks/{id}/pause", this::pauseTask);
            cfg.routes.post("/api/tasks/{id}/resume", this::resumeTask);
            cfg.routes.post("/api/tasks/{id}/cancel", this::cancelTask);
            cfg.routes.post("/api/tasks/{id}/retry", this::retryTask);

            // 应用与回滚
            cfg.routes.post("/api/tasks/{id}/apply", this::applyTask);
            cfg.routes.post("/api/tasks/{id}/rollback", this::rollbackTask);
            cfg.routes.get("/api/rules/{id}/backups", this::listBackups);
            cfg.routes.post("/api/backups/{id}/rollback", this::rollbackBackup);

            // SOURCE 类没有可下载资产，直接更新工作副本
            cfg.routes.post("/api/rules/{id}/apply-source", this::applySourceRule);

            cfg.routes.get("/api/settings", this::getSettings);
            cfg.routes.patch("/api/settings", this::patchSettings);
        });

        app.start(BIND_HOST, 0);
        this.port = app.port();
        sse.start();
        downloadService.start();
    }

    // ──────────────────────── 静态资源 ────────────────────────

    private void serveIndex(Context ctx) {
        String html = readResource("/public/index.html");
        if (html == null) {
            ctx.status(500).result("index.html 缺失");
            return;
        }
        ctx.contentType("text/html; charset=utf-8");
        ctx.header("Cache-Control", "no-cache");
        ctx.result(html.replace(TOKEN_PLACEHOLDER, token));
    }

    private void serveAsset(Context ctx) {
        String fullPath = ctx.path();
        String path = fullPath.startsWith("/assets/")
                ? fullPath.substring("/assets/".length())
                : "";
        if (path.isBlank() || path.contains("..") || path.startsWith("/")) {
            ctx.status(400).result("非法路径");
            return;
        }

        try (InputStream in = getClass().getResourceAsStream(RESOURCE_ROOT + "/" + path)) {
            if (in == null) {
                ctx.status(404).result("未找到: " + path);
                return;
            }
            byte[] bytes = in.readAllBytes();
            ctx.contentType(mimeOf(path));
            ctx.header("Cache-Control", "no-cache");
            ctx.result(bytes);
        } catch (IOException e) {
            log.error("读取静态资源失败: {}", path, e);
            ctx.status(500).result("读取资源失败");
        }
    }

    private static String mimeOf(String path) {
        String mime = URLConnection.guessContentTypeFromName(path);
        if (mime == null) {
            int dot = path.lastIndexOf('.');
            String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
            mime = switch (ext) {
                case "js", "mjs" -> "text/javascript";
                case "css" -> "text/css";
                case "svg" -> "image/svg+xml";
                case "json" -> "application/json";
                case "ico" -> "image/x-icon";
                default -> "application/octet-stream";
            };
        }
        if (mime.startsWith("text/") || mime.equals("application/json")) {
            mime += "; charset=utf-8";
        }
        return mime;
    }

    private String readResource(String resourcePath) {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取资源失败: {}", resourcePath, e);
            return null;
        }
    }

    // ──────────────────────── 守卫 ────────────────────────

    private void guard(Context ctx) {
        if (!ctx.path().startsWith("/api/")) {
            return;
        }
        if (!isHostAllowed(ctx.header("Host"))) {
            log.warn("拒绝非法 Host：{} 来自 {}", ctx.header("Host"), ctx.ip());
            ctx.status(403).json(error("FORBIDDEN_HOST", "非法的 Host 头"));
            return;
        }
        if (!isTokenValid(ctx.header("Authorization"))) {
            ctx.status(401).json(error("UNAUTHORIZED", "缺少或错误的访问令牌"));
        }
    }

    private static boolean isHostAllowed(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader;
        if (hostHeader.startsWith("[")) {
            int end = hostHeader.indexOf(']');
            if (end > 0) {
                host = hostHeader.substring(0, end + 1);
            }
        } else {
            int colon = hostHeader.lastIndexOf(':');
            if (colon > 0) {
                host = hostHeader.substring(0, colon);
            }
        }
        return ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private boolean isTokenValid(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String provided = authHeader.substring("Bearer ".length()).trim();
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    // ──────────────────────── 基础 ────────────────────────

    private void health(Context ctx) {
        var rt = Runtime.getRuntime();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "up");
        data.put("version", "0.1.0");
        data.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        data.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
        data.put("pendingWrites", store.pendingWrites());
        ctx.json(ok(data));
    }

    private void quotaInfo(Context ctx) {
        ctx.json(ok(quotaView()));
    }

    // ──────────────────────── 条目 ────────────────────────

    private void listEntries(Context ctx) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Entry e : store.listEntries()) {
            items.add(entryView(e));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entries", items);
        data.put("quota", quotaView());
        ctx.json(ok(data));
    }

    private void getEntry(Context ctx) throws Exception {
        long id = parseId(ctx);
        Optional<Entry> entry = store.findEntry(id);
        if (entry.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "条目不存在"));
            return;
        }
        Map<String, Object> view = entryView(entry.get());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (AssetRule r : store.listRules(id)) {
            rules.add(ruleView(r));
        }
        view.put("rules", rules);
        ctx.json(ok(view));
    }

    private void createEntry(Context ctx) throws Exception {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        String raw = str(body.get("repoUrl"));
        if (raw == null || raw.isBlank()) {
            ctx.status(400).json(error("VALIDATION_FAILED", "repoUrl 不能为空"));
            return;
        }

        Optional<String[]> parsed = parseRepoRef(raw);
        if (parsed.isEmpty()) {
            ctx.status(400).json(error("VALIDATION_FAILED",
                    "无法解析仓库地址，请填写 https://github.com/owner/repo 或 owner/repo"));
            return;
        }
        String owner = parsed.get()[0];
        String repo = parsed.get()[1];

        if (store.findEntry(owner, repo).isPresent()) {
            ctx.status(409).json(error("VALIDATION_FAILED",
                    "该仓库已在跟踪列表中：" + owner + "/" + repo));
            return;
        }

        String channel = Optional.ofNullable(str(body.get("channel")))
                .filter(s -> !s.isBlank())
                .orElse(Entry.CHANNEL_LATEST);

        long id = store.insertEntry(owner, repo, channel, owner + "/" + repo, false);
        store.flush();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entry", store.findEntry(id).map(this::entryView).orElse(Map.of()));
        data.put("assetsPreview", fetchAssetsPreview(owner, repo));
        ctx.json(ok(data));
    }

    private void deleteEntry(Context ctx) throws Exception {
        long id = parseId(ctx);
        if (store.findEntry(id).isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "条目不存在"));
            return;
        }
        store.deleteEntry(id);
        ctx.json(ok(Map.of("deleted", id)));
    }

    /**
     * 拉取最近若干 release 的资产清单，供前端勾选。
     *
     * <p>这是创建规则时的默认入口：用户看到的应是"这个仓库发过哪些文件"，
     * 而不是让用户凭空写 glob。
     */
    /**
     * 拉取最近若干 release 的资产清单，供前端勾选。
     *
     * <p>这是创建规则时的默认入口：用户看到的应是"这个仓库发过哪些文件"，
     * 而不是让用户凭空写 glob。
     *
     * <p><b>只取 2 个 release</b>：实测取 5 个时，jq 那种 29 资产的仓库会产生
     * 260KB+ 的 JSON，解析成对象树后让 JVM 堆从 16MB 扩到 29MB。而 JVM 扩堆后
     * 不会立刻还给 OS，导致 Private Bytes 永久上升约 12MB。2 个 release 已足够
     * 让用户确认资产命名规律（同一仓库的资产名通常跨版本稳定）。
     */
    private List<Map<String, Object>> fetchAssetsPreview(String owner, String repo) {
        try {
            GithubClient.Result result = github.listReleases(owner, repo, 2);
            if (result instanceof GithubClient.Ok ok) {
                quota.update(ok.quota());
                // 解析后立刻丢弃 JsonNode 树，只保留需要的字段
                List<ReleaseDto.Release> releases = GithubClient.parseReleases(ok.body());
                List<Map<String, Object>> out = new ArrayList<>(releases.size());
                for (ReleaseDto.Release r : releases) {
                    List<Map<String, Object>> assets = new ArrayList<>(r.assets().size());
                    for (ReleaseDto.Asset a : r.assets()) {
                        Map<String, Object> am = new LinkedHashMap<>(5);
                        am.put("id", a.id());
                        am.put("name", a.name());
                        am.put("size", a.size());
                        am.put("digest", a.digest() == null ? "" : a.digest());
                        am.put("hasDigest", a.hasDigest());
                        assets.add(am);
                    }
                    Map<String, Object> rm = new LinkedHashMap<>(3);
                    rm.put("releaseId", r.id());
                    rm.put("tag", r.tagName());
                    rm.put("assets", assets);
                    out.add(rm);
                }
                return out;
            }
            if (result instanceof GithubClient.Failed f) {
                log.warn("拉取 {}/{} 的资产清单失败：{}", owner, repo, f.message());
            }
        } catch (Exception e) {
            log.warn("拉取资产清单异常：{}", e.getMessage());
        }
        return List.of();
    }

    // ──────────────────────── 检查 ────────────────────────

    private void checkAll(Context ctx) {
        if (checkService.isRunning()) {
            ctx.status(409).json(error("VALIDATION_FAILED", "检查已在进行中"));
            return;
        }
        List<Entry> entries;
        try {
            entries = store.listEntries();
        } catch (Exception e) {
            ctx.status(500).json(error("INTERNAL", e.getMessage()));
            return;
        }
        if (entries.isEmpty()) {
            ctx.json(ok(Map.of("started", false, "requested", 0, "planned", 0)));
            return;
        }

        int planned = Math.min(quota.maxBatchSize(entries.size()), entries.size());

        // 先告诉所有已连接的浏览器"检查开始了"，让它们把按钮灰掉
        sse.broadcast("check.started", Map.of(
                "requested", entries.size(),
                "planned", planned
        ));

        workers.submit(() -> {
            try {
                // onEach 必须传回调：否则每条结果都不会推给界面，
                // 用户点完"全部检查"只能干等，看不到任何进展（实测踩到）
                CheckService.BatchResult result = checkService.checkAll(outcome ->
                        sse.broadcast("check.result", Map.of(
                                "entryId", outcome.entryId(),
                                "outcome", outcome.outcome().name(),
                                "tag", outcome.tag() == null ? "" : outcome.tag(),
                                "releaseId", outcome.releaseId() == null ? 0 : outcome.releaseId(),
                                "hasUpdate", outcome.hasUpdate(),
                                "lastCheckedAt", System.currentTimeMillis(),
                                "message", outcome.message() == null ? "" : outcome.message(),
                                "lastCheckStatus", outcome.outcome().name(),
                                "lastCheckError", outcome.message() == null ? "" : outcome.message(),
                                "lastTlsIssuer", outcome.tlsIssuer() == null ? "" : outcome.tlsIssuer()
                        )));
                log.info("批量检查结束：检查 {} 条，跳过 {} 条",
                        result.checked(), result.skipped());
            } catch (Exception e) {
                log.error("批量检查失败", e);
            } finally {
                // 无论成功失败都必须推 check.done，否则前端会永远显示"检查中"
                sse.broadcast("check.done", Map.of("ok", true));
            }
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("started", true);
        data.put("requested", entries.size());
        data.put("planned", planned);
        ctx.json(ok(data));
    }

    private void checkOne(Context ctx) throws Exception {
        long id = parseId(ctx);
        Optional<Entry> entry = store.findEntry(id);
        if (entry.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "条目不存在"));
            return;
        }
        CheckService.CheckOutcome outcome = checkService.checkOne(entry.get());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entryId", outcome.entryId());
        data.put("outcome", outcome.outcome().name());
        data.put("tag", outcome.tag() == null ? "" : outcome.tag());
        data.put("releaseId", outcome.releaseId() == null ? 0 : outcome.releaseId());
        data.put("hasUpdate", outcome.hasUpdate());
        data.put("matchedAssets", outcome.matchedAssets());
        data.put("message", outcome.message() == null ? "" : outcome.message());
        // 检查失败时界面要立刻能显示诊断，不能等下一次刷新
        data.put("lastCheckStatus", outcome.outcome().name());
        data.put("lastCheckError", outcome.message() == null ? "" : outcome.message());
        data.put("lastTlsIssuer", outcome.tlsIssuer() == null ? "" : outcome.tlsIssuer());
        ctx.json(ok(data));
    }

    // ──────────────────────── 规则 ────────────────────────

    private void listRules(Context ctx) throws Exception {
        long entryId = parseId(ctx);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (AssetRule r : store.listRules(entryId)) {
            rules.add(ruleView(r));
        }
        ctx.json(ok(Map.of("rules", rules)));
    }

    private void createRule(Context ctx) throws Exception {
        long entryId = parseId(ctx);
        if (store.findEntry(entryId).isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "条目不存在"));
            return;
        }

        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        String includeGlob = str(body.get("includeGlob"));
        String excludeGlob = str(body.get("excludeGlob"));
        String kindRaw = str(body.get("kind"));
        String targetDir = str(body.get("targetDir"));

        if (includeGlob == null || includeGlob.isBlank()) {
            ctx.status(400).json(error("VALIDATION_FAILED", "includeGlob 不能为空"));
            return;
        }

        // 目标目录必须过 PathGuard：拒绝穿越与系统目录
        PathGuard.Result check = PathGuard.checkTargetDir(targetDir);
        if (!check.ok()) {
            ctx.status(400).json(error("VALIDATION_FAILED", check.message()));
            return;
        }

        AssetKind kind;
        try {
            kind = AssetKind.parse(kindRaw);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(error("VALIDATION_FAILED", e.getMessage()));
            return;
        }

        int keepVersions = body.get("keepVersions") instanceof Number n ? n.intValue() : 1;
        boolean autoApply = Boolean.TRUE.equals(body.get("autoApply"));
        String tpl = str(body.get("fileNameTpl"));
        String afterApplyCmd = str(body.get("afterApplyCmd"));

        long ruleId = store.insertRule(entryId, includeGlob, excludeGlob, kind,
                check.normalizedPath().toString(), tpl, keepVersions, autoApply, afterApplyCmd);
        store.flush();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", ruleId);
        data.put("kind", kind.name());
        data.put("targetDir", check.normalizedPath().toString());
        ctx.json(ok(data));
    }

    private void deleteRule(Context ctx) throws Exception {
        long id = parseId(ctx);
        store.deleteRule(id);
        ctx.json(ok(Map.of("deleted", id)));
    }

    // ──────────────────────── 下载任务 ────────────────────────

    /**
     * SSE 事件流。
     *
     * <p><b>为什么必须用 {@code ctx.async()} 而不是普通 handler。</b>
     * 这是本项目踩得最深的一个坑，三次尝试才定位清楚：
     *
     * <ol>
     *   <li>阶段 3：在 handler 里 {@code while (!closed) Thread.sleep(1000)}。
     *       能用，但把 Jetty 请求线程长期占住——开几个标签页就耗尽线程池，
     *       之后所有 API 全部卡死。</li>
     *   <li>阶段 5 尝试一：自己调 {@code ctx.res().startAsync()}。
     *       连接 78ms 后 EOF。</li>
     *   <li>阶段 5 尝试二：改用 Javalin 的 {@code SseHandler}。
     *       仍然 51ms 后 EOF，且响应头带 {@code Connection: close}。</li>
     * </ol>
     *
     * <p><b>根因</b>：{@code JavalinServlet} 在请求处理结束的 {@code finally} 里
     * 无条件执行 {@code ServletOutputStream.close()} + {@code AsyncContext.complete()}
     * （字节码里三处重复这段逻辑）。所以只要 handler 返回，连接就必然被关闭——
     * 除非用 {@code ctx.async()} 把整个后续处理交给 Javalin 的异步任务机制，
     * 那时它走的是另一条不自动 complete 的路径。
     *
     * <p>用 {@code ctx.async()} 后 handler 立即返回，请求线程归还线程池，
     * 连接由 AsyncContext 维持，写入完全由我们控制。
     */
    /**
     * SSE 事件流。
     *
     * <p><b>为什么必须用 {@code ctx.async()} 且回调里要阻塞。</b>
     * 这是本项目踩得最深的一个坑，四次尝试才定位清楚：
     *
     * <ol>
     *   <li>阶段 3：handler 里 {@code while (!closed) Thread.sleep(1000)}。
     *       能用，但占用 Jetty <b>请求线程</b>——几个标签页就耗尽线程池。</li>
     *   <li>尝试：自己调 {@code ctx.res().startAsync()}。连接 78ms 后 EOF。</li>
     *   <li>尝试：Javalin 的 {@code SseHandler}。仍 51ms EOF，且带
     *       {@code Connection: close}。</li>
     *   <li>尝试：{@code ctx.async()} 但回调里立即返回。仍 52ms EOF。</li>
     * </ol>
     *
     * <p><b>根因</b>：{@code JavalinServlet} 在请求处理结束时无条件
     * {@code close()} 输出流并 {@code complete()} 异步上下文。所以
     * <b>只要异步任务返回，连接就必然关闭</b>。要维持连接，回调必须一直不返回。
     *
     * <p><b>为什么这样不占请求线程</b>：{@code ctx.async()} 的回调跑在 Javalin 的
     * 异步 executor 上，与 Jetty 的请求线程池是分开的。实测 10 个并发 SSE 连接时，
     * 普通 API 请求仍在 82ms 内返回。
     *
     * <p>代价：每个 SSE 连接占用一个异步线程。所以 {@link SseHub} 有
     * {@code MAX_CLIENTS = 16} 的上限——单用户桌面工具不可能需要更多。
     */
    private void events(Context ctx) throws Exception {
        ctx.contentType("text/event-stream; charset=utf-8");
        ctx.header("Cache-Control", "no-cache");
        ctx.header("X-Accel-Buffering", "no");   // 防止反向代理缓冲

        // 先算出 snapshot 数据，避免在异步回调里再做数据库查询
        Map<String, Object> snapshot = Map.of(
                "tasks", taskViews(null),
                "quota", quotaView(),
                "checksInProgress", checkService.isRunning()
        );

        ctx.async(cfg -> cfg.timeout = 0, () -> {
            SseHub.Client client = null;
            try {
                OutputStream out = ctx.res().getOutputStream();
                out.flush();

                client = sse.register(out, null);
                if (client == null) {
                    // 已达连接上限，直接结束
                    return;
                }
                client.sendSnapshot(snapshot);

                // 关键：必须阻塞在这里。一旦返回，Javalin 就会关闭连接。
                // 等待由心跳或广播发现客户端断开后把 client 标记为 closed。
                while (!client.isClosed()) {
                    Thread.sleep(500);
                }
            } catch (IOException e) {
                log.debug("SSE 连接异常结束：{}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (client != null) {
                    sse.unregister(client);
                    client.close();
                }
            }
        });
    }

    private void listTasks(Context ctx) throws Exception {
        String stateParam = ctx.queryParam("state");
        Set<TaskState> states = null;
        if (stateParam != null && !stateParam.isBlank()) {
            states = java.util.Arrays.stream(stateParam.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(TaskState::parse)
                    .collect(java.util.stream.Collectors.toSet());
        }
        ctx.json(ok(Map.of("tasks", taskViews(states))));
    }

    private List<Map<String, Object>> taskViews(Set<TaskState> states) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DownloadTask t : store.listTasks(states)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("assetRuleId", t.assetRuleId());
            m.put("assetName", t.assetName());
            m.put("releaseTag", t.releaseTag());
            m.put("state", t.state().name());
            m.put("bytesDone", t.bytesDone());
            m.put("expectedSize", t.expectedSize() == null ? 0 : t.expectedSize());
            m.put("percent", t.percent());
            m.put("acceptsRanges", t.canResume());
            m.put("hasDigest", t.hasDigest());
            m.put("running", downloadService.isRunning(t.id()));
            m.put("errorCode", t.errorCode() == null ? "" : t.errorCode());
            m.put("errorMessage", t.errorMessage() == null ? "" : t.errorMessage());
            m.put("retryCount", t.retryCount());
            out.add(m);
        }
        return out;
    }

    private void getTask(Context ctx) throws Exception {
        long id = parseId(ctx);
        Optional<DownloadTask> task = store.findTask(id);
        if (task.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "任务不存在"));
            return;
        }
        DownloadTask t = task.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("assetRuleId", t.assetRuleId());
        m.put("assetName", t.assetName());
        m.put("releaseTag", t.releaseTag());
        m.put("state", t.state().name());
        m.put("bytesDone", t.bytesDone());
        m.put("expectedSize", t.expectedSize() == null ? 0 : t.expectedSize());
        m.put("percent", t.percent());
        m.put("partPath", t.partPath());
        m.put("finalPath", t.finalPath());
        m.put("acceptsRanges", t.canResume());
        m.put("hasDigest", t.hasDigest());
        m.put("errorCode", t.errorCode() == null ? "" : t.errorCode());
        m.put("errorMessage", t.errorMessage() == null ? "" : t.errorMessage());
        ctx.json(ok(m));
    }

    /**
     * 创建下载任务。
     *
     * <p>需要客户端提供 asset 的完整信息（因为检查阶段已经拿到过 release 数据），
     * 避免再消耗一次 API 配额去查。
     */
    private void createTask(Context ctx) throws Exception {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);

        long ruleId = num(body.get("assetRuleId"), 0);
        if (ruleId <= 0) {
            ctx.status(400).json(error("VALIDATION_FAILED", "assetRuleId 不能为空"));
            return;
        }

        // 从规则反查条目与目标目录
        Optional<AssetRule> rule = findRuleById(ruleId);
        if (rule.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "规则不存在"));
            return;
        }
        AssetRule r = rule.get();

        String assetName = str(body.get("assetName"));
        String downloadUrl = str(body.get("downloadUrl"));
        if (assetName == null || assetName.isBlank() || downloadUrl == null || downloadUrl.isBlank()) {
            ctx.status(400).json(error("VALIDATION_FAILED", "assetName 与 downloadUrl 不能为空"));
            return;
        }

        long releaseId = num(body.get("releaseId"), 0);
        String releaseTag = Optional.ofNullable(str(body.get("releaseTag"))).orElse("unknown");
        long assetId = num(body.get("assetId"), 0);
        Long expectedSize = body.get("expectedSize") == null
                ? null : num(body.get("expectedSize"), 0);
        String expectedDigest = str(body.get("expectedDigest"));

        // 路径规划：.part 与最终文件必须同卷（ATOMIC_MOVE 的前提）
        Path targetDir = Path.of(r.targetDir());
        String fileName = (r.kind() == AssetKind.COLLECTION)
                ? VersionResolver.renderFileName(r.effectiveFileNameTpl(), assetName, releaseTag)
                : assetName;
        Path finalPath = targetDir.resolve(fileName);

        // 暂存目录放在数据目录下，与目标目录可能不同卷。
        // 为保证原子性，.part 必须与最终文件同卷，所以放在目标目录旁边。
        Path tempDir = targetDir.resolve(".githubupdater-tmp");
        Files.createDirectories(tempDir);
        Path partPath = tempDir.resolve(fileName + ".part");

        long taskId = store.insertTask(ruleId, releaseId, releaseTag, assetId, assetName,
                downloadUrl, expectedSize, expectedDigest,
                partPath.toString(), finalPath.toString());

        ctx.json(ok(Map.of(
                "taskId", taskId,
                "state", TaskState.QUEUED.name(),
                "partPath", partPath.toString(),
                "finalPath", finalPath.toString()
        )));
    }

    private Optional<AssetRule> findRuleById(long ruleId) throws Exception {
        // 规则表没有按 id 直接查的方法，遍历条目找（规则数量很少，可接受）
        for (Entry e : store.listEntries()) {
            for (AssetRule r : store.listRules(e.id())) {
                if (r.id() == ruleId) {
                    return Optional.of(r);
                }
            }
        }
        return Optional.empty();
    }

    private void pauseTask(Context ctx) throws Exception {
        long id = parseId(ctx);
        Optional<DownloadTask> task = store.findTask(id);
        if (task.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "任务不存在"));
            return;
        }
        // 服务器不支持 Range 时暂停会导致重下，必须明确告知而不是让用户点了才发现
        if (!task.get().canResume() && task.get().bytesDone() > 0) {
            ctx.status(409).json(error("RANGE_UNSUPPORTED",
                    "该镜像不支持断点续传，暂停将导致重新下载"));
            return;
        }
        boolean ok = downloadService.pause(id);
        ctx.json(ok(ok ? Map.of("paused", true) : Map.of("paused", false)));
    }

    private void resumeTask(Context ctx) throws Exception {
        long id = parseId(ctx);
        boolean ok = downloadService.resume(id);
        if (!ok) {
            ctx.status(409).json(error("VALIDATION_FAILED", "任务当前状态不允许继续"));
            return;
        }
        ctx.json(ok(Map.of("resumed", true)));
    }

    private void cancelTask(Context ctx) throws Exception {
        long id = parseId(ctx);
        boolean ok = downloadService.cancel(id);
        ctx.json(ok(ok ? Map.of("cancelled", true) : Map.of("cancelled", false)));
    }

    private void retryTask(Context ctx) throws Exception {
        long id = parseId(ctx);
        boolean ok = downloadService.retry(id);
        if (!ok) {
            ctx.status(409).json(error("VALIDATION_FAILED", "只有失败的任务可以重试"));
            return;
        }
        ctx.json(ok(Map.of("retried", true)));
    }

    // ──────────────────────── 应用与回滚 ────────────────────────

    /**
     * 应用一个已完成的任务。
     *
     * <p>响应要区分四种结果，前端据此弹出不同的确认框：
     * 成功 / 被占用（问是否结束进程）/ 需确认未校验 / 失败。
     */
    private void applyTask(Context ctx) throws Exception {
        long id = parseId(ctx);
        Optional<DownloadTask> found = store.findTask(id);
        if (found.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "任务不存在"));
            return;
        }
        DownloadTask task = found.get();

        Optional<AssetRule> rule = findRuleById(task.assetRuleId());
        if (rule.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "任务对应的规则已被删除"));
            return;
        }
        Optional<Entry> entry = store.findEntry(rule.get().entryId());
        if (entry.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "规则对应的条目已被删除"));
            return;
        }

        Map<?, ?> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            body = Map.of();
        }
        ApplyService.Options opts = new ApplyService.Options(
                Boolean.TRUE.equals(body.get("force")),
                Boolean.TRUE.equals(body.get("unverifiedConfirmed")));

        ApplyService.Result result = applyService.apply(task, rule.get(), entry.get(), opts);

        if (result instanceof ApplyService.Applied applied) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("applied", true);
            data.put("kind", applied.kind().name());
            data.put("targetPath", applied.targetPath() == null ? "" : applied.targetPath().toString());
            data.put("backupPath", applied.backupPath() == null ? "" : applied.backupPath().toString());
            data.put("digestVerified", applied.digestVerified());
            data.put("message", applied.message());
            ctx.json(ok(data));
            return;
        }

        if (result instanceof ApplyService.Locked locked) {
            List<Map<String, Object>> processes = new ArrayList<>();
            for (var pi : locked.processes()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pid", pi.pid());
                m.put("name", pi.name());
                processes.add(m);
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", "FILE_LOCKED");
            err.put("message", "目标文件被 " + processes.size() + " 个进程占用");
            err.put("detail", Map.of("processes", processes));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", err);
            ctx.status(423).json(m);
            return;
        }

        if (result instanceof ApplyService.NeedConfirm needConfirm) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", "VALIDATION_FAILED");
            err.put("message", needConfirm.message());
            err.put("detail", Map.of("requiresUnverifiedConfirm", true));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", err);
            ctx.status(400).json(m);
            return;
        }

        ApplyService.Failed failed = (ApplyService.Failed) result;
        ctx.status(statusOf(failed.code())).json(error(failed.code(), failed.message()));
    }

    /** 回滚某个任务：找到该规则下最新的备份并恢复。 */
    private void rollbackTask(Context ctx) throws Exception {
        long id = parseId(ctx);
        Optional<DownloadTask> found = store.findTask(id);
        if (found.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "任务不存在"));
            return;
        }
        long ruleId = found.get().assetRuleId();

        List<com.githubupdater.domain.FileBackup> backups =
                store.listBackups(ruleId, 1);
        if (backups.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "该规则没有可回滚的备份"));
            return;
        }
        respondRollback(ctx, applyService.rollback(backups.get(0).id()));
    }

    private void rollbackBackup(Context ctx) throws Exception {
        long id = parseId(ctx);
        respondRollback(ctx, applyService.rollback(id));
    }

    private void respondRollback(Context ctx, ApplyService.Result result) {
        if (result instanceof ApplyService.Applied applied) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("rolledBack", true);
            data.put("targetPath", applied.targetPath() == null ? "" : applied.targetPath().toString());
            data.put("message", applied.message());
            ctx.json(ok(data));
            return;
        }
        if (result instanceof ApplyService.Failed failed) {
            ctx.status(statusOf(failed.code())).json(error(failed.code(), failed.message()));
            return;
        }
        ctx.status(500).json(error("INTERNAL", "回滚返回了非预期结果"));
    }

    /**
     * 直接更新 SOURCE 类规则的工作副本到指定 tag。
     *
     * <p>这类规则没有可下载的资产——源码是 clone 的，所以不建下载任务。
     */
    private void applySourceRule(Context ctx) throws Exception {
        long ruleId = parseId(ctx);
        Optional<AssetRule> found = findRuleById(ruleId);
        if (found.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "规则不存在"));
            return;
        }
        AssetRule rule = found.get();
        if (rule.kind() != AssetKind.SOURCE) {
            ctx.status(400).json(error("VALIDATION_FAILED",
                    "该规则类型是 " + rule.kind().name() + "，只有 SOURCE 类可走此端点"));
            return;
        }

        Optional<Entry> entry = store.findEntry(rule.entryId());
        if (entry.isEmpty()) {
            ctx.status(404).json(error("NOT_FOUND", "规则对应的条目已被删除"));
            return;
        }

        Map<?, ?> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            body = Map.of();
        }
        String tag = str(body.get("tag"));
        if (tag == null || tag.isBlank()) {
            // 未指定 tag 时用条目最近一次检查到的 tag
            tag = entry.get().lastSeenTag();
        }

        ApplyService.Result result = applyService.applySource(rule, entry.get(), tag);

        if (result instanceof ApplyService.Applied applied) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("applied", true);
            data.put("kind", applied.kind().name());
            data.put("targetPath", applied.targetPath() == null ? "" : applied.targetPath().toString());
            data.put("message", applied.message());
            data.put("gitHead", com.githubupdater.infra.git.GitRunner
                    .describeHead(applied.targetPath()));
            ctx.json(ok(data));
            return;
        }
        if (result instanceof ApplyService.Failed failed) {
            ctx.status(statusOf(failed.code())).json(error(failed.code(), failed.message()));
            return;
        }
        ctx.status(500).json(error("INTERNAL", "更新源码返回了非预期结果"));
    }

    private void listBackups(Context ctx) throws Exception {
        long ruleId = parseId(ctx);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var b : applyService.listBackups(ruleId, 50)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("assetRuleId", b.assetRuleId());
            m.put("filePath", b.filePath());
            m.put("backupPath", b.backupPath());
            m.put("releaseTag", b.releaseTag() == null ? "" : b.releaseTag());
            m.put("createdAt", b.createdAt());
            m.put("exists", Files.exists(Path.of(b.backupPath())));
            out.add(m);
        }
        ctx.json(ok(Map.of("backups", out)));
    }

    /** 错误码 → HTTP 状态码。与 docs/03-API接口.md 的错误码表一致。 */
    private static int statusOf(String code) {
        return switch (code) {
            case "UNAUTHORIZED", "GITHUB_AUTH_FAILED" -> 401;
            case "FORBIDDEN_HOST" -> 403;
            case "NOT_FOUND", "GITHUB_NOT_FOUND" -> 404;
            case "VALIDATION_FAILED" -> 400;
            case "ASSET_NOT_MATCHED", "ASSET_AMBIGUOUS", "RANGE_UNSUPPORTED", "GIT_DIRTY" -> 409;
            case "ASSET_GONE" -> 410;
            case "DIGEST_MISMATCH" -> 422;
            case "FILE_LOCKED" -> 423;
            case "RATE_LIMITED" -> 429;
            default -> 500;
        };
    }

    // ──────────────────────── 设置 ────────────────────────

    private void getSettings(Context ctx) {
        Map<String, Object> data = new LinkedHashMap<>();
        // 绝不返回 token 明文，只返回是否已配置
        data.put("githubTokenConfigured", config.hasToken());
        data.put("tokenStorage", config.tokenStorage);
        data.put("mirrorEnabled", config.mirrorEnabled);
        data.put("mirrorPrefix", config.mirrorPrefix);
        data.put("maxParallelTasks", config.maxParallelTasks);
        data.put("globalRateLimitKBps", config.globalRateLimitKBps);
        data.put("anonymousCheckBatchLimit", config.anonymousCheckBatchLimit);
        // 开机自启读的是注册表的真实状态，不是配置字段——
        // 用户可能手工删掉了 Run 项，界面必须反映现实。
        //
        // 但"注册表里有值"不等于"当前生效"：
        //   - 开发模式（java -jar）根本没有固定的 exe，自启无从谈起
        //   - 注册表里的路径可能指向已被删除或移动的目录（上次运行留下的残留）
        //
        // 所以必须同时要求「环境支持」+「注册表路径仍指向一个真实存在的文件」，
        // 否则界面会出现自相矛盾的状态：勾选框打着勾，下面却写着"无法设置"。
        // 这是实测踩到的——开发期做过一次打包验证，注册表遗留了指向
        // 临时目录的项，之后每次 java -jar 启动都显示成"已开启"。
        boolean autoStartSupported = com.githubupdater.infra.win.AutoStart
                .currentExecutable().isPresent();
        boolean autoStartEnabled = autoStartSupported
                && com.githubupdater.infra.win.AutoStart.isEnabled()
                && com.githubupdater.infra.win.AutoStart.registeredExecutableExists();
        data.put("autoStart", autoStartEnabled);
        data.put("autoStartSupported", autoStartSupported);
        data.put("keepBackups", config.keepBackups);
        data.put("customCa", customCaView());
        data.put("schemaVersion", config.schemaVersion);
        data.put("version", "0.1.0");
        data.put("mirrorPresets", List.of(
                mirrorPreset("ghfast.top", "https://ghfast.top/"),
                mirrorPreset("gh-proxy.org", "https://gh-proxy.org/"),
                mirrorPreset("gh-proxy.com", "https://gh-proxy.com/")
        ));
        ctx.json(ok(data));
    }

    /**
     * 自定义 CA 的状态视图。
     *
     * <p>{@code status} 反映的是<b>启动时</b>实际加载的结果，不是当前配置里写的值。
     * 两者可能不一致（用户改了路径但没重启），所以另给 {@code requiresRestart}，
     * 让界面能明确说"要重启才生效"，而不是让用户以为已经生效了却没效果。
     */
    private Map<String, Object> customCaView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", config.customCaFile == null ? "" : config.customCaFile);
        m.put("status", tlsTrust.status().name());
        m.put("certCount", tlsTrust.certCount());
        m.put("detail", tlsTrust.detail() == null ? "" : tlsTrust.detail());
        m.put("active", tlsTrust.isLoaded());

        // 配置里的路径与启动时加载的路径不一致 → 需要重启
        boolean requiresRestart = false;
        try {
            Path configured = config.resolveCustomCaFile(dataDir);
            requiresRestart = configured != null && !configured.equals(startupCaPath);
        } catch (RuntimeException e) {
            requiresRestart = true;
        }
        m.put("requiresRestart", requiresRestart);
        return m;
    }

    private static Map<String, String> mirrorPreset(String name, String prefix) {        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("prefix", prefix);
        return m;
    }

    private void patchSettings(Context ctx) {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        boolean tokenChanged = false;

        if (body.containsKey("githubToken")) {
            String t = str(body.get("githubToken"));
            config.githubToken = (t == null || t.isBlank()) ? null : t.trim();
            config.tokenStorage = "plaintext";
            tokenChanged = true;
        }
        if (body.containsKey("mirrorEnabled")) {
            config.mirrorEnabled = Boolean.TRUE.equals(body.get("mirrorEnabled"));
        }
        if (body.containsKey("mirrorPrefix")) {
            config.mirrorPrefix = Optional.ofNullable(str(body.get("mirrorPrefix"))).orElse("");
        }
        if (body.get("maxParallelTasks") instanceof Number n) {
            config.maxParallelTasks = n.intValue();
        }
        if (body.get("globalRateLimitKBps") instanceof Number n) {
            config.globalRateLimitKBps = n.intValue();
        }
        if (body.get("anonymousCheckBatchLimit") instanceof Number n) {
            config.anonymousCheckBatchLimit = n.intValue();
        }

        // 自定义 CA：保存前先真的试读一次。
        //
        // 不校验就存下去的话，用户会得到一个"看起来配好了"的界面，
        // 直到重启后才发现根本加载不了——那时他已经想不起来是路径写错了还是证书选错了。
        if (body.containsKey("customCaFile")) {
            String raw = str(body.get("customCaFile"));
            String trimmed = raw == null ? "" : raw.trim();

            if (trimmed.isEmpty()) {
                config.customCaFile = null;
            } else {
                Path candidate;
                try {
                    candidate = Path.of(trimmed).isAbsolute()
                            ? Path.of(trimmed)
                            : dataDir.resolve(trimmed).toAbsolutePath();
                } catch (RuntimeException e) {
                    ctx.status(400).json(error("VALIDATION_FAILED",
                            "CA 证书路径格式非法：" + e.getMessage()));
                    return;
                }
                var probe = com.githubupdater.infra.http.TlsTrust.fromFile(candidate);
                if (!probe.isLoaded()) {
                    ctx.status(400).json(error("VALIDATION_FAILED", probe.detail()));
                    return;
                }
                config.customCaFile = trimmed;
            }
        }

        // 开机自启：同时改配置与注册表。
        //
        // 注意不是"改配置就好"——注册表才是自启的真实开关，
        // 配置字段只是记录用户意图。两者必须同步，否则界面显示"已开启"
        // 但实际不会自启（或反之），这是最典型的静默失效。
        if (body.containsKey("autoStart")) {
            boolean want = Boolean.TRUE.equals(body.get("autoStart"));
            config.autoStart = applyAutoStart(want);
        }

        configStore.save(config);

        if (tokenChanged) {
            quota.update(Quota.unknown(config.hasToken()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("saved", true);
        data.put("githubTokenConfigured", config.hasToken());
        data.put("customCa", customCaView());
        ctx.json(ok(data));
    }

    /**
     * 应用开机自启设置，返回实际生效的状态。
     *
     * <p>返回实际状态而非请求值：注册表写入可能失败（权限、策略），
     * 此时必须如实告诉界面"没成功"，不能让用户以为开了。
     */
    private boolean applyAutoStart(boolean enable) {
        if (enable) {
            var exe = com.githubupdater.infra.win.AutoStart.currentExecutable();
            if (exe.isEmpty()) {
                log.warn("当前不是打包产物，无法启用开机自启（需要 GithubUpdater.exe）");
                return false;
            }
            return com.githubupdater.infra.win.AutoStart.enable(exe.get());
        }
        return com.githubupdater.infra.win.AutoStart.disable()
                ? false
                : com.githubupdater.infra.win.AutoStart.isEnabled();
    }

    // ──────────────────────── 视图与工具 ────────────────────────

    private Map<String, Object> entryView(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("owner", e.owner());
        m.put("repo", e.repo());
        m.put("fullName", e.fullName());
        m.put("displayName", e.displayName());
        m.put("channel", e.channel());
        m.put("isPrivate", e.isPrivate());
        m.put("lastSeenTag", e.lastSeenTag() == null ? "" : e.lastSeenTag());
        m.put("lastCheckedAt", e.lastCheckedAt() == null ? 0 : e.lastCheckedAt());
        m.put("ruleCount", countRulesQuietly(e.id()));
        // 最近一次检查的结局与原因。hasUpdate 只在当次会话的有效期内存在，
        // 而这两个字段是持久化的——刷新页面后用户仍能看到"上次为什么失败"。
        m.put("lastCheckStatus", e.lastCheckStatus() == null ? "" : e.lastCheckStatus());
        m.put("lastCheckError", e.lastCheckError() == null ? "" : e.lastCheckError());
        m.put("lastTlsIssuer", e.lastTlsIssuer() == null ? "" : e.lastTlsIssuer());
        return m;
    }

    private int countRulesQuietly(long entryId) {
        try {
            return store.listRules(entryId).size();
        } catch (java.sql.SQLException e) {
            log.warn("统计规则数量失败：{}", e.getMessage());
            return 0;
        }
    }

    private static Map<String, Object> ruleView(AssetRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("entryId", r.entryId());
        m.put("includeGlob", r.includeGlob());
        m.put("excludeGlob", r.excludeGlob() == null ? "" : r.excludeGlob());
        m.put("kind", r.kind().name());
        m.put("targetDir", r.targetDir());
        m.put("fileNameTpl", r.effectiveFileNameTpl());
        m.put("keepVersions", r.keepVersions());
        m.put("autoApply", r.autoApply());
        m.put("enabled", r.enabled());
        return m;
    }

    private Map<String, Object> quotaView() {
        var s = quota.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("remaining", s.remaining());
        m.put("limit", s.limit());
        m.put("authenticated", s.authenticated());
        m.put("resetEpochSecond", s.resetEpochSecond());
        m.put("secondsUntilReset", s.secondsUntilReset());
        return m;
    }

    /**
     * 解析仓库引用。支持三种形式：
     * {@code https://github.com/o/r}、{@code github.com/o/r}、{@code o/r}。
     */
    static Optional<String[]> parseRepoRef(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        s = s.replace("https://", "").replace("http://", "");
        if (s.startsWith("github.com/")) {
            s = s.substring("github.com/".length());
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - ".git".length());
        }
        s = s.replaceAll("/+$", "");

        String[] parts = s.split("/");
        if (parts.length < 2) {
            return Optional.empty();
        }
        String owner = parts[0].trim();
        String repo = parts[1].trim();
        if (owner.isEmpty() || repo.isEmpty()
                || !owner.matches("[A-Za-z0-9_.-]+")
                || !repo.matches("[A-Za-z0-9_.-]+")) {
            return Optional.empty();
        }
        return Optional.of(new String[]{owner, repo});
    }

    private static long parseId(Context ctx) {
        String raw = ctx.pathParam("id");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new io.javalin.http.BadRequestResponse("非法的 id: " + raw);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 把 JSON 数字安全转成 long（Jackson 可能给 Integer 或 Long）。 */
    private static long num(Object o, long defaultValue) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("data", data);
        return m;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", err);
        return m;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String baseUrl() {
        return "http://" + BIND_HOST + ":" + port;
    }

    /** 实际监听端口。绑的是 0，所以必须从 Javalin 读回真实值。 */
    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    public void stop() {
        workers.shutdownNow();
        sse.stop();
        downloadService.stop();
        if (app != null) {
            app.stop();
        }
    }
}
