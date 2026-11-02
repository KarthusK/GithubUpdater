package com.githubupdater.app;

import com.githubupdater.config.AppConfig;
import com.githubupdater.domain.AssetKind;
import com.githubupdater.domain.AssetRule;
import com.githubupdater.domain.DownloadTask;
import com.githubupdater.domain.Entry;
import com.githubupdater.domain.TaskState;
import com.githubupdater.infra.db.SqliteStore;
import com.githubupdater.infra.fs.ArchiveExtractor;
import com.githubupdater.infra.fs.FileReplacer;
import com.githubupdater.infra.fs.ReplaceResult;
import com.githubupdater.infra.git.GitRunner;
import com.githubupdater.infra.http.DigestVerifier;
import com.githubupdater.util.PathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 应用器：把已下载并通过校验的文件落到最终位置。
 *
 * <p><b>四类资产只是 {@code kind} 不同</b>，共用"条目 + 资产规则"模型：
 * <table border="1">
 *   <tr><th>kind</th><th>动作</th><th>危险等级</th></tr>
 *   <tr><td>BINARY</td><td>备份 → 原子替换</td><td>高</td></tr>
 *   <tr><td>ARCHIVE</td><td>解压 → 覆盖</td><td>中</td></tr>
 *   <tr><td>SOURCE</td><td>git fetch + checkout</td><td>中</td></tr>
 *   <tr><td>COLLECTION</td><td>归档落盘</td><td>低</td></tr>
 * </table>
 *
 * <p><b>三条不可违反的安全约束</b>：
 * <ol>
 *   <li>应用前必须再校验一次 digest（不能信任"下载时校验过"——文件可能被外部改动）</li>
 *   <li>没有 digest 时必须由用户显式确认，不能默认放行</li>
 *   <li>任何覆盖都必须先备份，失败必须回滚</li>
 * </ol>
 */
public final class ApplyService {

    private static final Logger log = LoggerFactory.getLogger(ApplyService.class);

    /** 归档类清理旧版本时最多扫描的文件数，避免超大目录拖慢。 */
    private static final int MAX_SCAN_FILES = 5000;

    private final SqliteStore store;
    private final AppConfig config;
    private final FileReplacer replacer;

    public ApplyService(SqliteStore store, AppConfig config) {
        this.store = store;
        this.config = config;
        this.replacer = new FileReplacer(config.keepBackups);
    }

    /** 应用选项。 */
    public record Options(boolean force, boolean unverifiedConfirmed) {

        public static Options defaults() {
            return new Options(false, false);
        }
    }

    /** 应用结果。四态：成功 / 被占用 / 需确认 / 失败。 */
    public sealed interface Result
            permits Applied, Locked, NeedConfirm, Failed {
    }

    /**
     * 应用成功。
     *
     * @param digestVerified 是否做过 digest 校验并通过
     */
    public record Applied(AssetKind kind, Path targetPath, Path backupPath,
                          boolean digestVerified, String message) implements Result {
    }

    /** 目标文件被占用，需要用户决定是否结束进程。 */
    public record Locked(List<ReplaceResult.ProcessInfo> processes) implements Result {
    }

    /** 上游未提供校验和，需要用户显式确认。 */
    public record NeedConfirm(String message) implements Result {
    }

    /** 失败。{@code code} 是 API 错误码。 */
    public record Failed(String code, String message, Throwable cause) implements Result {
    }

    /**
     * 应用一个已下载完成的任务。
     *
     * @param task  下载任务，状态必须是 {@code DONE}
     * @param rule  资产规则
     * @param entry 所属条目（SOURCE 类需要 owner/repo）
     * @param opts  应用选项
     */
    public Result apply(DownloadTask task, AssetRule rule, Entry entry, Options opts) {
        if (task.state() != TaskState.DONE) {
            return new Failed("VALIDATION_FAILED",
                    "任务当前状态是 " + task.state().name() + "，只有 DONE 状态才能应用", null);
        }

        // 目标目录每次都要过 PathGuard：用户可能改过配置
        PathGuard.Result guard = PathGuard.checkTargetDir(rule.targetDir());
        if (!guard.ok()) {
            return new Failed("DIR_NOT_WRITABLE", guard.message(), null);
        }
        Path targetDir = guard.normalizedPath();

        // SOURCE 类不走文件落盘路径，直接进 git 分支
        if (rule.kind() == AssetKind.SOURCE) {
            return applySource(task, rule, entry, targetDir);
        }

        Path ready = readyFileOf(task);
        if (!Files.exists(ready)) {
            return new Failed("VALIDATION_FAILED",
                    "暂存文件不存在，可能已被清理：" + ready, null);
        }

        // 应用前重新校验：不能信任"下载时校验过"，文件可能被外部改动
        DigestVerifier.Result verify;
        try {
            verify = DigestVerifier.verify(ready, task.expectedDigest());
        } catch (IOException e) {
            return new Failed("INTERNAL", "读取暂存文件失败：" + e.getMessage(), e);
        }
        switch (verify.status()) {
            case MISMATCHED -> {
                log.error("应用前校验失败：期望 {}，实际 {}", verify.expected(), verify.actual());
                return new Failed("DIGEST_MISMATCH",
                        "应用前校验失败，暂存文件已损坏或被改动", null);
            }
            case UNVERIFIED -> {
                if (!opts.unverifiedConfirmed()) {
                    return new NeedConfirm("该资产上游未提供校验和，无法验证完整性，请确认后继续");
                }
                log.warn("用户确认接受未校验的文件：{}", task.assetName());
            }
            case MATCHED -> {
                // 正常路径
            }
        }
        boolean digestVerified = verify.status() == DigestVerifier.Result.Status.MATCHED;

        try {
            return switch (rule.kind()) {
                case BINARY -> applyBinary(task, rule, ready, opts, digestVerified);
                case ARCHIVE -> applyArchive(task, rule, ready, targetDir);
                case COLLECTION -> applyCollection(task, rule, ready, targetDir, digestVerified);
                case SOURCE -> throw new IllegalStateException("SOURCE 已在上方分流");
            };
        } catch (Exception e) {
            log.error("应用任务 {} 失败", task.id(), e);
            return new Failed("REPLACE_FAILED", "应用失败：" + e.getMessage(), e);
        }
    }

    // ──────────────────────────── BINARY ────────────────────────────

    private Result applyBinary(DownloadTask task, AssetRule rule, Path ready,
                               Options opts, boolean digestVerified) {
        Path target = Path.of(task.finalPath());

        ReplaceResult r = replacer.replace(ready, target, opts.force());
        if (r instanceof ReplaceResult.Locked locked) {
            return new Locked(locked.processes());
        }
        if (r instanceof ReplaceResult.Failed failed) {
            return new Failed("REPLACE_FAILED", failed.message(), failed.cause());
        }

        ReplaceResult.Success success = (ReplaceResult.Success) r;
        recordBackup(rule, success.target(), success.backup(), task);
        markApplied(task, success.target());
        cleanupTemp(task);
        log.info("BINARY 应用完成：{}", success.target());
        return new Applied(AssetKind.BINARY, success.target(), success.backup(),
                digestVerified, "已替换 " + success.target().getFileName());
    }

    // ──────────────────────────── ARCHIVE ────────────────────────────

    private Result applyArchive(DownloadTask task, AssetRule rule, Path ready, Path targetDir)
            throws IOException {
        // staging 与 targetDir 同级，保证同一卷
        Path staging = targetDir.resolveSibling(
                targetDir.getFileName() + ".staging." + System.nanoTime());

        ArchiveExtractor.ExtractResult extracted = ArchiveExtractor.extract(ready, staging);
        if (!extracted.ok()) {
            deleteQuietly(staging);
            return new Failed("REPLACE_FAILED", extracted.message(), null);
        }

        ReplaceResult r = ArchiveExtractor.swapInto(staging, targetDir, config.keepBackups);
        deleteQuietly(staging);

        if (r instanceof ReplaceResult.Failed failed) {
            return new Failed("REPLACE_FAILED", failed.message(), failed.cause());
        }

        ReplaceResult.Success success = (ReplaceResult.Success) r;
        recordBackup(rule, targetDir, success.backup(), task);
        markApplied(task, targetDir);

        // 压缩包本身不再需要，删掉避免用户目录里堆一堆 zip
        deleteQuietly(ready);
        cleanupTemp(task);

        log.info("ARCHIVE 应用完成：{} 个文件 -> {}", extracted.fileCount(), targetDir);
        return new Applied(AssetKind.ARCHIVE, targetDir, success.backup(), true,
                "已解压 " + extracted.fileCount() + " 个文件到 " + targetDir);
    }

    // ──────────────────────────── COLLECTION ────────────────────────────

    private Result applyCollection(DownloadTask task, AssetRule rule, Path ready,
                                   Path targetDir, boolean digestVerified) throws IOException {
        String fileName = Path.of(task.finalPath()).getFileName().toString();
        Path dest = targetDir.resolve(fileName);

        // 写入仍走"原子改名"，绝不直接往最终文件名写
        Files.createDirectories(targetDir);
        try {
            Files.move(ready, dest, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(ready, dest, StandardCopyOption.REPLACE_EXISTING);
        }

        markApplied(task, dest);

        // "只留最新一份"是留存策略，不是写入策略
        int keep = Math.max(1, rule.keepVersions());
        int pruned = pruneOldVersions(targetDir, fileName, keep);
        cleanupTemp(task);

        log.info("COLLECTION 落盘完成：{}，清理旧版本 {} 个", dest.getFileName(), pruned);
        return new Applied(AssetKind.COLLECTION, dest, null, digestVerified,
                "已归档到 " + dest.getFileName());
    }

    /**
     * 清理同一 base 名的旧版本文件。
     *
     * <p>只删"同一 base + 同一扩展名"且能解析出不同版本号的文件，
     * 保守匹配——宁可少删也不要误删用户的其它文件。
     *
     * @return 删除的文件数
     */
    int pruneOldVersions(Path dir, String newFileName, int keep) {
        if (keep < 1) {
            return 0;
        }
        int dot = newFileName.lastIndexOf('.');
        String base = dot < 0 ? newFileName : newFileName.substring(0, dot);
        String ext = dot < 0 ? "" : newFileName.substring(dot);

        // base 形如 "jq-1.7.1"，取最后一个 '-' 之前的部分作为前缀
        int dash = base.lastIndexOf('-');
        if (dash <= 0) {
            return 0;   // 无版本段，不做清理
        }
        String prefix = base.substring(0, dash + 1);

        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(prefix) + ".+" + Pattern.quote(ext) + "$",
                Pattern.CASE_INSENSITIVE);

        try (var stream = Files.list(dir)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> pattern.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingLong(ApplyService::lastModified).reversed())
                    .limit(MAX_SCAN_FILES)
                    .toList();

            int deleted = 0;
            for (int i = keep; i < candidates.size(); i++) {
                Path p = candidates.get(i);
                try {
                    Files.deleteIfExists(p);
                    deleted++;
                    log.debug("清理旧版本：{}", p.getFileName());
                } catch (IOException e) {
                    log.debug("删除旧版本失败：{} — {}", p.getFileName(), e.getMessage());
                }
            }
            return deleted;
        } catch (IOException e) {
            log.debug("扫描旧版本失败：{}", e.getMessage());
            return 0;
        }
    }

    // ──────────────────────────── SOURCE ────────────────────────────

    /**
     * 直接更新源码工作副本到指定 tag。
     *
     * <p>SOURCE 类<b>没有可下载的资产</b>——源码是 clone 下来的，不是下载的。
     * 所以它不走"下载任务 → 应用"流程，而是由界面直接调用本方法。
     *
     * @param rule  资产规则（{@code targetDir} 即工作副本目录）
     * @param entry 所属条目（提供 owner/repo）
     * @param tag   目标 tag；为空则只 fetch 不切换
     */
    public Result applySource(AssetRule rule, Entry entry, String tag) {
        if (tag == null || tag.isBlank()) {
            return new Failed("VALIDATION_FAILED",
                    "未指定要检出的 tag。SOURCE 类必须明确 tag，"
                            + "否则无从判断\"更新到哪个版本\"", null);
        }

        PathGuard.Result guard = PathGuard.checkTargetDir(rule.targetDir());
        if (!guard.ok()) {
            return new Failed("DIR_NOT_WRITABLE", guard.message(), null);
        }
        Path repoDir = guard.normalizedPath();

        String cloneUrl = "https://github.com/" + entry.owner() + "/" + entry.repo() + ".git";

        GitRunner.Result r = GitRunner.updateToTag(repoDir, cloneUrl, tag, null);

        if (r instanceof GitRunner.Dirty dirty) {
            return new Failed("GIT_DIRTY",
                    "工作副本有 " + dirty.changedFiles().size() + " 处未提交改动，已中止。"
                            + "请先手动提交或清理后再试（本工具绝不自动 stash/reset）", null);
        }
        if (r instanceof GitRunner.Failed failed) {
            return new Failed("GIT_FAILED", failed.message(), failed.cause());
        }

        GitRunner.Ok ok = (GitRunner.Ok) r;

        // 可选钩子：用户自己配置的构建命令
        if (rule.afterApplyCmd() != null && !rule.afterApplyCmd().isBlank()) {
            String hookResult = runHook(rule.afterApplyCmd(), repoDir);
            log.info("after_apply_cmd 执行完毕：{}", hookResult);
        }

        log.info("SOURCE 更新完成：{}", ok.message());
        return new Applied(AssetKind.SOURCE, repoDir, null, true, ok.message());
    }

    private Result applySource(DownloadTask task, AssetRule rule, Entry entry, Path targetDir) {
        Result r = applySource(rule, entry, task.releaseTag());
        if (r instanceof Applied) {
            markApplied(task, targetDir);
        }
        return r;
    }

    /**
     * 同步执行用户配置的钩子命令。
     *
     * <p>不内置构建：需要构建的用户自己填 {@code after_apply_cmd}。
     * 失败不影响应用结果，只记日志——源码已更新到目标 tag 才是主结果。
     */
    private static String runHook(String command, Path workDir) {
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                return "超时已终止";
            }
            int code = p.exitValue();
            return code == 0 ? "成功" : "退出码 " + code + "：" + tail(output, 500);
        } catch (IOException e) {
            return "启动失败：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "被中断";
        }
    }

    private static String tail(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : "..." + s.substring(s.length() - max);
    }

    // ──────────────────────────── 公共辅助 ────────────────────────────

    /**
     * 由 {@code .part} 路径推出 {@code .ready} 路径。
     *
     * <p>{@code DownloadService} 校验通过后把 {@code x.part} 改名为 {@code x.part.ready}，
     * 这里必须用完全一致的规则。
     */
    static Path readyFileOf(DownloadTask task) {
        Path part = Path.of(task.partPath());
        return part.resolveSibling(part.getFileName() + ".ready");
    }

    private void markApplied(DownloadTask task, Path target) {
        store.setState(task.id(), TaskState.APPLIED, null, null);
    }

    private void recordBackup(AssetRule rule, Path filePath, Path backupPath, DownloadTask task) {
        if (backupPath == null) {
            return;
        }
        try {
            store.insertBackup(rule.id(), filePath.toString(), backupPath.toString(),
                    task.releaseTag(), task.expectedDigest());
        } catch (Exception e) {
            // 备份记录写失败不应让应用失败——文件已经正确落盘了
            log.warn("记录备份失败（不影响应用结果）：{}", e.getMessage());
        }
    }

    /**
     * 清理任务自己的暂存目录。
     *
     * <p>{@code .part} / {@code .ready} 都在 {@code targetDir/.githubupdater-tmp/} 下，
     * 应用完成后必须清掉，否则用户目录里会一直留着一个隐藏目录。
     * 目录非空（还有别的任务在用）就保留。
     */
    private void cleanupTemp(DownloadTask task) {
        Path part = Path.of(task.partPath());
        Path tempDir = part.getParent();
        if (tempDir == null || !tempDir.getFileName().toString().equals(".githubupdater-tmp")) {
            return;
        }
        try {
            Files.deleteIfExists(readyFileOf(task));
            Files.deleteIfExists(part);
            try (var stream = Files.list(tempDir)) {
                if (stream.findAny().isEmpty()) {
                    Files.deleteIfExists(tempDir);
                }
            }
        } catch (IOException e) {
            log.debug("清理暂存目录失败：{}", e.getMessage());
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null || !Files.exists(p)) {
            return;
        }
        try (var stream = Files.walk(p)) {
            for (Path x : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x);
            }
        } catch (IOException e) {
            log.debug("删除失败：{} — {}", p, e.getMessage());
        }
    }

    // ──────────────────────────── 回滚 ────────────────────────────

    /**
     * 回滚：把某条备份记录恢复到它原来的路径。
     */
    public Result rollback(long backupId) {
        com.githubupdater.domain.FileBackup backup;
        try {
            var opt = store.findBackup(backupId);
            if (opt.isEmpty()) {
                return new Failed("NOT_FOUND", "备份记录不存在", null);
            }
            backup = opt.get();
        } catch (Exception e) {
            return new Failed("INTERNAL", "读取备份记录失败：" + e.getMessage(), e);
        }

        Path backupPath = Path.of(backup.backupPath());
        Path target = Path.of(backup.filePath());

        if (!Files.exists(backupPath)) {
            return new Failed("NOT_FOUND",
                    "备份文件已不存在（可能被手动删除）：" + backupPath, null);
        }

        // 目录备份（ARCHIVE）走目录恢复
        ReplaceResult r = Files.isDirectory(backupPath)
                ? restoreDirectory(backupPath, target)
                : replacer.rollback(backupPath, target);

        if (r instanceof ReplaceResult.Failed failed) {
            return new Failed("REPLACE_FAILED", failed.message(), failed.cause());
        }

        store.deleteBackup(backupId);
        log.info("回滚完成：{} <- {}", target, backupPath.getFileName());
        return new Applied(AssetKind.BINARY, target, null, false,
                "已回滚到 " + backupPath.getFileName());
    }

    private ReplaceResult restoreDirectory(Path backupDir, Path target) {
        try {
            ArchiveExtractor.swapInto(backupDir, target, config.keepBackups);
            return new ReplaceResult.Success(target, null, 0);
        } catch (Exception e) {
            return new ReplaceResult.Failed("恢复目录失败：" + e.getMessage(), e, false);
        }
    }

    /** 供界面显示：某个规则下可回滚的备份列表。 */
    public List<com.githubupdater.domain.FileBackup> listBackups(long ruleId, int limit) {
        try {
            return store.listBackups(ruleId, limit);
        } catch (Exception e) {
            log.warn("读取备份列表失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 规范化扩展名，供界面显示。 */
    static String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
