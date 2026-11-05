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
    public static final int LATEST_VERSION = 3;

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
            )),

            new Migration(2, "记录最近一次检查的结局与失败原因", List.of(
                    // 为什么要落库而不是只在界面上闪一条 toast：
                    // 检查失败后用户往往会去处理代理/网络，然后刷新页面再看——
                    // 如果失败原因只存在内存里，刷新后就只剩"从未检查"或
                    // "刚刚检查过"而看不到任何原因，等于把线索抹掉了。
                    "ALTER TABLE entry ADD COLUMN last_check_status TEXT",
                    "ALTER TABLE entry ADD COLUMN last_check_error  TEXT",
                    // 单独存 TLS 签发者：界面要据此判断"这是中间人劫持"而不是
                    // 普通网络故障，两者给的引导完全不同。
                    "ALTER TABLE entry ADD COLUMN last_tls_issuer   TEXT"
            )),

            new Migration(3, "按规则缓存最近一次检查匹配到的资产", List.of(
                    // 为什么要缓存：点「下载」原本会先调一次 checkOne 去拿资产信息，
                    // 但 CheckOutcome.matchedAssets 只带了文件名，URL / id / digest
                    // 在这一步就丢了，导致 createTask 必然 400。
                    //
                    // 缓存到**规则**而不是条目：一个条目下多条规则会匹配到不同资产，
                    // 按条目存会让"下载第一条规则"取到别人的文件——这正是
                    // AssetMatcher 一直要防的装错文件事故。
                    "ALTER TABLE asset_rule ADD COLUMN cached_asset_name   TEXT",
                    "ALTER TABLE asset_rule ADD COLUMN cached_asset_id     INTEGER",
                    "ALTER TABLE asset_rule ADD COLUMN cached_asset_size   INTEGER",
                    "ALTER TABLE asset_rule ADD COLUMN cached_asset_digest TEXT",
                    // browser_download_url 是永久链接（releases/download/<tag>/<asset>），
                    // 302 跳到签名 CDN 发生在下载时，所以落库不存在"URL 过期"问题
                    "ALTER TABLE asset_rule ADD COLUMN cached_asset_url    TEXT",
                    "ALTER TABLE asset_rule ADD COLUMN cached_release_id   INTEGER",
                    "ALTER TABLE asset_rule ADD COLUMN cached_release_tag  TEXT",
                    // 关键：cached_at 为 NULL = 从未检查过；非 NULL 但 asset_name
                    // 为 NULL = 检查过、但该规则没匹配到资产。两种提示完全不同，
                    // 必须能区分，所以不能用 asset_name 是否为空代替。
                    "ALTER TABLE asset_rule ADD COLUMN cached_at           INTEGER"
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
