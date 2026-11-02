package com.githubupdater.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 配置读写与迁移。
 *
 * <p>行为约定：
 * <ul>
 *   <li>配置文件不存在 → 生成默认配置</li>
 *   <li>schemaVersion 落后 → 先备份再迁移</li>
 *   <li>反序列化失败 → 备份损坏文件后用默认配置启动（<b>不静默覆盖</b>）</li>
 * </ul>
 */
public final class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private static final String FILE_NAME = "config.json";

    private final Path configPath;
    private final ObjectMapper mapper;

    public ConfigStore(Path dataDir) {
        this.configPath = dataDir.resolve(FILE_NAME);
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                // 容忍未来版本新增的字段：旧程序读新配置不应崩
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public Path path() {
        return configPath;
    }

    /** 加载配置。任何异常都不阻断启动，而是回退到默认值。 */
    public AppConfig load() {
        if (!Files.exists(configPath)) {
            log.info("配置文件不存在，生成默认配置：{}", configPath);
            AppConfig config = new AppConfig();
            save(config);
            return config;
        }

        try {
            AppConfig config = mapper.readValue(configPath.toFile(), AppConfig.class);
            if (config == null) {
                config = new AppConfig();
            }
            config = migrate(config);
            return config.normalized();
        } catch (IOException e) {
            // 不静默覆盖：把损坏的文件留档，用户可自行检查
            Path broken = configPath.resolveSibling(
                    FILE_NAME + ".broken." + System.currentTimeMillis());
            try {
                Files.move(configPath, broken, StandardCopyOption.REPLACE_EXISTING);
                log.error("配置解析失败，已备份为 {}，使用默认配置启动", broken.getFileName());
            } catch (IOException moveError) {
                log.error("配置解析失败且备份失败，使用默认配置启动", e);
            }
            AppConfig config = new AppConfig();
            save(config);
            return config;
        }
    }

    /** 保存配置。先写临时文件再原子替换，避免写一半导致配置损坏。 */
    public void save(AppConfig config) {
        try {
            Files.createDirectories(configPath.getParent());
            Path tmp = configPath.resolveSibling(FILE_NAME + ".tmp");
            mapper.writeValue(tmp.toFile(), config.normalized());
            try {
                Files.move(tmp, configPath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // 跨卷等场景退化
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存配置失败", e);
        }
    }

    /**
     * 配置迁移。
     *
     * <p>当前只有 v1，无实际迁移逻辑。保留框架是为了以后加字段时
     * 能按版本号补齐默认值，而不是靠"字段为 null"逐个判断。
     */
    private AppConfig migrate(AppConfig config) {
        if (config.schemaVersion < AppConfig.CURRENT_SCHEMA) {
            log.info("配置 schema v{} -> v{}", config.schemaVersion, AppConfig.CURRENT_SCHEMA);
            backupBeforeMigration(config.schemaVersion);
            // 未来的迁移分支写在这里
            config.schemaVersion = AppConfig.CURRENT_SCHEMA;
            save(config);
        } else if (config.schemaVersion > AppConfig.CURRENT_SCHEMA) {
            log.warn("配置文件来自更新的版本（v{}），部分字段可能被忽略", config.schemaVersion);
        }
        return config;
    }

    private void backupBeforeMigration(int fromVersion) {
        try {
            Path backup = configPath.resolveSibling(FILE_NAME + ".bak.v" + fromVersion);
            Files.copy(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
            log.info("配置迁移前已备份：{}", backup.getFileName());
        } catch (IOException e) {
            log.warn("配置备份失败（继续迁移）：{}", e.getMessage());
        }
    }
}
