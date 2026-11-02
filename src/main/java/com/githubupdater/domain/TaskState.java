package com.githubupdater.domain;

/**
 * 下载任务状态。
 *
 * <p>状态机与合法跃迁见 docs/02-数据库设计.md。非法跃迁必须被拒绝，
 * 且更新语句要带上"当前状态"作为条件（乐观并发控制）。
 */
public enum TaskState {

    /** 已创建，等待调度。 */
    QUEUED,

    /** 正在下载。 */
    RUNNING,

    /** 用户暂停，.part 保留。 */
    PAUSED,

    /** 下载完成，正在校验 SHA-256。 */
    VERIFYING,

    /** 校验通过，等待应用。 */
    DONE,

    /** 已应用（替换 / 解压 / checkout）。 */
    APPLIED,

    /** 失败，error_code 说明原因。 */
    FAILED,

    /** 用户取消，.part 已删除。 */
    CANCELLED;

    /** 是否处于"有文件可应用"的状态。 */
    public boolean isApplicable() {
        return this == DONE;
    }

    /** 是否为终态（不会被调度器再捡起）。 */
    public boolean isTerminal() {
        return this == APPLIED || this == CANCELLED;
    }

    /** 进程重启时，处于该状态的任务应被重置为 PAUSED。 */
    public boolean requiresResetOnStartup() {
        return this == RUNNING;
    }

    public static TaskState parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("state 不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的 state: " + raw);
        }
    }
}
