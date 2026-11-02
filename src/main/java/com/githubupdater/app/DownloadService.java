package com.githubupdater.app;

import com.githubupdater.config.AppConfig;
import com.githubupdater.domain.AssetRule;
import com.githubupdater.domain.DownloadTask;
import com.githubupdater.domain.Entry;
import com.githubupdater.domain.TaskState;
import com.githubupdater.infra.db.SqliteStore;
import com.githubupdater.infra.http.DigestVerifier;
import com.githubupdater.infra.http.DownloadException;
import com.githubupdater.infra.http.HttpFetcher;
import com.githubupdater.infra.http.RateLimiter;
import com.githubupdater.infra.http.RemoteProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 下载服务：调度器 + 工作线程池。
 *
 * <p>并发模型（见 docs/01-架构设计.md）：
 * <ul>
 *   <li>1 个调度线程：从 QUEUED 领任务，受 {@code maxParallelTasks} 限制</li>
 *   <li>最多 N 个下载工作线程（默认 2）</li>
 *   <li>每任务单连接，<b>不做分块并行</b>——GitHub 与镜像都对并发不友好</li>
 * </ul>
 *
 * <p>进度落库节流 500ms，SSE 推送节流 256KB。两个节流阈值都不是随意定的：
 * 每 chunk 写库会把 SQLite 单写线程打满，每 chunk 推 SSE 会造成大量无用序列化。
 */
public final class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    /** 进度落库间隔。 */
    private static final long PERSIST_INTERVAL_MS = 500;

    /** SSE 推送的字节间隔。 */
    private static final long PUSH_INTERVAL_BYTES = 256 * 1024;

    private final SqliteStore store;
    private final HttpFetcher fetcher;
    private final RateLimiter rateLimiter;
    private final AppConfig config;

    /** 正在运行的任务 → 取消标志。 */
    private final Map<Long, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();

    /**
     * 正在运行的任务 → 用户意图。
     *
     * <p>工作线程被中断时无法自行判断"用户是想暂停还是取消"，
     * 只能靠这个映射区分。不记录的话，取消会被当成暂停（保留 .part）。
     */
    private final Map<Long, TaskState> cancelIntents = new ConcurrentHashMap<>();

    /**
     * 正在运行的任务 → 流中止句柄。
     *
     * <p>暂停/取消时用它主动关闭底层流，让阻塞中的 read() 立即返回。
     */
    private final Map<Long, HttpFetcher.StreamAbortHandle> abortHandles = new ConcurrentHashMap<>();

    /**
     * 进度回调。
     *
     * <p>可以有多个消费者：SSE 推给浏览器、托盘切换图标状态。
     * 用 CopyOnWriteArrayList 是因为读多写极少，且遍历时不需要加锁。
     */
    private final java.util.List<Consumer<ProgressEvent>> progressListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private final ExecutorService workers;
    private volatile boolean started = false;
    private Thread scheduler;

    public DownloadService(SqliteStore store, HttpFetcher fetcher,
                           RateLimiter rateLimiter, AppConfig config) {
        this.store = store;
        this.fetcher = fetcher;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.workers = Executors.newFixedThreadPool(
                Math.max(1, Math.min(8, config.maxParallelTasks)),
                r -> {
                    Thread t = new Thread(r, "download-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 注册进度监听器。
     *
     * <p>支持多个监听器：SSE 推给浏览器，托盘用它切换图标状态。
     * 两者互相独立——某个监听器抛异常不能影响其它监听器，也不能影响下载本身。
     */
    public void addProgressListener(Consumer<ProgressEvent> listener) {
        if (listener != null) {
            progressListeners.add(listener);
        }
    }

    /** 把所有进度事件分发给全部监听器。单个监听器异常只记日志。 */
    private void fireProgress(ProgressEvent event) {
        for (Consumer<ProgressEvent> l : progressListeners) {
            try {
                l.accept(event);
            } catch (RuntimeException e) {
                log.debug("进度监听器出错：{}", e.getMessage());
            }
        }
    }

    /**
     * 启动调度器。
     *
     * <p>同时重置上次中断的任务：{@code RUNNING} → {@code PAUSED}。
     * 不直接回 QUEUED 是因为连接已断、ETag 可能失效，必须重新探测。
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        int reset = store.resetInterruptedTasks();
        if (reset > 0) {
            log.info("上次有 {} 个任务被中断，已重置为 PAUSED", reset);
        }
        started = true;

        scheduler = new Thread(this::scheduleLoop, "download-scheduler");
        scheduler.setDaemon(true);
        scheduler.start();
    }

    private void scheduleLoop() {
        while (started) {
            try {
                // 当前运行数已达上限就等
                if (runningFlags.size() >= config.maxParallelTasks) {
                    Thread.sleep(300);
                    continue;
                }

                Optional<DownloadTask> next = store.claimNextQueued();
                if (next.isEmpty()) {
                    Thread.sleep(500);
                    continue;
                }

                DownloadTask task = next.get();
                // 乐观并发：只有状态仍是 QUEUED 时才真正领取
                if (!store.transitionState(task.id(), TaskState.QUEUED, TaskState.RUNNING)) {
                    log.debug("任务 {} 已被其他线程领取", task.id());
                    continue;
                }

                AtomicBoolean flag = new AtomicBoolean(false);
                runningFlags.put(task.id(), flag);
                workers.submit(() -> executeTask(task.id(), flag));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("调度循环异常", e);
                sleepQuietly(1000);
            }
        }
    }

    /** 执行单个任务：探测 → 下载 → 校验 → 原子改名。 */
    private void executeTask(long taskId, AtomicBoolean cancelFlag) {
        DownloadTask task = null;
        try {
            task = store.findTask(taskId).orElse(null);
            if (task == null) {
                return;
            }

            Path partFile = Path.of(task.partPath());
            Files.createDirectories(partFile.getParent());

            // 1. 探测
            RemoteProbe probe;
            try {
                probe = fetcher.probe(task.downloadUrl());
            } catch (DownloadException e) {
                fail(taskId, e);
                return;
            }
            if (!probe.isValid()) {
                fail(taskId, new DownloadException(DownloadException.Kind.HTTP_CLIENT,
                        "探测失败，HTTP " + probe.statusCode(), probe.statusCode(), null));
                return;
            }

            store.updateTaskProbe(taskId, probe.etag(), probe.lastModified(),
                    probe.acceptsRanges(), probe.hasKnownSize() ? probe.totalSize() : null);

            // 2. 下载（可能因 ETag 变化而要求重下，重试一次）
            long total;
            try {
                total = downloadWithRetry(task, probe, partFile, cancelFlag);
            } catch (DownloadException e) {
                if (e.requiresReset() && e.kind() != DownloadException.Kind.CANCELLED) {
                    // 清空进度重下一次
                    log.warn("任务 {} 需重下：{}", taskId, e.getMessage());
                    Files.deleteIfExists(partFile);
                    store.resetTaskProgress(taskId);
                    RemoteProbe fresh = fetcher.probe(task.downloadUrl());
                    store.updateTaskProbe(taskId, fresh.etag(), fresh.lastModified(),
                            fresh.acceptsRanges(), fresh.hasKnownSize() ? fresh.totalSize() : null);
                    total = downloadWithRetry(store.findTask(taskId).orElseThrow(), fresh,
                            partFile, cancelFlag);
                } else {
                    throw e;
                }
            }

            // 3. 校验
            store.setState(taskId, TaskState.VERIFYING, null, null);
            DownloadTask current = store.findTask(taskId).orElseThrow();
            DigestVerifier.Result result = DigestVerifier.verify(partFile, current.expectedDigest());

            switch (result.status()) {
                case MISMATCHED -> {
                    Files.deleteIfExists(partFile);
                    fail(taskId, new DownloadException(DownloadException.Kind.DIGEST_MISMATCH,
                            "SHA-256 校验失败：期望 " + result.expected() + "，实际 " + result.actual()));
                    return;
                }
                case UNVERIFIED -> {
                    log.warn("任务 {} 上游未提供校验和，标记为未校验", taskId);
                    store.putMeta("task." + taskId + ".unverified", "true");
                }
                case MATCHED -> log.info("任务 {} 校验通过（{}）", taskId, result.actual());
            }

            // 4. 原子改名 .part → .ready
            Path readyFile = partFile.resolveSibling(partFile.getFileName() + ".ready");
            try {
                Files.move(partFile, readyFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                fail(taskId, new DownloadException(DownloadException.Kind.IO,
                        "重命名 .part 失败: " + e.getMessage(), e));
                return;
            }

            store.updateTaskProgress(taskId, total);
            store.setState(taskId, TaskState.DONE, null, null);
            log.info("任务 {} 下载完成，{} 字节", taskId, total);

        } catch (DownloadException e) {
            if (e.kind() == DownloadException.Kind.CANCELLED) {
                // 区分"暂停"与"取消"：取消要删 .part，暂停要保留以便续传
                TaskState desired = intentOf(taskId);
                if (desired == TaskState.CANCELLED) {
                    log.info("任务 {} 已取消，清理 .part", taskId);
                    try {
                        if (task != null) {
                            Files.deleteIfExists(Path.of(task.partPath()));
                        }
                    } catch (IOException io) {
                        log.warn("删除 .part 失败: {}", io.getMessage());
                    }
                    store.setState(taskId, TaskState.CANCELLED, null, null);
                    notifyState(taskId, TaskState.CANCELLED);
                } else {
                    log.info("任务 {} 已暂停，保留 .part 以便续传", taskId);
                    store.setState(taskId, TaskState.PAUSED, null, null);
                    notifyState(taskId, TaskState.PAUSED);
                }
            } else {
                fail(taskId, e);
            }
        } catch (Exception e) {
            log.error("任务 {} 执行失败", taskId, e);
            store.setState(taskId, TaskState.FAILED, "INTERNAL", e.getMessage());
            notifyState(taskId, TaskState.FAILED);
        } finally {
            runningFlags.remove(taskId);
            cancelIntents.remove(taskId);
        }
    }

    private void notifyState(long taskId, TaskState state) {
        fireProgress(ProgressEvent.stateChanged(taskId, state));
    }

    private TaskState intentOf(long taskId) {
        TaskState intent = cancelIntents.get(taskId);
        return intent == null ? TaskState.PAUSED : intent;
    }

    private long downloadWithRetry(DownloadTask task, RemoteProbe probe, Path partFile,
                                   AtomicBoolean cancelFlag) throws DownloadException {
        HttpFetcher.StreamAbortHandle abortHandle = new HttpFetcher.StreamAbortHandle();
        abortHandles.put(task.id(), abortHandle);
        try {
            return fetcher.download(task.id(), task.downloadUrl(), partFile, task.etag(),
                    probe,
                    new ThrottledProgress(task.id(), task.expectedSize()),
                    () -> cancelFlag.get(),
                    abortHandle);
        } finally {
            abortHandles.remove(task.id());
        }
    }

    /** 节流的进度回调：既控制落库频率，也控制 SSE 推送频率。 */
    private final class ThrottledProgress implements HttpFetcher.ProgressListener {
        private final long taskId;
        private final Long expectedSize;
        private long lastPersistMs = 0;
        private long lastPushedBytes = 0;
        private long startedAtMs = System.currentTimeMillis();

        ThrottledProgress(long taskId, Long expectedSize) {
            this.taskId = taskId;
            this.expectedSize = expectedSize;
        }

        @Override
        public void onProgress(long bytesDone, long totalSize) {
            long now = System.currentTimeMillis();

            // 落库节流
            if (now - lastPersistMs >= PERSIST_INTERVAL_MS) {
                lastPersistMs = now;
                store.updateTaskProgress(taskId, bytesDone);
            }

            // 推送节流
            if (bytesDone - lastPushedBytes >= PUSH_INTERVAL_BYTES || bytesDone == totalSize) {
                lastPushedBytes = bytesDone;
                long elapsed = Math.max(1, now - startedAtMs);
                long speed = bytesDone * 1000 / elapsed;
                fireProgress(ProgressEvent.progress(taskId, bytesDone, totalSize, speed));
            }
        }
    }

    private void fail(long taskId, DownloadException e) {
        String code = switch (e.kind()) {
            case NETWORK -> "NETWORK_ERROR";
            case HTTP_CLIENT, HTTP_SERVER -> "HTTP_" + e.statusCode();
            case RANGE_UNSUPPORTED -> "RANGE_UNSUPPORTED";
            case RESYNC_REQUIRED -> "RESYNC_REQUIRED";
            case RANGE_NOT_SATISFIABLE -> "RANGE_NOT_SATISFIABLE";
            case DIGEST_MISMATCH -> "DIGEST_MISMATCH";
            case IO -> "IO_ERROR";
            default -> "INTERNAL";
        };
        log.warn("任务 {} 失败 [{}] {}", taskId, code, e.getMessage());
        store.incrementRetry(taskId);
        store.setState(taskId, TaskState.FAILED, code, e.getMessage());
        fireProgress(ProgressEvent.stateChanged(taskId, TaskState.FAILED));
    }

    // ──────────────────────── 用户操作 ────────────────────────

    /**
     * 暂停任务。
     *
     * <p>只设置取消标志，<b>不中断连接</b>。工作线程会在下一个 chunk 处优雅退出，
     * 把状态转为 PAUSED 并保留 .part。若强制断开 socket，会被归类成 NETWORK_ERROR，
     * 用户看到的是"失败"而不是"已暂停"——这是实测踩到的问题。
     */
    public boolean pause(long taskId) {
        AtomicBoolean flag = runningFlags.get(taskId);
        if (flag != null) {
            cancelIntents.put(taskId, TaskState.PAUSED);
            flag.set(true);
            abortStream(taskId);
            return true;
        }
        return store.transitionState(taskId, TaskState.QUEUED, TaskState.PAUSED);
    }

    /** 主动中断底层流，让阻塞的 read() 立即返回。 */
    private void abortStream(long taskId) {
        HttpFetcher.StreamAbortHandle handle = abortHandles.get(taskId);
        if (handle != null) {
            handle.abort();
        }
    }

    /** 继续任务：PAUSED → QUEUED。 */
    public boolean resume(long taskId) {
        return store.transitionState(taskId, TaskState.PAUSED, TaskState.QUEUED);
    }

    /** 取消任务：正在运行则标记意图后由工作线程清理；否则直接转 CANCELLED 并删 .part。 */
    public boolean cancel(long taskId) {
        AtomicBoolean flag = runningFlags.get(taskId);
        if (flag != null) {
            cancelIntents.put(taskId, TaskState.CANCELLED);
            flag.set(true);
            abortStream(taskId);
            return true;
        }
        boolean ok = store.transitionState(taskId, TaskState.QUEUED, TaskState.CANCELLED)
                || store.transitionState(taskId, TaskState.PAUSED, TaskState.CANCELLED)
                || store.transitionState(taskId, TaskState.FAILED, TaskState.CANCELLED);
        if (ok) {
            try {
                Optional<DownloadTask> t = store.findTask(taskId);
                t.ifPresent(task -> {
                    try {
                        Files.deleteIfExists(Path.of(task.partPath()));
                    } catch (IOException e) {
                        log.warn("删除 .part 失败: {}", e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("清理取消任务失败: {}", e.getMessage());
            }
        }
        return ok;
    }

    /** 重试失败任务：FAILED → QUEUED，重置重试计数。 */
    public boolean retry(long taskId) {
        return store.transitionState(taskId, TaskState.FAILED, TaskState.QUEUED);
    }

    public Set<Long> runningTaskIds() {
        return Set.copyOf(runningFlags.keySet());
    }

    public boolean isRunning(long taskId) {
        return runningFlags.containsKey(taskId);
    }

    public synchronized void stop() {
        started = false;
        if (scheduler != null) {
            scheduler.interrupt();
        }
        runningFlags.values().forEach(f -> f.set(true));
        workers.shutdownNow();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 进度/状态事件。
     *
     * <p>用两个静态工厂区分两种语义，避免构造器重载歧义。
     */
    public record ProgressEvent(long taskId, long bytesDone, long totalSize,
                                long speedBytesPerSec, TaskState state) {

        /** 下载进度事件。 */
        public static ProgressEvent progress(long taskId, long bytesDone,
                                             long totalSize, long speed) {
            return new ProgressEvent(taskId, bytesDone, totalSize, speed, null);
        }

        /** 状态变更事件。 */
        public static ProgressEvent stateChanged(long taskId, TaskState state) {
            return new ProgressEvent(taskId, -1, -1, 0, state);
        }

        public boolean isProgress() {
            return state == null;
        }
    }
}
