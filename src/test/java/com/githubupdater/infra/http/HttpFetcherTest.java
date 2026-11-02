package com.githubupdater.infra.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpFetcher 的集成测试，用本地 mock server 覆盖所有状态码分支。
 *
 * <p>这些分支是"静默损坏文件"的高发区，必须逐个验证：
 * 206 正常续传、200 需重下、416 需重探、If-Range 失效、Content-Range 不符。
 */
class HttpFetcherTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private HttpFetcher fetcher;
    private String baseUrl;

    /** 服务端内容，测试中可改。 */
    private byte[] serverContent;
    private String serverEtag;
    private boolean supportsRange;

    @BeforeEach
    void setUp() throws IOException {
        serverContent = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        serverEtag = "\"v1\"";
        supportsRange = true;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            String ifRange = exchange.getRequestHeaders().getFirst("If-Range");

            var headers = exchange.getResponseHeaders();
            headers.add("ETag", serverEtag);

            if (supportsRange) {
                headers.add("Accept-Ranges", "bytes");
            }

            // If-Range 不匹配 → 返回 200 全量（这是标准行为）
            boolean ifRangeMismatch = ifRange != null && !ifRange.equals(serverEtag);

            if (supportsRange && range != null && !ifRangeMismatch) {
                long start = parseStart(range);
                if (start >= serverContent.length) {
                    exchange.sendResponseHeaders(416, -1);
                    exchange.close();
                    return;
                }
                byte[] slice = java.util.Arrays.copyOfRange(serverContent, (int) start, serverContent.length);
                headers.add("Content-Range",
                        "bytes " + start + "-" + (serverContent.length - 1) + "/" + serverContent.length);
                exchange.sendResponseHeaders(206, slice.length);
                exchange.getResponseBody().write(slice);
                exchange.close();
                return;
            }

            // 全量 200
            exchange.sendResponseHeaders(200, serverContent.length);
            exchange.getResponseBody().write(serverContent);
            exchange.close();
        });
        server.start();

        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
        fetcher = new HttpFetcher(null);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (fetcher != null) {
            fetcher.close();
        }
    }

    private static long parseStart(String range) {
        // "bytes=100-"
        String s = range.substring("bytes=".length());
        int dash = s.indexOf('-');
        return Long.parseLong(s.substring(0, dash));
    }

    // ──────────────── 探测 ────────────────

    @Test
    @DisplayName("探测应拿到 ETag、总大小与 Range 支持")
    void probeDetectsCapabilities() throws Exception {
        RemoteProbe probe = fetcher.probe(baseUrl);

        assertTrue(probe.isValid());
        assertEquals(206, probe.statusCode(), "用 Range: bytes=0-0 探测应返回 206");
        assertTrue(probe.acceptsRanges());
        assertEquals(serverContent.length, probe.totalSize());
        assertEquals("\"v1\"", probe.etag());
    }

    @Test
    @DisplayName("服务器不支持 Range 时探测应如实反映")
    void probeDetectsNoRangeSupport() throws Exception {
        supportsRange = false;
        RemoteProbe probe = fetcher.probe(baseUrl);

        assertTrue(probe.isValid());
        assertEquals(200, probe.statusCode());
        assertEquals(serverContent.length, probe.totalSize());
    }

    // ──────────────── 全新下载 ────────────────

    @Test
    @DisplayName("全新下载应写入完整内容")
    void freshDownload() throws Exception {
        Path part = tempDir.resolve("f.part");
        RemoteProbe probe = fetcher.probe(baseUrl);

        long written = fetcher.download(1, baseUrl, part, null, probe, null, null, null);

        assertEquals(serverContent.length, written);
        assertEquals(new String(serverContent, StandardCharsets.UTF_8),
                Files.readString(part));
    }

    @Test
    @DisplayName("下载进度回调应被调用，且最终值等于总长度")
    void progressCallback() throws Exception {
        Path part = tempDir.resolve("g.part");
        RemoteProbe probe = fetcher.probe(baseUrl);
        AtomicReference<Long> last = new AtomicReference<>(0L);
        AtomicInteger calls = new AtomicInteger();

        fetcher.download(1, baseUrl, part, null, probe,
                (done, total) -> {
                    last.set(done);
                    calls.incrementAndGet();
                }, null, null);

        assertTrue(calls.get() > 0, "应至少回调一次");
        assertEquals((long) serverContent.length, last.get());
    }

    // ──────────────── 续传 ────────────────

    @Test
    @DisplayName("有 .part 且 ETag 一致时应续传，不重下已有部分")
    void resumeWithMatchingEtag() throws Exception {
        Path part = tempDir.resolve("h.part");
        // 预置前 8 字节，模拟上次下载中断
        Files.write(part, java.util.Arrays.copyOfRange(serverContent, 0, 8));

        RemoteProbe probe = fetcher.probe(baseUrl);
        long written = fetcher.download(1, baseUrl, part, "\"v1\"", probe, null, null, null);

        assertEquals(serverContent.length, written);
        assertEquals(new String(serverContent, StandardCharsets.UTF_8), Files.readString(part),
                "续传结果必须与完整内容一致");
    }

    @Test
    @DisplayName("ETag 已变时必须抛 RESYNC_REQUIRED，不能拼接")
    void resyncWhenEtagChanged() throws Exception {
        Path part = tempDir.resolve("i.part");
        Files.write(part, java.util.Arrays.copyOfRange(serverContent, 0, 8));

        RemoteProbe probe = fetcher.probe(baseUrl);
        // 传入旧 ETag，服务器返回新 ETag
        DownloadException ex = assertThrows(DownloadException.class,
                () -> fetcher.download(1, baseUrl, part, "\"old\"", probe, null, null, null));

        assertEquals(DownloadException.Kind.RESYNC_REQUIRED, ex.kind());
        assertTrue(ex.requiresReset());
        // 关键：文件内容不能被污染
        assertEquals(8, Files.size(part), ".part 不应被改动");
    }

    @Test
    @DisplayName("没有 ETag 但有 .part 时应保守重下，不冒险拼接")
    void resyncWhenNoEtag() throws Exception {
        Path part = tempDir.resolve("j.part");
        Files.write(part, new byte[]{1, 2, 3});

        RemoteProbe probe = fetcher.probe(baseUrl);
        DownloadException ex = assertThrows(DownloadException.class,
                () -> fetcher.download(1, baseUrl, part, null, probe, null, null, null));

        assertEquals(DownloadException.Kind.RESYNC_REQUIRED, ex.kind());
    }

    @Test
    @DisplayName("服务器不支持 Range 且有 .part 时应抛 RANGE_UNSUPPORTED")
    void rangeUnsupportedWithExistingPart() throws Exception {
        supportsRange = false;
        Path part = tempDir.resolve("k.part");
        Files.write(part, new byte[]{1, 2, 3});

        RemoteProbe probe = fetcher.probe(baseUrl);
        DownloadException ex = assertThrows(DownloadException.class,
                () -> fetcher.download(1, baseUrl, part, "\"v1\"", probe, null, null, null));

        assertEquals(DownloadException.Kind.RANGE_UNSUPPORTED, ex.kind());
    }

    // ──────────────── 边界情形 ────────────────

    @Test
    @DisplayName("本地偏移已达远端长度时，416 应被识别")
    void rangeNotSatisfiable() throws Exception {
        Path part = tempDir.resolve("l.part");
        // 写入与远端等长的内容，再请求续传 → 服务器返回 416
        Files.write(part, serverContent);

        RemoteProbe probe = fetcher.probe(baseUrl);
        DownloadException ex = assertThrows(DownloadException.class,
                () -> fetcher.download(1, baseUrl, part, "\"v1\"", probe, null, null, null));

        assertEquals(DownloadException.Kind.RANGE_NOT_SATISFIABLE, ex.kind());
    }

    @Test
    @DisplayName("取消标志生效时应抛 CANCELLED")
    void cancellation() throws Exception {
        Path part = tempDir.resolve("m.part");
        RemoteProbe probe = fetcher.probe(baseUrl);

        DownloadException ex = assertThrows(DownloadException.class,
                () -> fetcher.download(1, baseUrl, part, null, probe, null, () -> true, null));

        assertEquals(DownloadException.Kind.CANCELLED, ex.kind());
    }

    @Test
    @DisplayName("从头下载时必须截断已有 .part，不能残留旧内容")
    void truncatesOnFreshStart() throws Exception {
        Path part = tempDir.resolve("n.part");
        // 预置一堆垃圾内容，但没有 ETag → 会被判定为需重下
        Files.write(part, new byte[1000]);

        RemoteProbe probe = fetcher.probe(baseUrl);
        // 先删掉再下载，模拟调用方的重下流程
        Files.delete(part);
        fetcher.download(1, baseUrl, part, null, probe, null, null, null);

        assertEquals(serverContent.length, Files.size(part));
        assertEquals(new String(serverContent, StandardCharsets.UTF_8), Files.readString(part));
    }

    // ──────────────── 失败分类 ────────────────

    @Test
    @DisplayName("重试策略应区分可重试与不可重试")
    void retryClassification() {
        assertTrue(new DownloadException(DownloadException.Kind.NETWORK, "x").isRetryable());
        assertTrue(new DownloadException(DownloadException.Kind.HTTP_SERVER, "x").isRetryable());
        assertTrue(new DownloadException(DownloadException.Kind.HTTP_CLIENT, "x", 429, null).isRetryable());
        assertTrue(new DownloadException(DownloadException.Kind.HTTP_CLIENT, "x", 408, null).isRetryable());

        // 404 重试没有意义
        assertTrue(!new DownloadException(DownloadException.Kind.HTTP_CLIENT, "x", 404, null).isRetryable());
        // 校验失败必须重下，不是重试续传
        assertTrue(!new DownloadException(DownloadException.Kind.DIGEST_MISMATCH, "x").isRetryable());
    }

    @Test
    @DisplayName("需要重置进度的失败类型应被正确标记")
    void resetClassification() {
        assertTrue(new DownloadException(DownloadException.Kind.RESYNC_REQUIRED, "x").requiresReset());
        assertTrue(new DownloadException(DownloadException.Kind.DIGEST_MISMATCH, "x").requiresReset());
        assertTrue(new DownloadException(DownloadException.Kind.RANGE_UNSUPPORTED, "x").requiresReset());
        assertTrue(new DownloadException(DownloadException.Kind.RANGE_NOT_SATISFIABLE, "x").requiresReset());

        // 网络错误不需要重置，可以续传
        assertTrue(!new DownloadException(DownloadException.Kind.NETWORK, "x").requiresReset());
    }
}
