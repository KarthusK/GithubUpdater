package com.githubupdater.infra.db;

import com.githubupdater.domain.AssetKind;
import com.githubupdater.domain.AssetRule;
import com.githubupdater.domain.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQLite 存储层测试，含迁移与外键约束。 */
class SqliteStoreTest {

    @TempDir
    Path tempDir;

    private SqliteStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = SqliteStore.open(tempDir);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    @DisplayName("新建数据库应创建所有表并写入 schemaVersion")
    void createsSchema() throws Exception {
        assertEquals(String.valueOf(Migrations.LATEST_VERSION),
                store.getMeta("schemaVersion").orElseThrow());
        assertTrue(store.integrityCheck());
    }

    @Test
    @DisplayName("重复打开同一数据库不应重复迁移")
    void migrationIsIdempotent() throws Exception {
        store.close();
        store = SqliteStore.open(tempDir);

        assertEquals(String.valueOf(Migrations.LATEST_VERSION),
                store.getMeta("schemaVersion").orElseThrow());
        assertTrue(store.integrityCheck());
    }

    @Test
    @DisplayName("插入条目后应能读回，且字段完整")
    void insertAndReadEntry() throws Exception {
        long id = store.insertEntry("jqlang", "jq", Entry.CHANNEL_LATEST, "jq 工具", false);
        store.flush();

        Optional<Entry> found = store.findEntry(id);
        assertTrue(found.isPresent());
        Entry e = found.get();
        assertEquals("jqlang", e.owner());
        assertEquals("jq", e.repo());
        assertEquals("jq 工具", e.displayName());
        assertFalse(e.isPrivate());
        assertEquals("jqlang/jq", e.fullName());
    }

    @Test
    @DisplayName("同一 owner/repo 重复插入应失败（UNIQUE 约束）")
    void duplicateEntryRejected() throws Exception {
        store.insertEntry("a", "b", Entry.CHANNEL_LATEST, "a/b", false);
        store.flush();

        // 写入线程内的异常不会传播到调用方，验证数据库层确实拒绝了
        boolean stillOne;
        try {
            store.insertEntry("a", "b", Entry.CHANNEL_LATEST, "a/b", false);
            store.flush();
            Thread.sleep(200);
            stillOne = store.listEntries().size() == 1;
        } catch (IllegalStateException e) {
            stillOne = true;
        }
        assertTrue(stillOne, "重复条目不应被插入");
    }

    @Test
    @DisplayName("更新检查状态应回写 release id 与 ETag")
    void updateEntryChecked() throws Exception {
        long id = store.insertEntry("o", "r", Entry.CHANNEL_LATEST, "o/r", false);
        store.updateEntryChecked(id, 42L, "v1.0.0", "W/\"abc\"");
        store.flush();

        Entry e = store.findEntry(id).orElseThrow();
        assertEquals(42L, e.lastSeenReleaseId());
        assertEquals("v1.0.0", e.lastSeenTag());
        assertEquals("W/\"abc\"", e.lastEtag());
        assertNotNull(e.lastCheckedAt());
    }

    @Test
    @DisplayName("touchEntryCheckedAt 只更新时间，不动 release id")
    void touchDoesNotClearReleaseId() throws Exception {
        long id = store.insertEntry("o", "r", Entry.CHANNEL_LATEST, "o/r", false);
        store.updateEntryChecked(id, 42L, "v1", "etag1");
        store.flush();
        store.touchEntryCheckedAt(id);
        store.flush();

        Entry e = store.findEntry(id).orElseThrow();
        assertEquals(42L, e.lastSeenReleaseId(), "304 时不应清空已有 release id");
        assertEquals("v1", e.lastSeenTag());
    }

    @Test
    @DisplayName("删除条目应级联删除其规则")
    void deleteEntryCascades() throws Exception {
        long entryId = store.insertEntry("o", "r", Entry.CHANNEL_LATEST, "o/r", false);
        store.insertRule(entryId, "*.exe", null, AssetKind.BINARY,
                "D:\\tools", null, 2, false, null);
        store.flush();

        assertEquals(1, store.listRules(entryId).size());

        store.deleteEntry(entryId);
        store.flush();

        assertTrue(store.findEntry(entryId).isEmpty());
        assertEquals(0, store.listRules(entryId).size(), "规则应被级联删除");
    }

    @Test
    @DisplayName("规则的 kind 与字段应正确往返")
    void ruleRoundTrip() throws Exception {
        long entryId = store.insertEntry("o", "r", Entry.CHANNEL_LATEST, "o/r", false);
        long ruleId = store.insertRule(entryId, "*-win-x64.zip", "*arm64*", AssetKind.ARCHIVE,
                "D:\\tools\\app", "{name}-{version}.{ext}", 3, true, "echo done");
        store.flush();

        List<AssetRule> rules = store.listRules(entryId);
        assertEquals(1, rules.size());
        AssetRule r = rules.get(0);
        assertEquals(ruleId, r.id());
        assertEquals("*-win-x64.zip", r.includeGlob());
        assertEquals("*arm64*", r.excludeGlob());
        assertEquals(AssetKind.ARCHIVE, r.kind());
        assertEquals(3, r.keepVersions());
        assertTrue(r.autoApply());
        assertEquals("echo done", r.afterApplyCmd());
        assertTrue(r.enabled());
    }

    @Test
    @DisplayName("删除规则不应影响条目")
    void deleteRuleKeepsEntry() throws Exception {
        long entryId = store.insertEntry("o", "r", Entry.CHANNEL_LATEST, "o/r", false);
        long ruleId = store.insertRule(entryId, "*.exe", null, AssetKind.BINARY,
                "D:\\tools", null, 1, false, null);
        store.flush();

        store.deleteRule(ruleId);
        store.flush();

        assertEquals(0, store.listRules(entryId).size());
        assertTrue(store.findEntry(entryId).isPresent());
    }

    @Test
    @DisplayName("关闭后重新打开，数据仍然存在")
    void dataPersistsAcrossReopen() throws Exception {
        long id = store.insertEntry("persist", "check", Entry.CHANNEL_LATEST, "p/c", false);
        store.updateEntryChecked(id, 999L, "v9", "e9");
        store.flush();
        store.close();

        store = SqliteStore.open(tempDir);
        Entry e = store.findEntry(id).orElseThrow();
        assertEquals(999L, e.lastSeenReleaseId());
        assertEquals("v9", e.lastSeenTag());
    }

    @Test
    @DisplayName("WAL 模式应实际启用")
    void walModeEnabled() throws Exception {
        // 通过检查数据目录里是否出现 -wal 文件间接验证（写入后才会创建）
        store.insertEntry("w", "m", Entry.CHANNEL_LATEST, "w/m", false);
        store.flush();
        Thread.sleep(100);
        boolean hasWal = Files.list(tempDir)
                .anyMatch(p -> p.getFileName().toString().contains("-wal"));
        assertTrue(hasWal, "WAL 模式下写入后应产生 -wal 文件");
    }

    // ── v2：检查失败状态 ────────────────────────────────

    @Test
    @DisplayName("记录检查失败时应落库原因、签发者，并同时更新检查时间")
    void recordsCheckFailure() throws Exception {
        long id = store.insertEntry("o", "r", Entry.CHANNEL_LATEST, "o/r", false);
        store.flush();

        store.updateEntryCheckFailed(id, "ERROR",
                "TLS 证书链不受信任，检测到本机有中间人在解密 HTTPS",
                "CN=steamcommunity.com, O=Steamcommunity302, C=CN");
        store.flush();

        Entry e = store.findEntry(id).orElseThrow();
        assertEquals("ERROR", e.lastCheckStatus());
        assertTrue(e.lastCheckError().contains("中间人"), "失败原因应完整保留");
        assertEquals("CN=steamcommunity.com, O=Steamcommunity302, C=CN", e.lastTlsIssuer());
        // 关键：失败也是一次真实的检查。若这里为 null，界面会显示"从未检查"
        // 却同时显示一条失败原因，自相矛盾。
        assertNotNull(e.lastCheckedAt(), "失败也必须更新 last_checked_at");
        assertNull(e.lastSeenReleaseId(), "失败不应写入 release id");
    }

    @Test
    @DisplayName("检查成功后应清掉上一次的失败信息")
    void clearsFailureOnSuccess() throws Exception {
        long id = store.insertEntry("o", "r", Entry.CHANNEL_LATEST, "o/r", false);
        store.updateEntryCheckFailed(id, "ERROR", "网络请求失败", "CN=某中间人");
        store.flush();

        assertEquals("ERROR", store.findEntry(id).orElseThrow().lastCheckStatus());

        store.updateEntryCheckOk(id, "OK");
        store.flush();

        Entry e = store.findEntry(id).orElseThrow();
        assertEquals("OK", e.lastCheckStatus());
        // 清空是必须的：否则用户修好网络后重新检查成功，
        // 界面还挂着上一次的失败原因，会让人以为问题没解决。
        assertNull(e.lastCheckError(), "成功后必须清掉旧的失败原因");
        assertNull(e.lastTlsIssuer(), "成功后必须清掉旧的签发者");
        assertNotNull(e.lastCheckedAt());
    }

    @Test
    @DisplayName("v1 数据库应能迁移到 v2，且原有数据不丢")
    void migratesFromV1() throws Exception {
        store.close();
        Files.deleteIfExists(tempDir.resolve("state.db"));
        Files.deleteIfExists(tempDir.resolve("state.db-wal"));
        Files.deleteIfExists(tempDir.resolve("state.db-shm"));

        // 手工造一个 v1 库：只跑第一个迁移，写入数据，再交给 SqliteStore 升级
        Path dbPath = tempDir.resolve("state.db");
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            try (var st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                for (String sql : Migrations.ALL.get(0).statements()) {
                    st.execute(sql);
                }
                st.execute("INSERT INTO app_meta(key, value) VALUES('schemaVersion', '1')");
                st.execute("INSERT INTO entry(owner, repo, channel, display_name, is_private, "
                        + "created_at) VALUES('old','repo','latest','old/repo',0, 111)");
            }
        }

        store = SqliteStore.open(tempDir);

        assertEquals(String.valueOf(Migrations.LATEST_VERSION),
                store.getMeta("schemaVersion").orElseThrow());
        var entries = store.listEntries();
        assertEquals(1, entries.size(), "迁移不应丢数据");
        Entry e = entries.get(0);
        assertEquals("old", e.owner());
        // 新增列在旧数据上是 null，语义正好是"从未记录过失败"
        assertNull(e.lastCheckStatus());
        assertNull(e.lastTlsIssuer());
    }

    // ── v3：规则资产缓存 ────────────────────────────────

    @Test
    @DisplayName("缓存资产后应能完整读回，且 hasCachedAsset 为真")
    void cachesMatchedAsset() throws Exception {
        long entryId = store.insertEntry("o", "r", Entry.CHANNEL_LATEST, "o/r", false);
        long ruleId = store.insertRule(entryId, "*.apk", null, AssetKind.COLLECTION,
                "E:\\APK", null, 2, false, null);
        store.flush();

        var asset = new com.githubupdater.infra.github.ReleaseDto.Asset(
                123L, "LocalSend-1.18.2-android-arm64v8.apk", 46558706L,
                "sha256:82ec3568fba2aa", "https://github.com/x/y/releases/download/v1.18.2/a.apk");
        store.updateRuleAssetCache(ruleId, asset, 374422528L, "v1.18.2");
        store.flush();

        AssetRule r = store.listRules(entryId).get(0);
        assertTrue(r.hasCachedAsset());
        assertTrue(r.hasBeenChecked());
        assertEquals("LocalSend-1.18.2-android-arm64v8.apk", r.cachedAssetName());
        assertEquals(123L, r.cachedAssetId());
        assertEquals(46558706L, r.cachedAssetSize());
        assertEquals("sha256:82ec3568fba2aa", r.cachedAssetDigest());
        assertEquals("https://github.com/x/y/releases/download/v1.18.2/a.apk",
                r.cachedAssetUrl());
        assertEquals(374422528L, r.cachedReleaseId());
        assertEquals("v1.18.2", r.cachedReleaseTag());
        assertNotNull(r.cachedAt());
    }

    @Test
    @DisplayName("检查过但没匹配到：应保留 cached_at，且与从未检查可区分")
    void checkedButNotMatchedIsDistinguishable() throws Exception {
        long entryId = store.insertEntry("o", "r", Entry.CHANNEL_LATEST, "o/r", false);
        long ruleId = store.insertRule(entryId, "*.apk", null, AssetKind.COLLECTION,
                "E:\\APK", null, 1, false, null);
        store.flush();

        // 从未检查
        AssetRule before = store.listRules(entryId).get(0);
        assertFalse(before.hasBeenChecked(), "刚建的规则不该算检查过");
        assertFalse(before.hasCachedAsset());

        // 检查了但没匹配到（asset 传 null）
        store.updateRuleAssetCache(ruleId, null, 1L, "v1");
        store.flush();

        AssetRule after = store.listRules(entryId).get(0);
        // 这条是本次修复的核心：必须能区分"检查过没匹配"与"从未检查"，
        // 否则界面只能给同一句提示，用户会反复点检查却毫无变化。
        assertTrue(after.hasBeenChecked(), "检查过即使没匹配到也要留下痕迹");
        assertFalse(after.hasCachedAsset(), "没有资产时不该说可下载");
        assertNull(after.cachedAssetName());
        assertNull(after.cachedAssetUrl());
        assertEquals("v1", after.cachedReleaseTag());
    }

    @Test
    @DisplayName("重新检查匹配失败时，应清掉上一次的旧资产")
    void recheckClearsStaleAsset() throws Exception {
        long entryId = store.insertEntry("o", "r", Entry.CHANNEL_LATEST, "o/r", false);
        long ruleId = store.insertRule(entryId, "*.apk", null, AssetKind.COLLECTION,
                "E:\\APK", null, 1, false, null);
        store.flush();

        var asset = new com.githubupdater.infra.github.ReleaseDto.Asset(
                1L, "old.apk", 10L, null, "https://example.com/old.apk");
        store.updateRuleAssetCache(ruleId, asset, 1L, "v1");
        store.flush();
        assertTrue(store.listRules(entryId).get(0).hasCachedAsset());

        // 新版本里删掉了这个资产
        store.updateRuleAssetCache(ruleId, null, 2L, "v2");
        store.flush();

        AssetRule r = store.listRules(entryId).get(0);
        // 不清的话，界面会拿上一次的 URL 去下一个已经不存在的文件
        assertFalse(r.hasCachedAsset(), "旧的资产信息必须被清掉");
        assertNull(r.cachedAssetUrl());
        assertEquals("v2", r.cachedReleaseTag());
    }

    @Test
    @DisplayName("v2 数据库应能迁移到 v3，旧规则视为从未检查")
    void migratesFromV2() throws Exception {
        store.close();
        Files.deleteIfExists(tempDir.resolve("state.db"));
        Files.deleteIfExists(tempDir.resolve("state.db-wal"));
        Files.deleteIfExists(tempDir.resolve("state.db-shm"));

        Path dbPath = tempDir.resolve("state.db");
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            try (var st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                for (String sql : Migrations.ALL.get(0).statements()) {
                    st.execute(sql);
                }
                for (String sql : Migrations.ALL.get(1).statements()) {
                    st.execute(sql);
                }
                st.execute("INSERT INTO app_meta(key, value) VALUES('schemaVersion', '2')");
                st.execute("INSERT INTO entry(owner, repo, channel, display_name, is_private, "
                        + "created_at) VALUES('o','r','latest','o/r',0, 111)");
                st.execute("INSERT INTO asset_rule(entry_id, include_glob, kind, target_dir, "
                        + "created_at) VALUES(1,'*.apk','COLLECTION','E:\\APK', 222)");
            }
        }

        store = SqliteStore.open(tempDir);

        assertEquals(String.valueOf(Migrations.LATEST_VERSION),
                store.getMeta("schemaVersion").orElseThrow());
        var rules = store.listRules(store.listEntries().get(0).id());
        assertEquals(1, rules.size(), "迁移不应丢数据");
        AssetRule r = rules.get(0);
        assertEquals("*.apk", r.includeGlob());
        // 旧数据在 v3 新列上全是 NULL，语义正好是"从未检查"
        assertFalse(r.hasBeenChecked());
        assertFalse(r.hasCachedAsset());
    }
}
