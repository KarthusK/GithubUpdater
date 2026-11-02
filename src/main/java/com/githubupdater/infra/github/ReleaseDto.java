package com.githubupdater.infra.github;

import java.util.List;

/**
 * GitHub Release 的响应映射。
 *
 * <p>只声明用得到的字段。特别注意：
 * <ul>
 *   <li>{@code id} 是版本判定的唯一依据（ADR-008）</li>
 *   <li>{@code created_at} 是<b>该 release 所指向 commit 的日期</b>，不是发布日，不要用它比新旧</li>
 *   <li>{@code published_at} 才是实际发布时间</li>
 * </ul>
 */
public final class ReleaseDto {

    private ReleaseDto() {
    }

    /**
     * Release 摘要。
     *
     * @param id          release 的唯一 id，<b>版本判定只看它是否变化</b>
     * @param tagName     tag 名，仅用于显示与归档文件名
     * @param name        release 标题，可能为 null
     * @param prerelease  是否预发布
     * @param draft       是否草稿（匿名访问时看不到草稿）
     * @param publishedAt 实际发布时间（ISO 字符串，可能为 null）
     */
    public record Release(
            long id,
            String tagName,
            String name,
            boolean prerelease,
            boolean draft,
            String publishedAt,
            List<Asset> assets
    ) {

        /** 用于界面显示的名称，标题为空时回退到 tag。 */
        public String displayName() {
            return (name == null || name.isBlank()) ? tagName : name;
        }
    }

    /**
     * Release 资产。
     *
     * @param id               资产唯一 id
     * @param name             文件名
     * @param size             字节数
     * @param digest           {@code sha256:...} 格式，<b>可能为 null</b>（2025-04 前的资产、源码自动生成的 zip）
     * @param browserDownloadUrl 直连下载地址，<b>不消耗 API 配额</b>
     */
    public record Asset(
            long id,
            String name,
            long size,
            String digest,
            String browserDownloadUrl
    ) {

        /** 上游是否提供了校验和。为 false 时下载后需用户确认。 */
        public boolean hasDigest() {
            return digest != null && !digest.isBlank();
        }
    }
}
