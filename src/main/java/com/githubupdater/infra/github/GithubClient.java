package com.githubupdater.infra.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * GitHub REST API 客户端。
 *
 * <p>只做三件事：拉 latest release、拉 release 列表、验证 token。下载资产不走这里
 * （直连 {@code browser_download_url} 不消耗 API 配额）。
 *
 * <p>关键事实（见 AGENTS.md 的 GitHub API 一节）：
 * <ul>
 *   <li><b>匿名请求即使返回 304 也扣配额</b>，官方限定"304 免配额"必须同时满足
 *       "返回 304"且"带 Authorization 头"。所以 ETag 在匿名下只省流量不省配额。</li>
 *   <li>不要把 {@code /releases/latest} 当"最新版本号"——作者可用 make_latest
 *       手动指定旧 release，且它按 created_at（commit 日期）排序。</li>
 * </ul>
 */
public final class GithubClient {

    private static final Logger log = LoggerFactory.getLogger(GithubClient.class);

    private static final String API_BASE = "https://api.github.com";

    /** GitHub 要求带 UA，否则部分端点返回 403。 */
    private static final String USER_AGENT = "GithubUpdater/0.1.0";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final java.util.function.Supplier<String> tokenSupplier;

    /** HttpClient 的执行器。用单线程，API 调用本来就是顺序的。 */
    private final java.util.concurrent.ExecutorService executor;

    public GithubClient(java.util.function.Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
        this.mapper = new ObjectMapper();

        // 显式用 HTTP/1.1 而不是默认的 HTTP/2。
        //
        // 为什么：HttpClient 默认尝试 HTTP/2，会为每个连接维护多路复用状态、
        // 更大的流控窗口和独立的 TLS 会话缓存。本工具的 API 调用是低频、
        // 顺序的（一次检查一个仓库），HTTP/2 的多路复用完全用不上，
        // 却要多付一份连接状态的常驻开销。
        //
        // 实测：改 HTTP/1.1 后检查操作的内存增量明显更小。
        //
        // executor 用单线程：API 调用本来就是顺序的（CheckService 单线程），
        // 默认的 ForkJoinPool 会按 CPU 核数拉起工作线程，纯属浪费。
        this.executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "github-http");
            t.setDaemon(true);
            return t;
        });

        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                // 跟随 302，但下载不经此客户端，这里只有 API 调用
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
    }

    /**
     * 调用结果。用 sealed 接口表达四类结局，调用方必须显式处理每种。
     */
    public sealed interface Result permits Ok, NotModified, Failed {
    }

    /** 成功拿到数据，同时带回新配额与响应头（ETag 在头里）。 */
    public record Ok(JsonNode body, Quota quota, String etag) implements Result {
    }

    /** 304，内容未变。注意：匿名时这次请求**仍然消耗了配额**。 */
    public record NotModified(Quota quota) implements Result {
    }

    /** 失败，带上可映射到 API 错误码的分类。 */
    public record Failed(Kind kind, int statusCode, String message, Quota quota) implements Result {

        public enum Kind {
            /** 401：token 无效或过期。 */
            AUTH_FAILED,
            /** 403/429：配额用尽或二级限流。 */
            RATE_LIMITED,
            /** 404：仓库不存在，或私有库无权限。 */
            NOT_FOUND,
            /** 网络层失败（超时、DNS、连接被拒）。 */
            NETWORK,
            /** 其它。 */
            OTHER
        }
    }

    /**
     * 获取 latest release。
     *
     * @param etag 上次的 ETag，为 null 则不发送条件头
     */
    public Result getLatestRelease(String owner, String repo, String etag) {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/releases/latest";
        return get(url, etag);
    }

    /**
     * 获取 release 列表（按创建时间倒序）。
     *
     * <p>用于 {@code prerelease} 频道：latest 端点不返回 prerelease，
     * 必须拉列表再筛选。
     *
     * @param perPage 每页条数，最大 100
     */
    public Result listReleases(String owner, String repo, int perPage) {
        int n = Math.max(1, Math.min(100, perPage));
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/releases?per_page=" + n;
        return get(url, null);
    }

    /** 验证 token 是否有效，并返回其配额。 */
    public Result verifyToken() {
        return get(API_BASE + "/rate_limit", null);
    }

    /** 通用 GET。 */
    private Result get(String url, String etag) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");

        String token = tokenSupplier.get();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (etag != null && !etag.isBlank()) {
            builder.header("If-None-Match", etag);
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("GitHub API 请求失败 {}: {}", url, e.getMessage());
            return new Failed(Failed.Kind.NETWORK, 0, e.getMessage(), Quota.unknown(hasToken()));
        }

        Quota quota = parseQuota(resp);

        int status = resp.statusCode();
        if (status == 304) {
            return new NotModified(quota);
        }
        if (status == 401) {
            return new Failed(Failed.Kind.AUTH_FAILED, status, "token 无效或已过期", quota);
        }
        if (status == 403 || status == 429) {
            // 403 可能是配额用尽，也可能是二级限流；两者处理方式相同（等待）
            return new Failed(Failed.Kind.RATE_LIMITED, status, describeRateLimit(resp), quota);
        }
        if (status == 404) {
            return new Failed(Failed.Kind.NOT_FOUND, status, "仓库不存在或无权限访问", quota);
        }
        if (status < 200 || status >= 300) {
            return new Failed(Failed.Kind.OTHER, status, truncate(resp.body()), quota);
        }

        try {
            return new Ok(
                    mapper.readTree(resp.body()),
                    quota,
                    resp.headers().firstValue("ETag").orElse(null));
        } catch (IOException e) {
            return new Failed(Failed.Kind.OTHER, status, "响应解析失败: " + e.getMessage(), quota);
        }
    }

    private boolean hasToken() {
        String t = tokenSupplier.get();
        return t != null && !t.isBlank();
    }

    private static Quota parseQuota(HttpResponse<String> resp) {
        Optional<String> limit = resp.headers().firstValue("X-RateLimit-Limit");
        Optional<String> remaining = resp.headers().firstValue("X-RateLimit-Remaining");
        Optional<String> reset = resp.headers().firstValue("X-RateLimit-Reset");
        if (limit.isEmpty() || remaining.isEmpty()) {
            return Quota.unknown(false);
        }
        try {
            return new Quota(
                    Integer.parseInt(limit.get().trim()),
                    Integer.parseInt(remaining.get().trim()),
                    reset.map(s -> Long.parseLong(s.trim())).orElse(0L),
                    // 有 remaining/limit 就说明调用成功，认证与否由调用方从配置判断
                    true
            );
        } catch (NumberFormatException e) {
            return Quota.unknown(false);
        }
    }

    private static String describeRateLimit(HttpResponse<String> resp) {
        Optional<String> remaining = resp.headers().firstValue("X-RateLimit-Remaining");
        if (remaining.isPresent() && "0".equals(remaining.get().trim())) {
            return "GitHub API 配额已用尽";
        }
        Optional<String> retryAfter = resp.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            return "触发二级限流，需等待 " + retryAfter.get() + " 秒";
        }
        return "请求被拒绝（可能触发二级限流）";
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    // ──────────────────────── 响应解析 ────────────────────────

    /** 把 release JSON 转成 {@link ReleaseDto.Release}。 */
    public static ReleaseDto.Release parseRelease(JsonNode node) {
        List<ReleaseDto.Asset> assets = new ArrayList<>();
        JsonNode assetsNode = node.get("assets");
        if (assetsNode != null && assetsNode.isArray()) {
            for (JsonNode a : assetsNode) {
                assets.add(new ReleaseDto.Asset(
                        a.path("id").asLong(),
                        a.path("name").asText(""),
                        a.path("size").asLong(0),
                        a.hasNonNull("digest") ? a.get("digest").asText() : null,
                        a.path("browser_download_url").asText("")
                ));
            }
        }
        return new ReleaseDto.Release(
                node.path("id").asLong(),
                node.path("tag_name").asText(""),
                node.hasNonNull("name") ? node.get("name").asText() : null,
                node.path("prerelease").asBoolean(false),
                node.path("draft").asBoolean(false),
                node.hasNonNull("published_at") ? node.get("published_at").asText() : null,
                assets
        );
    }

    /** 解析 release 数组。 */
    public static List<ReleaseDto.Release> parseReleases(JsonNode array) {
        List<ReleaseDto.Release> list = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                list.add(parseRelease(node));
            }
        }
        return list;
    }

    /**
     * 从 release 列表中挑选目标 release。
     *
     * @param releases  列表（GitHub 已按 created_at 倒序返回）
     * @param includePrerelease 是否接受预发布
     * @param pinnedTag 指定 tag；非 null 时只认这一个
     * @return 选中的 release，没有合适的返回 empty
     */
    public static Optional<ReleaseDto.Release> selectTarget(
            List<ReleaseDto.Release> releases, boolean includePrerelease, String pinnedTag) {

        for (ReleaseDto.Release r : releases) {
            if (r.draft()) {
                continue;
            }
            if (pinnedTag != null) {
                if (pinnedTag.equals(r.tagName())) {
                    return Optional.of(r);
                }
                continue;
            }
            if (r.prerelease() && !includePrerelease) {
                continue;
            }
            return Optional.of(r);
        }
        return Optional.empty();
    }

    /**
     * 关闭底层连接与执行器。
     *
     * <p>HttpClient 在 Java 21 实现了 {@code AutoCloseable}，但自定义的
     * executor 需要单独关闭——否则那个线程会一直活着，阻止 JVM 退出。
     */
    public void close() {
        try {
            http.close();
        } catch (RuntimeException e) {
            // 已经关闭或正在关闭，忽略
        }
        executor.shutdownNow();
    }
}
