package com.githubupdater.infra.http;

/**
 * 全局限速器（令牌桶）。
 *
 * <p>默认关闭（不限速）。开启后所有下载任务共享同一个配额，
 * 避免多个任务各自限速导致总带宽翻倍。
 *
 * <p>实现要点：按"每读 n 字节需等待多少毫秒"计算，不做精细的令牌补充，
 * 因为下载是流式读取，粒度本来就粗。
 */
public final class RateLimiter {

    /** 0 表示不限速。 */
    private final long bytesPerSecond;

    private long windowStartNanos = System.nanoTime();
    private long windowBytes = 0;

    public RateLimiter(long bytesPerSecond) {
        this.bytesPerSecond = Math.max(0, bytesPerSecond);
    }

    public boolean isEnabled() {
        return bytesPerSecond > 0;
    }

    /**
     * 记录已读字节并必要时阻塞。
     *
     * <p>用 1 秒滑动窗口：本秒内累计读超配额就 sleep 到下一秒。
     * 这种方式在限速精度上不完美，但实现简单且不会因为突发流量造成长时间卡顿。
     */
    public void acquire(int bytes) {
        if (!isEnabled() || bytes <= 0) {
            return;
        }

        windowBytes += bytes;
        long elapsedNanos = System.nanoTime() - windowStartNanos;
        long elapsedMs = elapsedNanos / 1_000_000;

        if (elapsedMs >= 1000) {
            // 进入新窗口
            windowStartNanos = System.nanoTime();
            windowBytes = 0;
            return;
        }

        long allowedBytes = bytesPerSecond * elapsedMs / 1000;
        if (windowBytes > allowedBytes) {
            long excessBytes = windowBytes - allowedBytes;
            long sleepMs = excessBytes * 1000 / bytesPerSecond;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(Math.min(sleepMs, 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** 每秒钟的字节上限。0 表示不限速。 */
    public long bytesPerSecond() {
        return bytesPerSecond;
    }
}
