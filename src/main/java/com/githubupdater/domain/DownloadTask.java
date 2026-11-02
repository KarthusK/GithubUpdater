package com.githubupdater.domain;

/**
 * 下载任务。
 *
 * <p>对应 {@code download_task} 表。关键字段：
 * <ul>
 *   <li>{@code etag} / {@code lastModified}：续传一致性校验。<b>必须</b>，
 *       否则远端文件在两次下载间被替换时会拼出"看起来成功"的坏文件。</li>
 *   <li>{@code acceptsRanges}：服务器是否支持 Range。为 false 时界面必须禁用暂停。</li>
 *   <li>{@code expectedDigest}：来自 GitHub 的 sha256，<b>可能为 null</b>。</li>
 * </ul>
 */
public record DownloadTask(
        long id,
        long assetRuleId,
        long releaseId,
        String releaseTag,
        long assetId,
        String assetName,
        String downloadUrl,
        Long expectedSize,
        String expectedDigest,
        String etag,
        String lastModified,
        Boolean acceptsRanges,
        String partPath,
        String finalPath,
        long bytesDone,
        TaskState state,
        String errorCode,
        String errorMessage,
        int retryCount,
        long createdAt,
        long updatedAt
) {

    /** 进度百分比（0–100）。未知总大小时返回 -1。 */
    public double percent() {
        if (expectedSize == null || expectedSize <= 0) {
            return -1;
        }
        return Math.min(100.0, bytesDone * 100.0 / expectedSize);
    }

    /** 是否已下载完整。 */
    public boolean isComplete() {
        return expectedSize != null && expectedSize > 0 && bytesDone >= expectedSize;
    }

    /** 上游是否提供了校验和。为 false 时应用前需用户确认。 */
    public boolean hasDigest() {
        return expectedDigest != null && !expectedDigest.isBlank();
    }

    /** 服务器是否确认支持断点续传。 */
    public boolean canResume() {
        return Boolean.TRUE.equals(acceptsRanges);
    }

    /** 用于界面显示的简短描述。 */
    public String describe() {
        return assetName + " (" + releaseTag + ")";
    }
}
