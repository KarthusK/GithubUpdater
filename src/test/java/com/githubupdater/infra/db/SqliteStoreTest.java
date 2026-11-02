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
}
