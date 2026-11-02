package com.githubupdater.domain;

/**
 * 跟踪条目：一个 GitHub 仓库一条记录。
 *
 * <p>{@code lastSeenReleaseId} 是版本判定的唯一依据（ADR-008），
 * 不要用 tag 或语义版本替代。
 */
public record Entry(
        long id,
        String owner,
        String repo,
        String channel,
        String displayName,
        boolean isPrivate,
        Long lastSeenReleaseId,
        String lastSeenTag,
        String lastEtag,
        Long lastCheckedAt,
        long createdAt
) {

    public static final String CHANNEL_LATEST = "latest";
    public static final String CHANNEL_PRERELEASE = "prerelease";
    public static final String CHANNEL_TAG_PREFIX = "tag:";

    /** 用于 API 显示的 {@code owner/repo}。 */
    public String fullName() {
        return owner + "/" + repo;
    }

    /** 是否跟踪 prerelease（含预发布）。 */
    public boolean includesPrerelease() {
        return CHANNEL_PRERELEASE.equals(channel)
                || channel.startsWith(CHANNEL_TAG_PREFIX);
    }

    /** 指定 tag 的频道返回 tag 名，其它返回 null。 */
    public String pinnedTag() {
        return channel.startsWith(CHANNEL_TAG_PREFIX)
                ? channel.substring(CHANNEL_TAG_PREFIX.length())
                : null;
    }
}
