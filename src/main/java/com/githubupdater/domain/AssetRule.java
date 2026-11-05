package com.githubupdater.domain;

/**
 * 资产规则：一条 {@link Entry} 下若干条规则。
 *
 * <p>{@code includeGlob}/{@code excludeGlob} 表达"要哪个资产"，
 * {@code kind} 表达"拿到资产后怎么处理"，{@code targetDir} 表达"放到哪里"。
 */
public record AssetRule(
        long id,
        long entryId,
        String includeGlob,
        String excludeGlob,
        AssetKind kind,
        String targetDir,
        String fileNameTpl,
        int keepVersions,
        boolean autoApply,
        String afterApplyCmd,
        boolean enabled,
        long createdAt,
        String cachedAssetName,
        Long cachedAssetId,
        Long cachedAssetSize,
        String cachedAssetDigest,
        String cachedAssetUrl,
        Long cachedReleaseId,
        String cachedReleaseTag,
        Long cachedAt
) {

    /** 归档类的默认文件名模板。 */
    public static final String DEFAULT_FILE_NAME_TPL = "{name}-{version}.{ext}";

    /** 归档类若未配模板，回退到默认值。 */
    public String effectiveFileNameTpl() {
        return (fileNameTpl == null || fileNameTpl.isBlank())
                ? DEFAULT_FILE_NAME_TPL
                : fileNameTpl;
    }

    /**
     * 最近一次检查是否已缓存到可用资产。
     *
     * <p>三个条件缺一不可：缓存过（{@code cachedAt} 非空）、匹配到了资产、
     * 且 URL 可用。只有 {@code cachedAssetName} 不为空就下结论是不够的——
     * 老版本迁移上来的行里这个字段本来就是 NULL，与"检查过但没匹配到"无法区分。
     */
    public boolean hasCachedAsset() {
        return cachedAt != null
                && cachedAssetName != null && !cachedAssetName.isBlank()
                && cachedAssetUrl != null && !cachedAssetUrl.isBlank();
    }

    /**
     * 是否已经检查过（无论有没有匹配到资产）。
     *
     * <p>用来区分两种"没有资产可用"：从未检查 → 引导去点「检查」；
     * 检查过但没匹配到 → 提示规则写错了。混用会让用户反复点检查却毫无变化。
     */
    public boolean hasBeenChecked() {
        return cachedAt != null;
    }
}
