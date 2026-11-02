package com.githubupdater.app;

import com.githubupdater.config.AppConfig;
import com.githubupdater.infra.github.Quota;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配额管理。
 *
 * <p>数据来源是每次 GitHub 响应的 {@code X-RateLimit-*} 头。只记录最新值，
 * 不做精确推算——官方明确说明"GitHub 在多区域处理请求，配额值可能在响应间波动"。
 */
public final class QuotaService {

    /** 预留余量，避免把配额用到 0 之后连错误处理都做不了。 */
    private static final int RESERVE = 5;

    private final AtomicReference<Quota> latest =
            new AtomicReference<>(Quota.unknown(false));

    private final AppConfig config;

    public QuotaService(AppConfig config) {
        this.config = config;
    }

    public Quota current() {
        return latest.get();
    }

    /** 记录一次响应带回的配额。认证状态以配置为准，不信任响应头推断。 */
    public void update(Quota quota) {
        if (quota != null && quota.isKnown()) {
            latest.set(new Quota(quota.limit(), quota.remaining(),
                    quota.resetEpochSecond(), config.hasToken()));
        }
    }

    /** 剩余量是否够检查 {@code count} 个条目。 */
    public boolean canCheck(int count) {
        Quota q = latest.get();
        if (!q.isKnown()) {
            return true;      // 还不知道配额，先放行
        }
        return q.remaining() >= count + RESERVE;
    }

    /**
     * 本批次最多能检查多少条。
     *
     * <p>匿名模式另有配置上限（默认 50），避免一次打空 60/h 的额度。
     */
    public int maxBatchSize(int requested) {
        int cap = Math.min(requested, config.effectiveCheckLimit());
        Quota q = latest.get();
        if (q.isKnown()) {
            cap = Math.min(cap, Math.max(0, q.remaining() - RESERVE));
        }
        return cap;
    }

    /** 供界面显示的快照。 */
    public Snapshot snapshot() {
        Quota q = latest.get();
        return new Snapshot(
                q.remaining(),
                q.limit(),
                q.resetEpochSecond(),
                config.hasToken(),
                q.isKnown() ? q.secondsUntilReset(Instant.now().getEpochSecond()) : 0
        );
    }

    /** 界面展示用的配额快照。 */
    public record Snapshot(int remaining, int limit, long resetEpochSecond,
                           boolean authenticated, long secondsUntilReset) {
    }
}
