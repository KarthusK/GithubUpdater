package com.githubupdater.infra.db;

import com.githubupdater.domain.AssetKind;
import com.githubupdater.domain.AssetRule;
import com.githubupdater.domain.DownloadTask;
import com.githubupdater.domain.Entry;
import com.githubupdater.domain.FileBackup;
import com.githubupdater.domain.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 存储。
 *
 * <p>关键约束（见 AGENTS.md 数据库一节）：
 * <ul>
 *   <li>{@code PRAGMA foreign_keys = ON} 是<b>连接级</b>设置，每条新连接都要执行</li>
 *   <li>所有写操作必须走 {@link DbWriter} 串行化</li>
 *   <li>迁移前自动备份数据库；迁移失败拒绝启动</li>
 * </ul>
 */
public final class SqliteStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteStore.class);

    /** 迁移前备份的保留数量。 */
    private static final int MAX_BACKUPS = 3;

    private final Path dbPath;
    private final Connection conn;
    private final DbWriter writer;

    private SqliteStore(Path dbPath, Connection conn) {
        this.dbPath = dbPath;
        this.conn = conn;
        this.writer = new DbWriter();
    }

    /**
     * 打开（或创建）数据库，并执行必要的迁移。
     *
     * @param dataDir 数据目录
     * @throws SQLException 打开或迁移失败。迁移失败时调用方应拒绝启动。
     */
    public static SqliteStore open(Path dataDir) throws SQLException {
        try {
            Files.createDirectories(dataDir);
        } catch (java.io.IOException e) {
            throw new SQLException("无法创建数据目录: " + dataDir, e);
        }
        Path dbPath = dataDir.resolve("state.db");

        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        applyPragmas(conn);

        int current = readSchemaVersion(conn);
        List<Migrations.Migration> pending = Migrations.pending(current);

        if (!pending.isEmpty()) {
            log.info("数据库 schema v{} -> v{}，共 {} 个迁移", current, Migrations.LATEST_VERSION, pending.size());
            backupBeforeMigration(dbPath, current);
            runMigrations(conn, pending);
        }

        return new SqliteStore(dbPath, conn);
    }

    /** 每条连接都必须执行。特别注意 foreign_keys 默认是关闭的。 */
    private static void applyPragmas(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = NORMAL");
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA foreign_keys = ON");
        }
    }

    private static int readSchemaVersion(Connection conn) throws SQLException {
        // schema_version 表可能不存在（全新库）
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='app_meta'")) {
            if (!rs.next()) {
                return 0;
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM app_meta WHERE key='schemaVersion'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        }
    }

    private static void backupBeforeMigration(Path dbPath, int fromVersion) {
        if (fromVersion == 0 || !Files.exists(dbPath)) {
            return;
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now());
        Path backup = dbPath.resolveSibling("state.db.bak.v" + fromVersion + "." + stamp);
        try {
            Files.copy(dbPath, backup, StandardCopyOption.REPLACE_EXISTING);
            log.info("迁移前已备份数据库：{}", backup.getFileName());
            pruneOldBackups(dbPath.getParent());
        } catch (Exception e) {
            log.warn("迁移前备份失败（继续执行迁移）：{}", e.getMessage());
        }
    }

    private static void pruneOldBackups(Path dir) {
        try (var stream = Files.list(dir)) {
            List<Path> backups = stream
                    .filter(p -> p.getFileName().toString().startsWith("state.db.bak."))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .toList();
            for (int i = MAX_BACKUPS; i < backups.size(); i++) {
                Files.deleteIfExists(backups.get(i));
            }
        } catch (Exception e) {
            log.debug("清理旧备份失败：{}", e.getMessage());
        }
    }

    /** 每个迁移在单个事务内完成，失败整体回滚并抛出，调用方应拒绝启动。 */
    private static void runMigrations(Connection conn, List<Migrations.Migration> pending) throws SQLException {
        for (Migrations.Migration m : pending) {
            log.info("执行迁移 v{}: {}", m.version(), m.description());
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String sql : m.statements()) {
                    st.execute(sql);
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO app_meta(key, value) VALUES('schemaVersion', ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                    ps.setString(1, String.valueOf(m.version()));
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new SQLException("迁移 v" + m.version() + " 失败，已回滚", e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // ──────────────────────────── 条目 ────────────────────────────

    public List<Entry> listEntries() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, owner, repo, channel, display_name, is_private, " +
                "last_seen_release_id, last_seen_tag, last_etag, last_checked_at, created_at, " +
                "last_check_status, last_check_error, last_tls_issuer " +
                "FROM entry ORDER BY created_at DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<Entry> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapEntry(rs));
                }
                return list;
            }
        }
    }

    public Optional<Entry> findEntry(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, owner, repo, channel, display_name, is_private, " +
                "last_seen_release_id, last_seen_tag, last_etag, last_checked_at, created_at, " +
                "last_check_status, last_check_error, last_tls_issuer " +
                "FROM entry WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapEntry(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Entry> findEntry(String owner, String repo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, owner, repo, channel, display_name, is_private, " +
                "last_seen_release_id, last_seen_tag, last_etag, last_checked_at, created_at, " +
                "last_check_status, last_check_error, last_tls_issuer " +
                "FROM entry WHERE owner = ? AND repo = ?")) {
            ps.setString(1, owner);
            ps.setString(2, repo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapEntry(rs)) : Optional.empty();
            }
        }
    }

    /**
     * 新增条目，返回自增 id。
     *
     * <p>用 {@code submitAndGet} 同步等待：异步提交做不到"插入后立刻拿到 id"，
     * 因为任务还没执行调用方就已经读结果了。
     */
    public long insertEntry(String owner, String repo, String channel,
                            String displayName, boolean isPrivate) {
        return writer.submitAndGet(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO entry(owner, repo, channel, display_name, is_private, created_at) " +
                    "VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, owner);
                ps.setString(2, repo);
                ps.setString(3, channel);
                ps.setString(4, displayName);
                ps.setInt(5, isPrivate ? 1 : 0);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("插入条目失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 写回检查结果。
     *
     * <p>只更新 last_seen_release_id / last_seen_tag / last_etag / last_checked_at 四列，
     * 不动其它字段。
     */
    public void updateEntryChecked(long entryId, long releaseId, String tag, String etag) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE entry SET last_seen_release_id = ?, last_seen_tag = ?, " +
                    "last_etag = ?, last_checked_at = ? WHERE id = ?")) {
                ps.setLong(1, releaseId);
                ps.setString(2, tag);
                ps.setString(3, etag);
                ps.setLong(4, System.currentTimeMillis());
                ps.setLong(5, entryId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新条目检查状态失败", e);
            }
        });
    }

    /** 只更新检查时间（304 或失败时用），保留已有的 release id 与 etag。 */
    public void touchEntryCheckedAt(long entryId) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE entry SET last_checked_at = ? WHERE id = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, entryId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新检查时间失败", e);
            }
        });
    }

    /**
     * 记录一次<b>干净的</b>检查结局（成功或无变化）。
     *
     * <p>必须把失败字段清空：否则用户修好网络后重新检查成功，界面却还挂着
     * 上一次的失败原因，会让人以为问题没解决。
     */
    public void updateEntryCheckOk(long entryId, String status) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE entry SET last_checked_at = ?, last_check_status = ?, " +
                    "last_check_error = NULL, last_tls_issuer = NULL WHERE id = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, status);
                ps.setLong(3, entryId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新检查状态失败", e);
            }
        });
    }

    /**
     * 记录一次检查失败。
     *
     * <p>同时更新 {@code last_checked_at}：失败也是一次真实的检查。
     * 若只写错误而不更新时间，界面会显示"从未检查"并附一条失败原因，
     * 自相矛盾，也会让人怀疑数据不准。
     *
     * @param entryId   条目
     * @param status    结局标记（通常为 ERROR）
     * @param message   给人看的原因
     * @param tlsIssuer TLS 不受信任时的证书签发者；非 TLS 失败传 null
     */
    public void updateEntryCheckFailed(long entryId, String status, String message, String tlsIssuer) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE entry SET last_checked_at = ?, last_check_status = ?, " +
                    "last_check_error = ?, last_tls_issuer = ? WHERE id = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, status);
                ps.setString(3, message);
                ps.setString(4, tlsIssuer);
                ps.setLong(5, entryId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("记录检查失败状态失败", e);
            }
        });
    }

    /**
     * 记录一次检查后该规则匹配到的资产。
     *
     * <p>这是「下载」按钮的数据来源：前端不再为了拿 assetName/downloadUrl
     * 去触发一次 checkOne（那会白扣一次配额，而且刷新页面后就没了）。
     *
     * @param ruleId      规则
     * @param asset       匹配到的资产；为 null 表示"检查过了但没匹配到"——
     *                    此时仍要写 cached_at，否则无法与"从未检查"区分
     * @param releaseId   所属 release id
     * @param releaseTag  所属 release tag
     */
    public void updateRuleAssetCache(long ruleId, com.githubupdater.infra.github.ReleaseDto.Asset asset,
                                     Long releaseId, String releaseTag) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE asset_rule SET cached_asset_name = ?, cached_asset_id = ?, " +
                    "cached_asset_size = ?, cached_asset_digest = ?, cached_asset_url = ?, " +
                    "cached_release_id = ?, cached_release_tag = ?, cached_at = ? WHERE id = ?")) {
                long now = System.currentTimeMillis();
                if (asset == null) {
                    // 检查过了但没有匹配：清空资产列，但保留 cached_at。
                    // 保留时间戳是刻意的——它表达"检查过"，与从未检查是两件事。
                    ps.setString(1, null);
                    ps.setNull(2, java.sql.Types.INTEGER);
                    ps.setNull(3, java.sql.Types.INTEGER);
                    ps.setString(4, null);
                    ps.setString(5, null);
                } else {
                    ps.setString(1, asset.name());
                    ps.setLong(2, asset.id());
                    ps.setLong(3, asset.size());
                    ps.setString(4, asset.digest());
                    ps.setString(5, asset.browserDownloadUrl());
                }
                if (releaseId == null) {
                    ps.setNull(6, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(6, releaseId);
                }
                ps.setString(7, releaseTag);
                ps.setLong(8, now);
                ps.setLong(9, ruleId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新规则资产缓存失败", e);
            }
        });
    }

    public void deleteEntry(long entryId) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM entry WHERE id = ?")) {
                ps.setLong(1, entryId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删除条目失败", e);
            }
        });
    }

    // ──────────────────────────── 规则 ────────────────────────────

    /** 规则的完整列清单。新增列时必须同步这里，否则 mapRule 会读不到。 */
    private static final String RULE_COLUMNS =
            "id, entry_id, include_glob, exclude_glob, kind, target_dir, " +
            "file_name_tpl, keep_versions, auto_apply, after_apply_cmd, enabled, created_at, " +
            "cached_asset_name, cached_asset_id, cached_asset_size, cached_asset_digest, " +
            "cached_asset_url, cached_release_id, cached_release_tag, cached_at";

    public List<AssetRule> listRules(long entryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + RULE_COLUMNS + " FROM asset_rule WHERE entry_id = ? ORDER BY created_at")) {
            ps.setLong(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                List<AssetRule> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRule(rs));
                }
                return list;
            }
        }
    }

    public long insertRule(long entryId, String includeGlob, String excludeGlob, AssetKind kind,
                           String targetDir, String fileNameTpl, int keepVersions,
                           boolean autoApply, String afterApplyCmd) {
        return writer.submitAndGet(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO asset_rule(entry_id, include_glob, exclude_glob, kind, target_dir, " +
                    "file_name_tpl, keep_versions, auto_apply, after_apply_cmd, enabled, created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,1,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, entryId);
                ps.setString(2, includeGlob);
                ps.setString(3, excludeGlob);
                ps.setString(4, kind.name());
                ps.setString(5, targetDir);
                ps.setString(6, fileNameTpl);
                ps.setInt(7, keepVersions);
                ps.setInt(8, autoApply ? 1 : 0);
                ps.setString(9, afterApplyCmd);
                ps.setLong(10, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("插入规则失败: " + e.getMessage(), e);
            }
        });
    }

    public void deleteRule(long ruleId) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM asset_rule WHERE id = ?")) {
                ps.setLong(1, ruleId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删除规则失败", e);
            }
        });
    }

    // ──────────────────────────── 下载任务 ────────────────────────────

    private static final String TASK_COLUMNS =
            "id, asset_rule_id, release_id, release_tag, asset_id, asset_name, download_url, " +
            "expected_size, expected_digest, etag, last_modified, accepts_ranges, " +
            "part_path, final_path, bytes_done, state, error_code, error_message, " +
            "retry_count, created_at, updated_at";

    /**
     * 创建下载任务。
     *
     * <p>用 {@code submitAndGet} 同步等待，因为调用方需要立刻拿到 taskId。
     */
    public long insertTask(long assetRuleId, long releaseId, String releaseTag, long assetId,
                           String assetName, String downloadUrl, Long expectedSize,
                           String expectedDigest, String partPath, String finalPath) {
        return writer.submitAndGet(() -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO download_task(asset_rule_id, release_id, release_tag, asset_id, " +
                    "asset_name, download_url, expected_size, expected_digest, part_path, final_path, " +
                    "bytes_done, state, created_at, updated_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,0,'QUEUED',?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, assetRuleId);
                ps.setLong(2, releaseId);
                ps.setString(3, releaseTag);
                ps.setLong(4, assetId);
                ps.setString(5, assetName);
                ps.setString(6, downloadUrl);
                if (expectedSize == null) {
                    ps.setNull(7, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(7, expectedSize);
                }
                ps.setString(8, expectedDigest);
                ps.setString(9, partPath);
                ps.setString(10, finalPath);
                ps.setLong(11, now);
                ps.setLong(12, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("创建下载任务失败: " + e.getMessage(), e);
            }
        });
    }

    public Optional<DownloadTask> findTask(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + TASK_COLUMNS + " FROM download_task WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapTask(rs)) : Optional.empty();
            }
        }
    }

    /** 列出任务。{@code states} 为空表示不限状态。 */
    public List<DownloadTask> listTasks(java.util.Set<TaskState> states) throws SQLException {
        String sql = "SELECT " + TASK_COLUMNS + " FROM download_task";
        boolean filter = states != null && !states.isEmpty();
        if (filter) {
            String placeholders = String.join(",", java.util.Collections.nCopies(states.size(), "?"));
            sql += " WHERE state IN (" + placeholders + ")";
        }
        sql += " ORDER BY created_at DESC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filter) {
                int i = 1;
                for (TaskState s : states) {
                    ps.setString(i++, s.name());
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<DownloadTask> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapTask(rs));
                }
                return list;
            }
        }
    }

    /** 取最早排队的任务，供调度器领取。 */
    public Optional<DownloadTask> claimNextQueued() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + TASK_COLUMNS + " FROM download_task WHERE state = 'QUEUED' " +
                "ORDER BY created_at LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapTask(rs)) : Optional.empty();
            }
        }
    }

    /**
     * 状态跃迁，带乐观并发控制。
     *
     * <p>WHERE 里带 {@code fromState}，返回受影响行数——0 表示状态已被其他线程改变，
     * 调用方必须据此放弃操作。这比加锁简单且无死锁风险。
     *
     * @return 是否成功跃迁
     */
    public boolean transitionState(long taskId, TaskState fromState, TaskState toState) {
        return writer.submitAndGet(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE download_task SET state = ?, updated_at = ? WHERE id = ? AND state = ?")) {
                ps.setString(1, toState.name());
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, taskId);
                ps.setString(4, fromState.name());
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new IllegalStateException("状态跃迁失败: " + e.getMessage(), e);
            }
        });
    }

    /** 无条件设置状态（用于失败、取消等强制路径）。 */
    public void setState(long taskId, TaskState state, String errorCode, String errorMessage) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE download_task SET state = ?, error_code = ?, error_message = ?, " +
                    "updated_at = ? WHERE id = ?")) {
                ps.setString(1, state.name());
                ps.setString(2, errorCode);
                ps.setString(3, errorMessage);
                ps.setLong(4, System.currentTimeMillis());
                ps.setLong(5, taskId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("设置任务状态失败", e);
            }
        });
    }

    /** 记录下载进度。<b>必须节流调用</b>（每 500ms），不是每个 chunk 都写。 */
    public void updateTaskProgress(long taskId, long bytesDone) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE download_task SET bytes_done = ?, updated_at = ? WHERE id = ?")) {
                ps.setLong(1, bytesDone);
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, taskId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新进度失败", e);
            }
        });
    }

    /**
     * 记录探测到的远端信息（ETag、长度、Range 支持）。
     *
     * <p>这是续传一致性的基础：下次续传要用这里的 ETag 发 {@code If-Range}。
     */
    public void updateTaskProbe(long taskId, String etag, String lastModified,
                                boolean acceptsRanges, Long totalSize) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE download_task SET etag = ?, last_modified = ?, accepts_ranges = ?, " +
                    "expected_size = COALESCE(?, expected_size), updated_at = ? WHERE id = ?")) {
                ps.setString(1, etag);
                ps.setString(2, lastModified);
                ps.setInt(3, acceptsRanges ? 1 : 0);
                if (totalSize == null) {
                    ps.setNull(4, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(4, totalSize);
                }
                ps.setLong(5, System.currentTimeMillis());
                ps.setLong(6, taskId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新探测信息失败", e);
            }
        });
    }

    /** 重置进度（服务器不支持续传或文件已变时用）。 */
    public void resetTaskProgress(long taskId) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE download_task SET bytes_done = 0, updated_at = ? WHERE id = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, taskId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("重置进度失败", e);
            }
        });
    }

    public void incrementRetry(long taskId) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE download_task SET retry_count = retry_count + 1, updated_at = ? WHERE id = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, taskId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("递增重试次数失败", e);
            }
        });
    }

    public void deleteTask(long taskId) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM download_task WHERE id = ?")) {
                ps.setLong(1, taskId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删除任务失败", e);
            }
        });
    }

    /**
     * 启动时重置上次未完成的任务。
     *
     * <p>{@code RUNNING} → {@code PAUSED}，而不是直接回 {@code QUEUED}：
     * HTTP 连接已断，ETag 可能失效，远端文件可能已变。重新探测后再决定
     * 续传还是重下，比盲目续传安全。
     *
     * @return 被重置的任务数
     */
    public int resetInterruptedTasks() {
        return writer.submitAndGet(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE download_task SET state = 'PAUSED', updated_at = ? WHERE state = 'RUNNING'")) {
                ps.setLong(1, System.currentTimeMillis());
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("重置中断任务失败", e);
            }
        });
    }

    /** 清理已终结的旧任务记录（保留最近 100 条）。 */
    public int pruneFinishedTasks() {
        return writer.submitAndGet(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM download_task WHERE state IN ('APPLIED','CANCELLED') AND id NOT IN (" +
                    "  SELECT id FROM download_task WHERE state IN ('APPLIED','CANCELLED') " +
                    "  ORDER BY updated_at DESC LIMIT 100)")) {
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("清理任务失败", e);
            }
        });
    }

    // ──────────────────────────── 文件备份 ────────────────────────────

    private static final String BACKUP_COLUMNS =
            "id, asset_rule_id, file_path, backup_path, release_tag, digest, created_at";

    /**
     * 记录一条备份。
     *
     * <p>用 {@code submitAndGet}：调用方需要立刻拿到 id 用于响应，且必须保证
     * 记录落库成功后再告诉用户"已备份"。
     */
    public long insertBackup(long assetRuleId, String filePath, String backupPath,
                             String releaseTag, String digest) {
        return writer.submitAndGet(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO file_backup(asset_rule_id, file_path, backup_path, " +
                    "release_tag, digest, created_at) VALUES(?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, assetRuleId);
                ps.setString(2, filePath);
                ps.setString(3, backupPath);
                ps.setString(4, releaseTag);
                ps.setString(5, digest);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("记录备份失败: " + e.getMessage(), e);
            }
        });
    }

    /** 按规则列出备份，最新的在前。 */
    public List<FileBackup> listBackups(long assetRuleId, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + BACKUP_COLUMNS + " FROM file_backup " +
                "WHERE asset_rule_id = ? ORDER BY created_at DESC LIMIT ?")) {
            ps.setLong(1, assetRuleId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<FileBackup> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapBackup(rs));
                }
                return list;
            }
        }
    }

    public Optional<FileBackup> findBackup(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + BACKUP_COLUMNS + " FROM file_backup WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapBackup(rs)) : Optional.empty();
            }
        }
    }

    /** 回滚成功后把该条备份记录删掉——它已经被消费，留着会误导界面。 */
    public void deleteBackup(long id) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM file_backup WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删除备份记录失败", e);
            }
        });
    }

    // ──────────────────────────── 元信息 ────────────────────────────

    public Optional<String> getMeta(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM app_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public void putMeta(String key, String value) {
        writer.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO app_meta(key, value) VALUES(?,?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写入元信息失败", e);
            }
        });
    }

    /** {@code PRAGMA integrity_check}。启动自检用。 */
    public boolean integrityCheck() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            return rs.next() && "ok".equalsIgnoreCase(rs.getString(1));
        } catch (SQLException e) {
            log.error("数据库完整性检查失败", e);
            return false;
        }
    }

    /** 等待所有排队写入完成，用于测试与优雅关闭。 */
    public void flush() {
        while (writer.pendingWrites() > 0) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 当前排队的写任务数，用于健康检查与诊断。 */
    public int pendingWrites() {
        return writer.pendingWrites();
    }

    @Override
    public void close() {
        writer.close();
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("关闭数据库连接失败：{}", e.getMessage());
        }
    }

    // ──────────────────────────── 映射 ────────────────────────────

    private static Entry mapEntry(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        Object releaseId = rs.getObject("last_seen_release_id");
        Object checkedAt = rs.getObject("last_checked_at");
        return new Entry(
                id,
                rs.getString("owner"),
                rs.getString("repo"),
                rs.getString("channel"),
                rs.getString("display_name"),
                rs.getInt("is_private") != 0,
                releaseId == null ? null : ((Number) releaseId).longValue(),
                rs.getString("last_seen_tag"),
                rs.getString("last_etag"),
                checkedAt == null ? null : ((Number) checkedAt).longValue(),
                rs.getLong("created_at"),
                rs.getString("last_check_status"),
                rs.getString("last_check_error"),
                rs.getString("last_tls_issuer")
        );
    }

    private static AssetRule mapRule(ResultSet rs) throws SQLException {
        return new AssetRule(
                rs.getLong("id"),
                rs.getLong("entry_id"),
                rs.getString("include_glob"),
                rs.getString("exclude_glob"),
                AssetKind.parse(rs.getString("kind")),
                rs.getString("target_dir"),
                rs.getString("file_name_tpl"),
                rs.getInt("keep_versions"),
                rs.getInt("auto_apply") != 0,
                rs.getString("after_apply_cmd"),
                rs.getInt("enabled") != 0,
                rs.getLong("created_at"),
                rs.getString("cached_asset_name"),
                nullableLong(rs, "cached_asset_id"),
                nullableLong(rs, "cached_asset_size"),
                rs.getString("cached_asset_digest"),
                rs.getString("cached_asset_url"),
                nullableLong(rs, "cached_release_id"),
                rs.getString("cached_release_tag"),
                nullableLong(rs, "cached_at")
        );
    }

    /**
     * 读可空的整型列。
     *
     * <p>不能用 {@code rs.getLong()} 然后判 0：0 是合法值（虽然这里不会出现），
     * 而且 SQL NULL 与 0 在语义上必须分开——{@code cached_at} 为 NULL 表示
     * "从未检查"，为 0 则表示"检查发生在 1970 年"，两者不能用同一个值表达。
     */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static FileBackup mapBackup(ResultSet rs) throws SQLException {
        return new FileBackup(
                rs.getLong("id"),
                rs.getLong("asset_rule_id"),
                rs.getString("file_path"),
                rs.getString("backup_path"),
                rs.getString("release_tag"),
                rs.getString("digest"),
                rs.getLong("created_at")
        );
    }

    private static DownloadTask mapTask(ResultSet rs) throws SQLException {
        Object expectedSize = rs.getObject("expected_size");
        Object acceptsRanges = rs.getObject("accepts_ranges");
        return new DownloadTask(
                rs.getLong("id"),
                rs.getLong("asset_rule_id"),
                rs.getLong("release_id"),
                rs.getString("release_tag"),
                rs.getLong("asset_id"),
                rs.getString("asset_name"),
                rs.getString("download_url"),
                expectedSize == null ? null : ((Number) expectedSize).longValue(),
                rs.getString("expected_digest"),
                rs.getString("etag"),
                rs.getString("last_modified"),
                acceptsRanges == null ? null : rs.getInt("accepts_ranges") != 0,
                rs.getString("part_path"),
                rs.getString("final_path"),
                rs.getLong("bytes_done"),
                TaskState.parse(rs.getString("state")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getInt("retry_count"),
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }
}
