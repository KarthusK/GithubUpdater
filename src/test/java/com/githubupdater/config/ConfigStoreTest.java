package com.githubupdater.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置读写与容错。 */
class ConfigStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("首次加载应生成默认配置文件")
    void createsDefaultConfig() {
        ConfigStore store = new ConfigStore(tempDir);
        AppConfig config = store.load();

        assertNotNull(config);
        assertEquals(AppConfig.CURRENT_SCHEMA, config.schemaVersion);
        assertFalse(config.hasToken(), "默认不应配置 token");
        assertFalse(config.mirrorEnabled, "镜像默认必须关闭");
        assertTrue(Files.exists(store.path()), "应写出 config.json");
    }

    @Test
    @DisplayName("保存后重新加载应保持字段")
    void roundTrip() {
        ConfigStore store = new ConfigStore(tempDir);
        AppConfig config = store.load();
        config.githubToken = "ghp_test123";
        config.mirrorEnabled = true;
        config.mirrorPrefix = "https://ghfast.top/";
        config.maxParallelTasks = 3;
        store.save(config);

        AppConfig reloaded = new ConfigStore(tempDir).load();
        assertTrue(reloaded.hasToken());
        assertEquals("ghp_test123", reloaded.githubToken);
        assertTrue(reloaded.mirrorEnabled);
        assertEquals("https://ghfast.top/", reloaded.mirrorPrefix);
        assertEquals(3, reloaded.maxParallelTasks);
    }

    @Test
    @DisplayName("损坏的配置应被备份而非静默覆盖")
    void brokenConfigIsBackedUp() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        Files.createDirectories(tempDir);
        Files.writeString(store.path(), "{ this is not json ");

        AppConfig config = store.load();

        assertNotNull(config);
        assertFalse(config.hasToken());
        // 应存在 .broken.* 备份
        boolean hasBroken = Files.list(tempDir)
                .anyMatch(p -> p.getFileName().toString().contains(".broken."));
        assertTrue(hasBroken, "损坏的配置应被留档");
    }

    @Test
    @DisplayName("未知字段应被忽略（向前兼容）")
    void unknownFieldsIgnored() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        Files.createDirectories(tempDir);
        Files.writeString(store.path(), """
                {
                  "schemaVersion": 1,
                  "futureFieldWeDontKnow": "whatever",
                  "maxParallelTasks": 4
                }
                """);

        AppConfig config = store.load();
        assertEquals(4, config.maxParallelTasks);
    }

    @Test
    @DisplayName("非法取值应被归一化")
    void valuesNormalized() {
        ConfigStore store = new ConfigStore(tempDir);
        AppConfig config = store.load();
        config.maxParallelTasks = 0;
        config.anonymousCheckBatchLimit = -5;
        config.keepBackups = -1;

        AppConfig normalized = config.normalized();
        assertTrue(normalized.maxParallelTasks >= 1);
        assertTrue(normalized.anonymousCheckBatchLimit >= 1);
        assertTrue(normalized.keepBackups >= 0);
    }

    @Test
    @DisplayName("有 token 时检查上限为无限，无 token 时受配置限制")
    void effectiveCheckLimit() {
        AppConfig config = new AppConfig();
        config.anonymousCheckBatchLimit = 50;

        assertNull(config.githubToken);
        assertEquals(50, config.effectiveCheckLimit());

        config.githubToken = "ghp_x";
        assertEquals(Integer.MAX_VALUE, config.effectiveCheckLimit());
    }

    @Test
    @DisplayName("来自更新版本的配置应能加载并告警")
    void newerSchemaLoads() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        Files.createDirectories(tempDir);
        Files.writeString(store.path(), """
                { "schemaVersion": 99, "maxParallelTasks": 2 }
                """);

        AppConfig config = store.load();
        assertEquals(99, config.schemaVersion);
        assertEquals(2, config.maxParallelTasks);
    }

    // ── 自定义 CA ───────────────────────────────────────

    @Test
    @DisplayName("customCaFile 应能往返保存")
    void customCaRoundTrip() {
        ConfigStore store = new ConfigStore(tempDir);
        AppConfig config = store.load();
        config.customCaFile = "D:\\tools\\proxy-ca.pem";
        store.save(config);

        AppConfig reloaded = new ConfigStore(tempDir).load();
        assertEquals("D:\\tools\\proxy-ca.pem", reloaded.customCaFile);
    }

    @Test
    @DisplayName("空白的 customCaFile 应归一为 null，而不是空串")
    void blankCustomCaBecomesNull() {
        AppConfig config = new AppConfig();
        config.customCaFile = "   ";
        config.normalized();

        // 空串会被当成"配置了一个空路径"，界面显示成"已配置但加载失败"，
        // 与"未配置"混淆。归一为 null 才能正确表达"没配"。
        assertNull(config.customCaFile);
    }

    @Test
    @DisplayName("旧配置没有 customCaFile 字段时应加载为 null")
    void oldConfigWithoutCustomCa() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        Files.createDirectories(tempDir);
        Files.writeString(store.path(), """
                { "schemaVersion": 1, "maxParallelTasks": 2 }
                """);

        AppConfig config = store.load();
        assertNull(config.customCaFile);
    }

    @Test
    @DisplayName("相对路径应按数据目录解析，绝对路径原样使用")
    void resolvesCustomCaPath() {
        AppConfig config = new AppConfig();
        Path dataDir = Path.of("D:\\app\\data");

        config.customCaFile = "certs\\proxy.pem";
        assertEquals(Path.of("D:\\app\\data\\certs\\proxy.pem"),
                config.resolveCustomCaFile(dataDir),
                "相对路径应指向数据目录，而不是进程工作目录");

        config.customCaFile = "E:\\other\\ca.pem";
        assertEquals(Path.of("E:\\other\\ca.pem"), config.resolveCustomCaFile(dataDir));

        config.customCaFile = null;
        assertNull(config.resolveCustomCaFile(dataDir));
    }
}
