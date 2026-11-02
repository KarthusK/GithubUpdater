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
        long createdAt
) {

    /** 归档类的默认文件名模板。 */
    public static final String DEFAULT_FILE_NAME_TPL = "{name}-{version}.{ext}";

    /** 归档类若未配模板，回退到默认值。 */
    public String effectiveFileNameTpl() {
        return (fileNameTpl == null || fileNameTpl.isBlank())
                ? DEFAULT_FILE_NAME_TPL
                : fileNameTpl;
    }
}
