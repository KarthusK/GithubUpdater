package com.githubupdater.infra.github;

/**
 * GitHub API 的配额信息。
 *
 * <p>来源是响应头 {@code X-RateLimit-*}。注意官方说明"GitHub 在多区域处理请求，
 * 配额值可能在响应间波动"，所以不对单次消耗值做精确推算，只记录最新值。
 */
public record Quota(
        int limit,
        int remaining,
        long resetEpochSecond,
        boolean authenticated
) {

    /** 完全未知时的占位值。 */
    public static Quota unknown(boolean authenticated) {
        return new Quota(authenticated ? 5000 : 60, -1, 0, authenticated);
    }

    /** 是否已知具体剩余量。 */
    public boolean isKnown() {
        return remaining >= 0;
    }

    /** 距离重置还有多少秒。 */
    public long secondsUntilReset(long nowEpochSecond) {
        if (resetEpochSecond <= 0) {
            return 0;
        }
        return Math.max(0, resetEpochSecond - nowEpochSecond);
    }

    /** 是否接近耗尽（保留 5 次余量用于重试与错误处理）。 */
    public boolean isNearExhausted() {
        return isKnown() && remaining <= 5;
    }
}
