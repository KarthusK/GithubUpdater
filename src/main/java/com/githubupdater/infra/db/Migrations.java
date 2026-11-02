package com.githubupdater.infra.db;

import java.util.List;

/**
 * 数据库迁移定义与脚本。见 docs/02-数据库设计.md。
 *
 * <p>原则：<b>只做加法</b>——新增表 / 列 / 索引，不改已有列语义，不删列。
 * 这样才能保证旧版本数据能平滑升级。
 */
public final class Migrations {

    private Migrations() {
    }

    /** 迁移条目。{@code statements} 在单个事务内顺序执行。 */
    public record Migration(int version, String description, List<String> statements) {
    }

    /** 当前代码支持的最新 schema 版本。 */
    public static final int LATEST_VERSION = 1;

    public static final List<Migration> ALL = List.of(
            new Migration(1, "初始 schema", List.of(
                    """
                    CREATE TABLE entry (
                        id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner                 TEXT    NOT NULL,
                        repo                  TEXT    NOT NULL,
                        channel               TEXT    NOT NULL DEFAULT 'latest',
                        display_name          TEXT    NOT NULL,
                        is_private            INTEGER NOT NULL DEFAULT 0,
                        last_seen_release_id  INTEGER,
                        last_seen_tag         TEXT,
                        last_etag             TEXT,
                        last_checked_at       INTEGER,
                        created_at            INTEGER NOT NULL,
                        UNIQUE(owner, repo)
                    )
                    """,
                    "CREATE INDEX idx_entry_checked ON entry(last_checked_at)",

                    """
                    CREATE TABLE asset_rule (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        entry_id        INTEGER NOT NULL REFERENCES entry(id) ON DELETE CASCADE,
                        include_glob    TEXT    NOT NULL,
                        exclude_glob    TEXT,
                        kind            TEXT    NOT NULL,
                        target_dir      TEXT    NOT NULL,
                        file_name_tpl   TEXT,
                        keep_versions   INTEGER NOT NULL DEFAULT 1,
                        auto_apply      INTEGER NOT NULL DEFAULT 0,
                        after_apply_cmd TEXT,
                        enabled         INTEGER NOT NULL DEFAULT 1,
                        created_at      INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX idx_rule_entry ON asset_rule(entry_id)",

                    """
                    CREATE TABLE download_task (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        asset_rule_id    INTEGER NOT NULL REFERENCES asset_rule(id) ON DELETE CASCADE,
                        release_id       INTEGER NOT NULL,
                        release_tag      TEXT    NOT NULL,
                        asset_id         INTEGER NOT NULL,
                        asset_name       TEXT    NOT NULL,
                        download_url     TEXT    NOT NULL,
                        expected_size    INTEGER,
                        expected_digest  TEXT,
                        etag             TEXT,
                        last_modified    TEXT,
                        accepts_ranges   INTEGER,
                        part_path        TEXT    NOT NULL,
                        final_path       TEXT    NOT NULL,
                        bytes_done       INTEGER NOT NULL DEFAULT 0,
                        state            TEXT    NOT NULL,
                        error_code       TEXT,
                        error_message    TEXT,
                        retry_count      INTEGER NOT NULL DEFAULT 0,
                        created_at       INTEGER NOT NULL,
                        updated_at       INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX idx_task_state ON download_task(state, created_at)",
                    "CREATE INDEX idx_task_rule  ON download_task(asset_rule_id)",

                    """
                    CREATE TABLE file_backup (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        asset_rule_id  INTEGER NOT NULL REFERENCES asset_rule(id) ON DELETE CASCADE,
                        file_path      TEXT    NOT NULL,
                        backup_path    TEXT    NOT NULL,
                        release_tag    TEXT,
                        digest         TEXT,
                        created_at     INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX idx_backup_rule ON file_backup(asset_rule_id, created_at DESC)",

                    """
                    CREATE TABLE check_history (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        entry_id      INTEGER NOT NULL REFERENCES entry(id) ON DELETE CASCADE,
                        checked_at    INTEGER NOT NULL,
                        result        TEXT    NOT NULL,
                        release_id    INTEGER,
                        release_tag   TEXT,
                        error_message TEXT
                    )
                    """,
                    "CREATE INDEX idx_check_entry ON check_history(entry_id, checked_at DESC)",

                    """
                    CREATE TABLE app_meta (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """
            ))
    );

    /** 返回从 {@code fromVersion} 之后需要执行的迁移。 */
    public static List<Migration> pending(int fromVersion) {
        return ALL.stream()
                .filter(m -> m.version() > fromVersion)
                .sorted((a, b) -> Integer.compare(a.version(), b.version()))
                .toList();
    }
}
