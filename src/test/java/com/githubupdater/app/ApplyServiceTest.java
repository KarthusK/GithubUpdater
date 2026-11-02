package com.githubupdater.app;

import com.githubupdater.config.AppConfig;
import com.githubupdater.domain.AssetKind;
import com.githubupdater.domain.AssetRule;
import com.githubupdater.domain.DownloadTask;
import com.githubupdater.domain.Entry;
import com.githubupdater.domain.TaskState;
import com.githubupdater.infra.db.SqliteStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用器端到端：真实文件系统 + 真实 SQLite。
 *
 * <p>应用器是唯一会破坏用户文件的地方，所以这些用例必须走真实 IO，
 * 不能用 mock——mock 无法验证"备份真的存在""回滚真的恢复内容"。
 */
class ApplyServiceTest {

    @TempDir
    Path tempDir;

    private SqliteStore store;
    private ApplyService service;
    private AppConfig config;
    private Path dataDir;
    private Path targetDir;
    private long entryId;
    private long ruleId;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        store = SqliteStore.open(dataDir);

        config = new AppConfig();
        config.keepBackups = 2;
        service = new ApplyService(store, config);

        targetDir = tempDir.resolve("tools");
        Files.createDirectories(targetDir);

        entryId = store.insertEntry("jqlang", "jq", "latest", "jq", false);
        ruleId = store.insertRule(entryId, "jq-windows-amd64.exe", null,
                AssetKind.BINARY, targetDir.toString(), null, 2, false, null);
        store.flush();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    // ──────────────────────── 构造辅助 ────────────────────────

    private static String sha256Of(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        return "sha256:" + HexFormat.of().formatHex(digest);
    }

    /** 造一个"下载完成"的任务：.part.ready 已就绪，状态 DONE。 */
    private DownloadTask makeDoneTask(String assetName, String content, String digestOverride)
            throws Exception {
        Path temp = targetDir.resolve(".githubupdater-tmp");
        Files.createDirectories(temp);
        Path part = temp.resolve(assetName + ".part");
        Path ready = temp.resolve(assetName + ".part.ready");
        Files.writeString(ready, content, StandardCharsets.UTF_8);

        String digest = digestOverride != null ? digestOverride : sha256Of(ready);

        long taskId = store.insertTask(ruleId, 100L, "jq-1.7.1", 555L, assetName,
                "https://example.invalid/" + assetName, (long) content.length(), digest,
                part.toString(), targetDir.resolve(assetName).toString());
        store.flush();
        store.setState(taskId, TaskState.DONE, null, null);
        store.flush();
        return store.findTask(taskId).orElseThrow();
    }

    private Entry entry() throws Exception {
        return store.findEntry(entryId).orElseThrow();
    }

    private AssetRule rule() throws Exception {
        return store.listRules(entryId).get(0);
    }

    // ──────────────────────── BINARY ────────────────────────

    @Test
    @DisplayName("BINARY：校验通过应替换目标并留下备份")
    void binaryReplaceSucceeds() throws Exception {
        Path target = targetDir.resolve("jq-windows-amd64.exe");
        Files.writeString(target, "OLD-BINARY", StandardCharsets.UTF_8);

        DownloadTask task = makeDoneTask("jq-windows-amd64.exe", "NEW-BINARY", null);
        ApplyService.Result r = service.apply(task, rule(), entry(), ApplyService.Options.defaults());

        assertTrue(r instanceof ApplyService.Applied, "应应用成功，实际：" + describe(r));
        ApplyService.Applied applied = (ApplyService.Applied) r;
        assertEquals(AssetKind.BINARY, applied.kind());
        assertTrue(applied.digestVerified(), "有 digest 时必须报告已验证");

        assertEquals("NEW-BINARY", Files.readString(target), "目标文件必须换成新内容");
        assertTrue(applied.backupPath() != null && Files.exists(applied.backupPath()),
                "必须留下备份");
        assertEquals("OLD-BINARY", Files.readString(applied.backupPath()));

        // 状态应推进到 APPLIED
        assertEquals(TaskState.APPLIED, store.findTask(task.id()).orElseThrow().state());

        // 备份记录必须落库，否则界面看不到可回滚项
        var backups = store.listBackups(ruleId, 10);
        assertEquals(1, backups.size());
        assertEquals(target.toString(), backups.get(0).filePath());
    }

    @Test
    @DisplayName("BINARY：应用前 digest 不匹配必须拒绝，绝不覆盖目标")
    void binaryRejectsDigestMismatch() throws Exception {
        Path target = targetDir.resolve("jq-windows-amd64.exe");
        Files.writeString(target, "OLD-BINARY", StandardCharsets.UTF_8);

        DownloadTask task = makeDoneTask("jq-windows-amd64.exe", "NEW-BINARY",
                "sha256:0000000000000000000000000000000000000000000000000000000000000000");

        ApplyService.Result r = service.apply(task, rule(), entry(), ApplyService.Options.defaults());

        assertTrue(r instanceof ApplyService.Failed, "应拒绝应用，实际：" + describe(r));
        assertEquals("DIGEST_MISMATCH", ((ApplyService.Failed) r).code());
        assertEquals("OLD-BINARY", Files.readString(target), "目标文件绝不能被改动");
    }

    @Test
    @DisplayName("BINARY：无 digest 且未确认应要求确认，确认后才应用")
    void binaryRequiresUnverifiedConfirm() throws Exception {
        Path target = targetDir.resolve("noverify.exe");

        // digest 为 null 表示上游未提供校验和
        Path temp = targetDir.resolve(".githubupdater-tmp");
        Files.createDirectories(temp);
        Path part = temp.resolve("noverify.exe.part");
        Path ready = temp.resolve("noverify.exe.part.ready");
        Files.writeString(ready, "NEW", StandardCharsets.UTF_8);

        long id = store.insertTask(ruleId, 100L, "jq-1.7.1", 556L, "noverify.exe",
                "https://example.invalid/x", 3L, null, part.toString(), target.toString());
        store.flush();
        store.setState(id, TaskState.DONE, null, null);
        store.flush();
        DownloadTask noDigest = store.findTask(id).orElseThrow();
        assertFalse(noDigest.hasDigest(), "前置条件：该任务没有 digest");

        ApplyService.Result first = service.apply(noDigest, rule(), entry(),
                ApplyService.Options.defaults());
        assertTrue(first instanceof ApplyService.NeedConfirm, "应先要求确认，实际：" + describe(first));
        assertFalse(Files.exists(target), "确认前不得写入");

        ApplyService.Result second = service.apply(noDigest, rule(), entry(),
                new ApplyService.Options(false, true));
        assertTrue(second instanceof ApplyService.Applied, "确认后应应用，实际：" + describe(second));
        assertEquals("NEW", Files.readString(target));
        assertFalse(((ApplyService.Applied) second).digestVerified(), "未校验时必须如实报告");
    }

    @Test
    @DisplayName("BINARY：暂存文件缺失应返回明确错误而不是抛异常")
    void binaryMissingReadyFile() throws Exception {
        long taskId = store.insertTask(ruleId, 100L, "v1", 1L, "ghost.exe",
                "https://example.invalid/x", 10L, null,
                targetDir.resolve(".githubupdater-tmp/ghost.exe.part").toString(),
                targetDir.resolve("ghost.exe").toString());
        store.flush();
        store.setState(taskId, TaskState.DONE, null, null);
        store.flush();

        ApplyService.Result r = service.apply(store.findTask(taskId).orElseThrow(),
                rule(), entry(), new ApplyService.Options(false, true));

        assertTrue(r instanceof ApplyService.Failed);
        assertTrue(((ApplyService.Failed) r).message().contains("暂存文件不存在"));
    }

    @Test
    @DisplayName("BINARY：非 DONE 状态的任务不允许应用")
    void rejectsNonDoneTask() throws Exception {
        DownloadTask task = makeDoneTask("jq-windows-amd64.exe", "X", null);
        store.setState(task.id(), TaskState.RUNNING, null, null);
        store.flush();

        ApplyService.Result r = service.apply(store.findTask(task.id()).orElseThrow(),
                rule(), entry(), new ApplyService.Options(false, true));

        assertTrue(r instanceof ApplyService.Failed);
        assertEquals("VALIDATION_FAILED", ((ApplyService.Failed) r).code());
    }

    // ──────────────────────── COLLECTION ────────────────────────

    @Test
    @DisplayName("COLLECTION：按模板落盘，并清理超出 keepVersions 的旧版本")
    void collectionRendersNameAndPrunes() throws Exception {
        Path apkDir = tempDir.resolve("apk");
        Files.createDirectories(apkDir);

        long rid = store.insertRule(entryId, "app-*.apk", null, AssetKind.COLLECTION,
                apkDir.toString(), "{name}-{version}.{ext}", 2, false, null);
        store.flush();

        // 先放三个历史版本
        for (String v : new String[]{"1.0", "1.1", "1.2"}) {
            Path p = apkDir.resolve("app-" + v + ".apk");
            Files.writeString(p, "old-" + v, StandardCharsets.UTF_8);
            Thread.sleep(10);   // 让 mtime 有区分度
        }

        Path temp = apkDir.resolve(".githubupdater-tmp");
        Files.createDirectories(temp);
        Path part = temp.resolve("app-1.3.apk.part");
        Path ready = temp.resolve("app-1.3.apk.part.ready");
        Files.writeString(ready, "new-apk", StandardCharsets.UTF_8);
        String digest = sha256Of(ready);

        long taskId = store.insertTask(rid, 200L, "1.3", 9L, "app-1.3.apk",
                "https://example.invalid/app.apk", 7L, digest,
                part.toString(), apkDir.resolve("app-1.3.apk").toString());
        store.flush();
        store.setState(taskId, TaskState.DONE, null, null);
        store.flush();

        AssetRule apkRule = store.listRules(entryId).stream()
                .filter(x -> x.id() == rid).findFirst().orElseThrow();

        ApplyService.Result r = service.apply(store.findTask(taskId).orElseThrow(),
                apkRule, entry(), ApplyService.Options.defaults());

        assertTrue(r instanceof ApplyService.Applied, "应落盘成功，实际：" + describe(r));
        Path dest = apkDir.resolve("app-1.3.apk");
        assertEquals("new-apk", Files.readString(dest), "文件名模板应生效");

        long remaining;
        try (var s = Files.list(apkDir)) {
            remaining = s.filter(p -> p.getFileName().toString().startsWith("app-")
                    && p.getFileName().toString().endsWith(".apk")).count();
        }
        assertTrue(remaining <= 2, "keepVersions=2 时应最多剩 2 个版本，实际 " + remaining);
    }

    // ──────────────────────── ARCHIVE ────────────────────────

    @Test
    @DisplayName("ARCHIVE：解压覆盖目标目录，保留用户的额外文件")
    void archiveExtractsInto() throws Exception {
        Path appDir = tempDir.resolve("app");
        Files.createDirectories(appDir);
        Files.writeString(appDir.resolve("keep.ini"), "user-config", StandardCharsets.UTF_8);

        long rid = store.insertRule(entryId, "*.zip", null, AssetKind.ARCHIVE,
                appDir.toString(), null, 2, false, null);
        store.flush();

        // 造一个真实 zip
        Path temp = appDir.resolve(".githubupdater-tmp");
        Files.createDirectories(temp);
        Path part = temp.resolve("bundle.zip.part");
        Path ready = temp.resolve("bundle.zip.part.ready");
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(ready))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("bin/app.exe"));
            zos.write("MZ-NEW".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("README.txt"));
            zos.write("docs".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        String digest = sha256Of(ready);

        long taskId = store.insertTask(rid, 300L, "v2", 77L, "bundle.zip",
                "https://example.invalid/b.zip", Files.size(ready), digest,
                part.toString(), appDir.resolve("bundle.zip").toString());
        store.flush();
        store.setState(taskId, TaskState.DONE, null, null);
        store.flush();

        AssetRule archiveRule = store.listRules(entryId).stream()
                .filter(x -> x.id() == rid).findFirst().orElseThrow();

        ApplyService.Result r = service.apply(store.findTask(taskId).orElseThrow(),
                archiveRule, entry(), ApplyService.Options.defaults());

        assertTrue(r instanceof ApplyService.Applied, "应解压成功，实际：" + describe(r));
        assertEquals("MZ-NEW", Files.readString(appDir.resolve("bin/app.exe")));
        assertEquals("docs", Files.readString(appDir.resolve("README.txt")));
        assertTrue(Files.exists(appDir.resolve("keep.ini")),
                "用户放在目标目录里的文件绝不能被删");
    }

    // ──────────────────────── 回滚 ────────────────────────

    @Test
    @DisplayName("回滚：应把备份内容恢复回目标路径")
    void rollbackRestoresPreviousVersion() throws Exception {
        Path target = targetDir.resolve("jq-windows-amd64.exe");
        Files.writeString(target, "V1", StandardCharsets.UTF_8);

        DownloadTask task = makeDoneTask("jq-windows-amd64.exe", "V2", null);
        ApplyService.Applied applied = (ApplyService.Applied)
                service.apply(task, rule(), entry(), ApplyService.Options.defaults());
        assertEquals("V2", Files.readString(target));

        var backups = store.listBackups(ruleId, 10);
        assertEquals(1, backups.size());

        ApplyService.Result rb = service.rollback(backups.get(0).id());
        assertTrue(rb instanceof ApplyService.Applied, "回滚应成功，实际：" + describe(rb));
        assertEquals("V1", Files.readString(target), "必须回到旧内容");

        store.flush();
        assertTrue(store.listBackups(ruleId, 10).isEmpty(), "回滚后备份记录应被消费掉");
    }

    @Test
    @DisplayName("回滚：备份文件被手工删除时应返回明确错误")
    void rollbackMissingBackupFile() throws Exception {
        Path target = targetDir.resolve("jq-windows-amd64.exe");
        Files.writeString(target, "V1", StandardCharsets.UTF_8);

        DownloadTask task = makeDoneTask("jq-windows-amd64.exe", "V2", null);
        ApplyService.Applied applied = (ApplyService.Applied)
                service.apply(task, rule(), entry(), ApplyService.Options.defaults());
        Files.delete(applied.backupPath());

        var backups = store.listBackups(ruleId, 10);
        ApplyService.Result rb = service.rollback(backups.get(0).id());

        assertTrue(rb instanceof ApplyService.Failed);
        assertEquals("NOT_FOUND", ((ApplyService.Failed) rb).code());
    }

    // ──────────────────────── 路径安全 ────────────────────────

    @Test
    @DisplayName("目标目录非法时应拒绝，不尝试写入")
    void rejectsUnsafeTargetDir() throws Exception {
        long rid = store.insertRule(entryId, "*.exe", null, AssetKind.BINARY,
                "C:\\Windows\\System32", null, 2, false, null);
        store.flush();

        long taskId = store.insertTask(rid, 400L, "v1", 1L, "evil.exe",
                "https://example.invalid/x", 4L, null,
                targetDir.resolve(".githubupdater-tmp/evil.exe.part").toString(),
                "C:\\Windows\\System32\\evil.exe");
        store.flush();
        store.setState(taskId, TaskState.DONE, null, null);
        store.flush();

        AssetRule bad = store.listRules(entryId).stream()
                .filter(x -> x.id() == rid).findFirst().orElseThrow();

        ApplyService.Result r = service.apply(store.findTask(taskId).orElseThrow(),
                bad, entry(), new ApplyService.Options(false, true));

        assertTrue(r instanceof ApplyService.Failed);
        assertEquals("DIR_NOT_WRITABLE", ((ApplyService.Failed) r).code());
    }

    // ──────────────────────── SOURCE ────────────────────────

    @Test
    @DisplayName("SOURCE：目标目录非法时应在 clone 前就拒绝")
    void sourceRejectsUnsafeDir() throws Exception {
        long rid = store.insertRule(entryId, "*", null, AssetKind.SOURCE,
                "C:\\Windows\\System32", null, 1, false, null);
        store.flush();
        AssetRule srcRule = store.listRules(entryId).stream()
                .filter(x -> x.id() == rid).findFirst().orElseThrow();

        ApplyService.Result r = service.applySource(srcRule, entry(), "jq-1.8.2");

        assertTrue(r instanceof ApplyService.Failed);
        assertEquals("DIR_NOT_WRITABLE", ((ApplyService.Failed) r).code());
    }

    @Test
    @DisplayName("SOURCE：tag 为空时应返回失败而不是静默什么都不做")
    void sourceRejectsBlankTag() throws Exception {
        Path repoDir = tempDir.resolve("srcrepo");
        Files.createDirectories(repoDir);
        long rid = store.insertRule(entryId, "*", null, AssetKind.SOURCE,
                repoDir.toString(), null, 1, false, null);
        store.flush();
        AssetRule srcRule = store.listRules(entryId).stream()
                .filter(x -> x.id() == rid).findFirst().orElseThrow();

        ApplyService.Result r = service.applySource(srcRule, entry(), "");

        assertTrue(r instanceof ApplyService.Failed, "应失败，实际：" + describe(r));
        assertTrue(((ApplyService.Failed) r).message().contains("未指定"),
                "错误信息应说明未指定 tag");
    }

    @Test
    @DisplayName("SOURCE：目标目录已存在且非空但不是 git 仓库时，应给出可操作的错误而不是崩")
    void sourceRejectsNonEmptyNonGitDir() throws Exception {
        Path repoDir = tempDir.resolve("not-a-repo");
        Files.createDirectories(repoDir);
        Files.writeString(repoDir.resolve("something.txt"), "user data", StandardCharsets.UTF_8);

        long rid = store.insertRule(entryId, "*", null, AssetKind.SOURCE,
                repoDir.toString(), null, 1, false, null);
        store.flush();
        AssetRule srcRule = store.listRules(entryId).stream()
                .filter(x -> x.id() == rid).findFirst().orElseThrow();

        ApplyService.Result r = service.applySource(srcRule, entry(), "jq-1.8.2");

        assertTrue(r instanceof ApplyService.Failed, "应失败，实际：" + describe(r));
        ApplyService.Failed f = (ApplyService.Failed) r;
        assertEquals("GIT_FAILED", f.code());
        assertTrue(f.message().contains("非空"), "错误信息应说明目录非空：" + f.message());
        assertEquals("user data", Files.readString(repoDir.resolve("something.txt")),
                "用户文件绝不能被删");
    }

    private static String describe(ApplyService.Result r) {
        if (r instanceof ApplyService.Failed f) {
            return "Failed(" + f.code() + ": " + f.message() + ")";
        }
        if (r instanceof ApplyService.Applied a) {
            return "Applied(" + a.message() + ")";
        }
        if (r instanceof ApplyService.Locked l) {
            return "Locked(" + l.processes().size() + " 个进程)";
        }
        return String.valueOf(r);
    }
}
