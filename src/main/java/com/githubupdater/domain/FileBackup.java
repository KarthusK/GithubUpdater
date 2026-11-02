package com.githubupdater.domain;

/**
 * 文件备份记录，对应 {@code file_backup} 表。
 *
 * <p>每次应用（替换 / 解压覆盖）都会留下一条记录，用于界面展示"可回滚到哪个版本"。
 * 记录只是索引——真正的备份文件在 {@code backupPath}，用户也可能手工删掉，
 * 所以回滚前必须再校验文件是否存在。
 */
public record FileBackup(
        long id,
        long assetRuleId,
        String filePath,
        String backupPath,
        String releaseTag,
        String digest,
        long createdAt
) {
}
