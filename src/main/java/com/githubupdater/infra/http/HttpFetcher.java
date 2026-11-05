package com.githubupdater.infra.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * 断点续传下载器。
 *
 * <p><b>这个类是整个项目最容易产生静默损坏的地方</b>，每个细节都不能省：
 *
 * <ol>
 *   <li><b>必须用 {@code BodyHandlers.ofInputStream()}</b>，不能用 {@code ofFile()}——
 *       后者是截断语义，会把已下载的 .part 清空。</li>
 *   <li><b>{@code BodyHandlers} 不检查状态码</b>，必须自己判断 206/200/416，
 *       否则会把 404 的错误页当文件内容写进去。</li>
 *   <li><b>{@code If-Range} 是必须项不是优化项</b>：没有它，远端文件在两次下载间
 *       被替换（同名不同内容）时会拼出一个"看起来成功"的坏文件。</li>
 *   <li><b>必须校验 {@code Content-Range} 的起点等于本地偏移</b>，否则偏移错位
 *       照样拼出坏文件。</li>
 *   <li><b>收到 200 必须清空 .part 从头写</b>（服务器不支持续传，或 If-Range
 *       判定文件已变）。</li>
 *   <li><b>收到 416 必须重新探测远端长度</b>，再决定"已完成"还是"重下"。</li>
 * </ol>
 */
public final class HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);

    private static final String USER_AGENT = "GithubUpdater/0.1.0";

    /** 下载用的读缓冲。64KB 是常见块大小的合理值，太小会增加系统调用次数。 */
    private static final int BUFFER_SIZE = 64 * 1024;

    private final HttpClient http;
    private final RateLimiter rateLimiter;

    /** HttpClient 的执行器。下载是流式的，用少量线程即可。 */
    private final java.util.concurrent.ExecutorService executor;

    public HttpFetcher(RateLimiter rateLimiter) {
        this(rateLimiter, null);
    }

    /**
     * @param rateLimiter 全局限速器
     * @param ssl         自定义 SSLContext（含用户配置的额外 CA）；为 null 用默认
     */
    public HttpFetcher(RateLimiter rateLimiter, javax.net.ssl.SSLContext ssl) {
        this.rateLimiter = rateLimiter;

        // 与 GithubClient 同理：显式 HTTP/1.1 + 固定小线程池。
        //
        // HTTP/2 对"下载单个大文件"没有任何收益，却要多付连接状态与
        // 流控窗口的常驻开销。下载本来就是 2 个并发，用 2 个线程足够。
        this.executor = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "download-http");
            t.setDaemon(true);
            return t;
        });

        var builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                // 必须跟随 302：GitHub 会跳到 release-assets.githubusercontent.com
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor);
        // 下载目标（release-assets.githubusercontent.com）同样会被本机代理劫持，
        // 只给检查端配 CA 会出现"检查成功但下载失败"这种极难理解的状态。
        if (ssl != null) {
            builder.sslContext(ssl);
        }
        this.http = builder.build();
    }

    /**
     * 探测远端文件。
     *
     * <p>用 {@code Range: bytes=0-0} 而不是 HEAD：某些 CDN 对 HEAD 的响应头
     * 与 GET 不一致，用 GET 拿到的 {@code Accept-Ranges}/{@code ETag} 才可信。
     * 只读 1 字节，代价可忽略。
     */
    public RemoteProbe probe(String url) throws DownloadException {
        HttpRequest request = baseRequest(url)
                .header("Range", "bytes=0-0")
                .build();

        HttpResponse<InputStream> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            throw networkError(e);
        }

        // 必须消费/关闭响应体，否则连接不会被回收
        try (InputStream body = resp.body()) {
            body.readNBytes(64);
        } catch (IOException ignored) {
            // 探测阶段读失败不影响结论，头信息已经拿到
        }

        int status = resp.statusCode();
        String etag = resp.headers().firstValue("ETag").orElse(null);
        String lastModified = resp.headers().firstValue("Last-Modified").orElse(null);
        boolean acceptsRanges = resp.headers().firstValue("Accept-Ranges")
                .map(v -> v.toLowerCase().contains("bytes"))
                .orElse(false);

        long totalSize = -1;
        Optional<String> contentRange = resp.headers().firstValue("Content-Range");
        if (contentRange.isPresent()) {
            totalSize = parseTotalFromContentRange(contentRange.get());
            // 206 本身就证明支持 Range
            if (status == 206) {
                acceptsRanges = true;
            }
        } else {
            totalSize = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        }

        log.debug("探测 {} -> status={} ranges={} size={} etag={}",
                url, status, acceptsRanges, totalSize, etag);

        return new RemoteProbe(url, etag, lastModified, acceptsRanges, totalSize, status);
    }

    /**
     * 下载（或续传）到 {@code partFile}。
     *
     * @param taskId       任务 id，用于日志与进度回调
     * @param url          下载地址
     * @param partFile     .part 文件
     * @param knownEtag    上次记录的 ETag，用于 If-Range
     * @param probe        本次探测结果
     * @param listener     进度回调（bytesDone 为累计值）
     * @param cancelFlag   取消/暂停检查
     * @param abortHandle  可选的流中止句柄。暂停/取消时调用 {@code abort()} 主动关闭
     *                     底层流，让阻塞中的 {@code read()} 立即返回。
     *                     <b>没有它，网络慢时暂停会延迟很久才生效</b>。
     * @return 最终写入的字节总数
     */
    public long download(long taskId, String url, Path partFile, String knownEtag,
                         RemoteProbe probe, ProgressListener listener, CancelFlag cancelFlag,
                         StreamAbortHandle abortHandle)
            throws DownloadException {

        long existing = 0;
        try {
            if (Files.exists(partFile)) {
                existing = Files.size(partFile);
            }
        } catch (IOException e) {
            throw new DownloadException(DownloadException.Kind.IO,
                    "读取 .part 文件大小失败: " + e.getMessage(), e);
        }

        // 判断能否续传：必须同时满足三个条件
        boolean canResume = probe.acceptsRanges()
                && existing > 0
                && knownEtag != null && !knownEtag.isBlank()
                && knownEtag.equals(probe.etag());

        if (existing > 0 && !canResume) {
            if (probe.acceptsRanges() && !java.util.Objects.equals(knownEtag, probe.etag())) {
                // 文件已变：ETag 不同，必须重下。这是最危险的情形，必须显式处理。
                log.warn("任务 {} 远端文件已变更（etag {} -> {}），必须重下",
                        taskId, knownEtag, probe.etag());
                throw new DownloadException(DownloadException.Kind.RESYNC_REQUIRED,
                        "远端文件已变更，需重新下载");
            }
            if (!probe.acceptsRanges()) {
                log.info("任务 {} 服务器不支持 Range，从头下载", taskId);
                throw new DownloadException(DownloadException.Kind.RANGE_UNSUPPORTED,
                        "服务器不支持断点续传，需重新下载");
            }
            // 没有 ETag 但支持 Range：保守起见重下，不能冒险拼接
            log.warn("任务 {} 缺少 ETag，无法保证续传一致性，从头下载", taskId);
            throw new DownloadException(DownloadException.Kind.RESYNC_REQUIRED,
                    "缺少 ETag，无法保证续传一致性");
        }

        HttpRequest.Builder builder = baseRequest(url);
        if (canResume) {
            builder.header("Range", "bytes=" + existing + "-");
            // 关键：If-Range 让服务端在文件已变时返回 200 全量而非 206 片段
            builder.header("If-Range", knownEtag);
            log.info("任务 {} 从 {} 字节续传（etag={}）", taskId, existing, knownEtag);
        }

        HttpResponse<InputStream> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            throw networkError(e);
        }

        int status = resp.statusCode();
        long startOffset;

        switch (status) {
            case 206 -> {
                // 校验 Content-Range 的起点必须等于本地偏移
                String contentRange = resp.headers().firstValue("Content-Range").orElse(null);
                long rangeStart = parseStartFromContentRange(contentRange);
                if (rangeStart != existing) {
                    closeQuietly(resp.body());
                    throw new DownloadException(DownloadException.Kind.RESYNC_REQUIRED,
                            "Content-Range 起点(" + rangeStart + ")与本地偏移(" + existing + ")不符");
                }
                startOffset = existing;
            }
            case 200 -> {
                // 服务器不支持续传，或 If-Range 判定文件已变：必须清空从头写
                if (canResume) {
                    log.warn("任务 {} 请求了 Range 但收到 200，说明远端文件已变，从头下载", taskId);
                    closeQuietly(resp.body());
                    throw new DownloadException(DownloadException.Kind.RESYNC_REQUIRED,
                            "远端文件已变更，需重新下载");
                }
                startOffset = 0;
            }
            case 416 -> {
                closeQuietly(resp.body());
                throw new DownloadException(DownloadException.Kind.RANGE_NOT_SATISFIABLE,
                        "请求范围不可满足（本地偏移可能已超过远端长度）", 416, null);
            }
            default -> {
                closeQuietly(resp.body());
                DownloadException.Kind kind = status >= 500
                        ? DownloadException.Kind.HTTP_SERVER
                        : DownloadException.Kind.HTTP_CLIENT;
                throw new DownloadException(kind, "下载失败，HTTP " + status, status, null);
            }
        }

        long totalWritten;
        try (InputStream in = resp.body();
             RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {

            // 把流的关闭能力交给外部：暂停/取消时主动关流，
            // 让阻塞中的 read() 立即抛异常返回，而不是等下一个 chunk
            if (abortHandle != null) {
                final InputStream streamRef = in;
                abortHandle.register(() -> {
                    try {
                        streamRef.close();
                    } catch (IOException ignored) {
                        // 关闭失败无需处理，read() 会自行返回
                    }
                });
            }

            if (startOffset == 0) {
                raf.setLength(0);          // 从头写：必须截断，防止残留旧内容
            } else {
                raf.seek(startOffset);     // 续传：定位到已有末尾
            }

            // 预分配：减少边下边扩容产生的碎片
            long expectedTotal = probe.hasKnownSize() ? probe.totalSize() : -1;
            if (expectedTotal > 0) {
                try {
                    raf.setLength(expectedTotal);
                    raf.seek(startOffset);
                } catch (IOException e) {
                    // 预分配失败不致命（可能磁盘空间刚好不够），继续按需增长
                    log.debug("预分配失败（不影响下载）：{}", e.getMessage());
                }
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            long done = startOffset;
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (cancelFlag != null && cancelFlag.isCancelled()) {
                    throw new DownloadException(DownloadException.Kind.CANCELLED, "已取消或暂停");
                }
                raf.write(buffer, 0, n);
                done += n;

                if (rateLimiter != null) {
                    rateLimiter.acquire(n);
                }
                if (listener != null) {
                    listener.onProgress(done, expectedTotal);
                }
            }
            totalWritten = done;
        } catch (IOException e) {
            // 主动关流会走到这里。必须先判断是不是用户要求停止，
            // 否则会把"暂停"误报成"网络错误"。
            if (cancelFlag != null && cancelFlag.isCancelled()) {
                throw new DownloadException(DownloadException.Kind.CANCELLED, "已取消或暂停");
            }
            throw new DownloadException(DownloadException.Kind.IO,
                    "写入文件失败: " + e.getMessage(), e);
        } finally {
            if (abortHandle != null) {
                abortHandle.clear();
            }
        }

        // 若已知总大小，校验最终字节数
        if (probe.hasKnownSize() && totalWritten != probe.totalSize()) {
            throw new DownloadException(DownloadException.Kind.RESYNC_REQUIRED,
                    "下载字节数(" + totalWritten + ")与预期(" + probe.totalSize() + ")不符");
        }

        return totalWritten;
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", USER_AGENT);
    }

    /**
     * 从 {@code Content-Range: bytes 0-0/12345} 解析总长度。
     * 解析失败返回 -1。
     */
    static long parseTotalFromContentRange(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash == contentRange.length() - 1) {
            return -1;
        }
        String total = contentRange.substring(slash + 1).trim();
        if ("*".equals(total)) {
            return -1;
        }
        try {
            return Long.parseLong(total);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 从 {@code Content-Range: bytes 0-1023/12345} 解析起始偏移。
     * 解析失败返回 -1（调用方会判定为不符而重下）。
     */
    static long parseStartFromContentRange(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        String s = contentRange.trim();
        int space = s.indexOf(' ');
        if (space < 0) {
            return -1;
        }
        String range = s.substring(space + 1);
        int dash = range.indexOf('-');
        if (dash <= 0) {
            return -1;
        }
        try {
            return Long.parseLong(range.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理
            }
        }
    }

    private static DownloadException networkError(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        // 证书问题要有独立分类：否则用户只会看到"网络请求失败"，
        // 完全不知道原因是本机代理在解密流量。
        if (TlsDiagnostics.isTlsCertificateProblem(e)) {
            boolean mismatch = TlsDiagnostics.isHostnameMismatch(e);
            return new DownloadException(DownloadException.Kind.TLS_UNTRUSTED,
                    (mismatch ? "证书与域名不匹配：" : "TLS 证书链不受信任：") + e.getMessage(), e);
        }
        return new DownloadException(DownloadException.Kind.NETWORK,
                "网络请求失败: " + e.getMessage(), e);
    }

    /** 进度回调。{@code totalSize} 为 -1 表示未知。 */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long bytesDone, long totalSize);
    }

    /** 取消/暂停标志。 */
    @FunctionalInterface
    public interface CancelFlag {
        boolean isCancelled();
    }

    /**
     * 流中止句柄。
     *
     * <p>暂停/取消时由外部调用 {@link #abort()} 主动关闭底层 HTTP 流，
     * 让阻塞中的 {@code read()} 立即返回。
     *
     * <p><b>为什么必须有它</b>：只靠 cancelFlag 在循环里检查是不够的——
     * 网络慢时 {@code read()} 可能阻塞几十秒，用户点了暂停却要等很久才响应，
     * 体验上等同于"暂停无效"。
     */
    public static final class StreamAbortHandle {
        private volatile Runnable aborter;

        void register(Runnable aborter) {
            this.aborter = aborter;
        }

        void clear() {
            this.aborter = null;
        }

        /** 主动中断当前下载。可重复调用。 */
        public void abort() {
            Runnable a = this.aborter;
            if (a != null) {
                a.run();
            }
        }
    }

    /** 关闭连接与执行器。executor 不关的话那个线程会阻止 JVM 退出。 */
    public void close() {
        try {
            http.close();
        } catch (RuntimeException e) {
            // 已经关闭，忽略
        }
        executor.shutdownNow();
    }
}
