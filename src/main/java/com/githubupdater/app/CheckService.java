package com.githubupdater.app;

import com.githubupdater.domain.AssetMatcher;
import com.githubupdater.domain.Entry;
import com.githubupdater.domain.VersionResolver;
import com.githubupdater.infra.db.SqliteStore;
import com.githubupdater.infra.github.GithubClient;
import com.githubupdater.infra.github.ReleaseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 检查服务：对条目调用 GitHub API，判断是否有新版本，匹配资产，写回状态。
 *
 * <p><b>不做后台轮询</b>（ADR-002）：只在用户显式请求时执行。世界只在你
 * 打开面板并点按钮时发生变化。
 *
 * <p>顺序执行，不并发：GitHub 有二级限流（并发请求 ≤100、单端点 ≤900 点/分钟），
 * 而本场景是低频率操作，顺序执行足以在几秒内完成几十个条目。
 */
public final class CheckService {

    private static final Logger log = LoggerFactory.getLogger(CheckService.class);

    /** 条目之间的间隔，避免触发二级限流。 */
    private static final long INTER_ENTRY_DELAY_MS = 200;

    private final SqliteStore store;
    private final GithubClient github;
    private final QuotaService quota;

    /** 防止重复触发批量检查。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CheckService(SqliteStore store, GithubClient github, QuotaService quota) {
        this.store = store;
        this.github = github;
        this.quota = quota;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 单个条目的检查结果。 */
    public record CheckOutcome(
            long entryId,
            String fullName,
            Outcome outcome,
            String tag,
            Long releaseId,
            boolean hasUpdate,
            List<String> matchedAssets,
            String message,
            /**
             * TLS 不受信任时的证书签发者。
             *
             * <p>单独带出来而不是塞进 message：界面要据此区分"这是中间人劫持"
             * 和"普通网络故障"，两者给的引导完全不同。
             */
            String tlsIssuer
    ) {

        /** 不含 TLS 诊断信息的结局。 */
        public CheckOutcome(long entryId, String fullName, Outcome outcome, String tag,
                            Long releaseId, boolean hasUpdate, List<String> matchedAssets,
                            String message) {
            this(entryId, fullName, outcome, tag, releaseId, hasUpdate, matchedAssets, message, null);
        }

        public enum Outcome {
            /** 出现新 release（id 与上次不同）。 */
            UPDATED,
            /** release 未变（id 相同，或 304）。 */
            NO_CHANGE,
            /** 检查失败。 */
            ERROR,
            /** 配额不足被跳过。 */
            SKIPPED
        }
    }

    /** 批量检查汇总。 */
    public record BatchResult(int requested, int checked, int skipped, List<CheckOutcome> outcomes) {
    }

    /**
     * 批量检查所有条目。
     *
     * @param onEach 每完成一个条目回调一次，用于推送 SSE；可为 null
     */
    public BatchResult checkAll(Consumer<CheckOutcome> onEach) throws SQLException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("检查已在进行中");
        }
        try {
            List<Entry> entries = store.listEntries();
            int limit = quota.maxBatchSize(entries.size());
            List<CheckOutcome> outcomes = new ArrayList<>();
            int checked = 0;
            int skipped = 0;

            for (Entry entry : entries) {
                if (checked >= limit) {
                    CheckOutcome skip = new CheckOutcome(entry.id(), entry.fullName(),
                            CheckOutcome.Outcome.SKIPPED, null, null, false, List.of(),
                            "配额或批量上限不足，已跳过");
                    outcomes.add(skip);
                    skipped++;
                    if (onEach != null) {
                        onEach.accept(skip);
                    }
                    continue;
                }

                CheckOutcome outcome = checkOne(entry);
                outcomes.add(outcome);
                checked++;
                if (onEach != null) {
                    onEach.accept(outcome);
                }

                if (entry != entries.get(entries.size() - 1)) {
                    sleepQuietly(INTER_ENTRY_DELAY_MS);
                }
            }

            log.info("批量检查完成：请求 {} 条，实际检查 {} 条，跳过 {} 条",
                    entries.size(), checked, skipped);
            return new BatchResult(entries.size(), checked, skipped, outcomes);
        } finally {
            running.set(false);
        }
    }

    /** 检查单个条目。异常不外抛，统一转成 ERROR 结果。 */
    public CheckOutcome checkOne(Entry entry) {
        try {
            GithubClient.Result result = fetchRelease(entry);

            if (result instanceof GithubClient.NotModified nm) {
                quota.update(nm.quota());
                store.updateEntryCheckOk(entry.id(), "NO_CHANGE");
                // 304 时拿不到资产清单，但规则可能已在缓存里匹配过。
                // 这里仍尝试用上次记录的 tag 匹配一次，避免界面在 304 后丢失已匹配结果。
                List<String> cached = matchAssetsForTag(entry, entry.lastSeenTag());
                return new CheckOutcome(entry.id(), entry.fullName(),
                        CheckOutcome.Outcome.NO_CHANGE, entry.lastSeenTag(),
                        entry.lastSeenReleaseId(), false, cached, "内容未修改 (304)");
            }

            if (result instanceof GithubClient.Failed failed) {
                quota.update(failed.quota());
                log.warn("检查 {} 失败：{} ({})", entry.fullName(), failed.message(), failed.kind());

                String message = describeFailure(failed);
                String tlsIssuer = failed.tls() == null ? null : failed.tls().issuer();
                // 失败原因必须落库：用户通常会去处理代理/网络再刷新页面回来看，
                // 只存在内存里的话刷新后就什么都看不到了。
                store.updateEntryCheckFailed(entry.id(),
                        CheckOutcome.Outcome.ERROR.name(), message, tlsIssuer);

                return new CheckOutcome(entry.id(), entry.fullName(),
                        CheckOutcome.Outcome.ERROR, null, null, false, List.of(),
                        message, tlsIssuer);
            }

            GithubClient.Ok ok = (GithubClient.Ok) result;
            quota.update(ok.quota());

            Optional<ReleaseDto.Release> target = pickRelease(entry, ok);
            if (target.isEmpty()) {
                store.updateEntryCheckOk(entry.id(), "NO_CHANGE");
                return new CheckOutcome(entry.id(), entry.fullName(),
                        CheckOutcome.Outcome.NO_CHANGE, null, null, false, List.of(),
                        "未找到符合条件的 release");
            }

            ReleaseDto.Release release = target.get();
            boolean hasUpdate = VersionResolver.hasUpdate(entry.lastSeenReleaseId(), release.id());

            // 匹配资产：无论是否新版本都要算，否则界面在"无更新"时看不到可用资产
            List<String> matched = matchAssets(entry, release);

            // 成功路径也要记录结局，并清掉上一次的失败信息
            store.updateEntryCheckOk(entry.id(), hasUpdate ? "UPDATED" : "NO_CHANGE");
            store.updateEntryChecked(entry.id(), release.id(), release.tagName(), ok.etag());

            log.info("检查 {} -> {} (id={}) 新版本={} 匹配资产={}",
                    entry.fullName(), release.tagName(), release.id(), hasUpdate, matched);

            return new CheckOutcome(entry.id(), entry.fullName(),
                    hasUpdate ? CheckOutcome.Outcome.UPDATED : CheckOutcome.Outcome.NO_CHANGE,
                    release.tagName(), release.id(), hasUpdate, matched, null);

        } catch (Exception e) {
            log.error("检查 {} 时发生未预期错误", entry.fullName(), e);
            return new CheckOutcome(entry.id(), entry.fullName(),
                    CheckOutcome.Outcome.ERROR, null, null, false, List.of(), e.getMessage());
        }
    }

    /**
     * 304 场景下尽力匹配资产。
     *
     * <p>304 没有响应体，拿不到资产清单。只能拉一次列表端点补数据——但那会
     * 再消耗一次配额，与"304 省配额"的初衷冲突。所以这里改为：仅在确实配置了
     * 规则时，才额外拉一次列表；否则直接返回空。
     *
     * <p>更优的做法是缓存上次的资产清单，属于后续优化，此处先保证功能正确。
     */
    private List<String> matchAssetsForTag(Entry entry, String tag) {
        if (tag == null || tag.isBlank()) {
            return List.of();
        }
        try {
            if (store.listRules(entry.id()).stream().noneMatch(r -> r.enabled())) {
                return List.of();       // 没配规则就别浪费配额
            }
            GithubClient.Result listResult = github.listReleases(entry.owner(), entry.repo(), 10);
            if (!(listResult instanceof GithubClient.Ok ok)) {
                return List.of();
            }
            quota.update(ok.quota());
            Optional<ReleaseDto.Release> found = GithubClient.parseReleases(ok.body()).stream()
                    .filter(r -> tag.equals(r.tagName()))
                    .findFirst();
            return found.map(r -> matchAssets(entry, r)).orElse(List.of());
        } catch (Exception e) {
            log.debug("304 后补拉资产清单失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 按频道决定调哪个端点。 */
    private GithubClient.Result fetchRelease(Entry entry) {
        if (entry.includesPrerelease()) {
            // latest 端点不返回 prerelease，必须拉列表再筛选
            return github.listReleases(entry.owner(), entry.repo(), 20);
        }
        return github.getLatestRelease(entry.owner(), entry.repo(), entry.lastEtag());
    }

    /** 从调用结果里挑出目标 release。 */
    private Optional<ReleaseDto.Release> pickRelease(Entry entry, GithubClient.Ok ok) {
        if (entry.includesPrerelease()) {
            List<ReleaseDto.Release> list = GithubClient.parseReleases(ok.body());
            return GithubClient.selectTarget(list, true, entry.pinnedTag());
        }
        return Optional.of(GithubClient.parseRelease(ok.body()));
    }

    /**
     * 用条目下所有启用的规则去匹配资产。
     *
     * <p>只做"能不能匹配上"的探测，不创建下载任务——下载需要用户显式触发。
     */
    private List<String> matchAssets(Entry entry, ReleaseDto.Release release) {
        List<String> result = new ArrayList<>();
        try {
            List<String> names = release.assets().stream()
                    .map(ReleaseDto.Asset::name)
                    .toList();
            for (var rule : store.listRules(entry.id())) {
                if (!rule.enabled()) {
                    continue;
                }
                AssetMatcher.Result match =
                        AssetMatcher.match(names, rule.includeGlob(), rule.excludeGlob());
                if (match.isSingle()) {
                    result.add(match.singleAsset());
                } else if (match.kind() == AssetMatcher.Result.Kind.AMBIGUOUS) {
                    log.warn("规则 {} 匹配到多个资产：{}", rule.id(), match.matched());
                }
            }
        } catch (SQLException e) {
            log.warn("读取规则失败：{}", e.getMessage());
        }
        return result;
    }

    /**
     * 把失败分类翻成用户能照着做的一句话。
     *
     * <p>{@code TLS_UNTRUSTED} 是这里最需要小心的一条：它的根因几乎总是
     * "本机有中间人代理"，而不是 GitHub 或这个工具的问题。文案必须
     * 说清三件事——发生了什么、可能是什么软件、去哪里修，
     * 否则用户只会看到"证书错误"然后去搜索与问题无关的东西。
     */
    private static String describeFailure(GithubClient.Failed failed) {
        return switch (failed.kind()) {
            case AUTH_FAILED -> "token 无效或过期，请在设置中重新填写";
            case RATE_LIMITED -> failed.message();
            case NOT_FOUND -> "仓库不存在，或私有库缺少权限";
            case TLS_UNTRUSTED -> describeTlsFailure(failed, false);
            case TLS_HOSTNAME_MISMATCH -> describeTlsFailure(failed, true);
            case NETWORK -> "网络请求失败：" + failed.message();
            case OTHER -> "请求失败 (" + failed.statusCode() + ")：" + failed.message();
        };
    }

    /**
     * TLS 证书问题的完整说明。
     *
     * <p>分两层：一句话结论 + 具体证据（签发者）+ 操作指引。
     * 拿不到签发者时也要说清"检测不到具体来源"，而不是假装知道。
     *
     * @param failed     失败结果
     * @param hostnameMismatch true 表示是主机名不匹配（不是链不受信任）
     */
    private static String describeTlsFailure(GithubClient.Failed failed, boolean hostnameMismatch) {
        var tls = failed.tls();
        StringBuilder sb = new StringBuilder();

        if (hostnameMismatch) {
            // 这条错误原文是 "No name matching api.github.com found"，
            // 很容易被当成 DNS 问题。必须主动说清它跟 DNS 无关。
            sb.append("证书与该域名不匹配，说明本机代理重签证书时没用对域名")
                    .append("（这不是 DNS 故障）");
        } else {
            sb.append("TLS 证书链不受信任，检测到本机有中间人在解密 HTTPS");
        }

        if (tls != null) {
            sb.append("。签发者：").append(tls.issuer());
            if (tls.selfSigned()) {
                sb.append("（自签名根证书，属于本机代理而非 GitHub）");
            }
            if (tls.hint() != null && !tls.hint().isBlank()) {
                sb.append("。").append(tls.hint());
            }
        } else {
            sb.append("（未能识别签发者，可能是代理未运行或已拒绝连接）");
        }

        sb.append("。请关闭该软件，或在 设置 → 网络与证书 里填入它的 CA 证书文件。");
        return sb.toString();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
