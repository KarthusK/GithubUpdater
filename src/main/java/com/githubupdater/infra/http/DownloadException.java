package com.githubupdater.infra.http;

/**
 * 下载失败分类。调用方必须按类型决定"重试"还是"放弃"。
 */
public final class DownloadException extends Exception {

    public enum Kind {
        /** 网络层失败（超时、DNS、连接重置）。可重试。 */
        NETWORK,
        /**
         * TLS 证书链不受信任。
         *
         * <p>通常是本机加速器/企业网关在解密 HTTPS。重试没有意义——
         * 必须先去设置里配对应 CA，所以不可重试。
         */
        TLS_UNTRUSTED,
        /** HTTP 4xx。多数不可重试，但 408/429 可以。 */
        HTTP_CLIENT,
        /** HTTP 5xx。可重试。 */
        HTTP_SERVER,
        /** 服务器不支持 Range，需要从头下。 */
        RANGE_UNSUPPORTED,
        /** {@code Content-Range} 与本地偏移不符，必须重下。 */
        RESYNC_REQUIRED,
        /** 本地偏移 ≥ 远端长度（416），需重新探测。 */
        RANGE_NOT_SATISFIABLE,
        /** SHA-256 不匹配。必须重下，不能重试续传。 */
        DIGEST_MISMATCH,
        /** 磁盘写入失败（空间不足、权限）。不可重试。 */
        IO,
        /** 被用户取消或暂停。 */
        CANCELLED,
        /** 其它未预期错误。 */
        UNKNOWN
    }

    private final Kind kind;
    private final int statusCode;

    public DownloadException(Kind kind, String message) {
        this(kind, message, 0, null);
    }

    public DownloadException(Kind kind, String message, Throwable cause) {
        this(kind, message, 0, cause);
    }

    public DownloadException(Kind kind, String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public Kind kind() {
        return kind;
    }

    public int statusCode() {
        return statusCode;
    }

    /** 是否可以重试（同一次下载，不重置进度）。 */
    public boolean isRetryable() {
        return switch (kind) {
            case NETWORK, HTTP_SERVER -> true;
            // 429 是限流，等待后可重试
            case HTTP_CLIENT -> statusCode == 408 || statusCode == 429;
            // TLS_UNTRUSTED 明确不可重试：同样的证书链再试一次还是失败
            default -> false;
        };
    }

    /** 是否需要清空进度重下。 */
    public boolean requiresReset() {
        return kind == Kind.RANGE_UNSUPPORTED
                || kind == Kind.RESYNC_REQUIRED
                || kind == Kind.RANGE_NOT_SATISFIABLE
                || kind == Kind.DIGEST_MISMATCH;
    }
}
