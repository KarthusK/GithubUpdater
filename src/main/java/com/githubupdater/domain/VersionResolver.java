package com.githubupdater.domain;

import java.util.Locale;

/**
 * 版本判定。
 *
 * <p><b>只看 release id 是否变化</b>，不做语义版本比较。理由见 ADR-008：
 * <ul>
 *   <li>{@code /releases/latest} 可被作者用 {@code make_latest} 手动指定旧 release</li>
 *   <li>它按 {@code created_at} 排序，而官方定义 created_at 是"release 所指向 commit 的日期"</li>
 *   <li>大量项目用 {@code nightly}、{@code 2026.11.1}、{@code v2} 等非标准 tag</li>
 * </ul>
 *
 * <p>tag 只用于显示与归档文件名，不参与判定。
 */
public final class VersionResolver {

    private VersionResolver() {
    }

    /**
     * 判断是否出现了新版本。
     *
     * @param lastSeenReleaseId 上次记录到的 release id；首次检查时为 null
     * @param currentReleaseId  本次拿到的 release id
     * @return 有新版本返回 true
     */
    public static boolean hasUpdate(Long lastSeenReleaseId, long currentReleaseId) {
        return lastSeenReleaseId == null || lastSeenReleaseId != currentReleaseId;
    }

    /**
     * 生成归档文件名。
     *
     * <p>模板占位符：{@code {name}}、{@code {version}}、{@code {ext}}。
     * 占位符缺失时原样保留，便于用户发现模板写错。
     *
     * @param template  模板，如 {@code {name}-{version}.{ext}}
     * @param assetName 原始资产名，如 {@code jq-windows-amd64.exe}
     * @param version   版本标识，通常是 release tag
     */
    public static String renderFileName(String template, String assetName, String version) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("文件名模板不能为空");
        }
        String ext = extensionOf(assetName);
        String base = ext.isEmpty()
                ? assetName
                : assetName.substring(0, assetName.length() - ext.length() - 1);

        return template
                .replace("{name}", sanitize(base))
                .replace("{version}", sanitize(version))
                .replace("{ext}", ext);
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 剔除 Windows 文件名非法字符，避免目标路径不可创建。 */
    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
