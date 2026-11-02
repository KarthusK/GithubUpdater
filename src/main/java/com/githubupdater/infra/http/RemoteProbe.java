package com.githubupdater.infra.http;

/**
 * 远端文件的探测结果。
 *
 * <p>在真正下载前先探测，用来决定能否续传。这几个字段是续传一致性的基础：
 * <ul>
 *   <li>{@code etag}：续传时作为 {@code If-Range} 的值，保证片段来自同一版本</li>
 *   <li>{@code acceptsRanges}：为 false 时界面必须禁用暂停按钮</li>
 *   <li>{@code totalSize}：用于预分配文件与判断是否已下完</li>
 * </ul>
 */
public record RemoteProbe(
        String url,
        String etag,
        String lastModified,
        boolean acceptsRanges,
        long totalSize,
        int statusCode
) {

    /** 总大小是否已知。分块传输（chunked）时为未知。 */
    public boolean hasKnownSize() {
        return totalSize > 0;
    }

    /** 探测是否有效（拿到 2xx）。 */
    public boolean isValid() {
        return statusCode >= 200 && statusCode < 300;
    }
}
