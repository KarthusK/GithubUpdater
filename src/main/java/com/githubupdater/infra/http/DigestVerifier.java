package com.githubupdater.infra.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 校验。
 *
 * <p>这是下载完成的<b>唯一成功判据</b>。不信任"状态显示成功"——
 * 断点续传的偏移错位、ETag 失效、CDN 返回错误页，都会产生
 * "看起来成功但文件已损坏"的结果。
 *
 * <p>流式读取，不把整个文件读进内存（大文件会撑爆堆）。
 */
public final class DigestVerifier {

    private static final int BUFFER_SIZE = 64 * 1024;

    private DigestVerifier() {
    }

    /**
     * 计算文件的 SHA-256，返回 {@code sha256:<hex>} 格式。
     *
     * <p>与 GitHub 的 {@code assets[].digest} 字段格式一致，可直接比对。
     */
    public static String computeDigest(Path file) throws IOException {
        MessageDigest md = newSha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                md.update(buffer, 0, n);
            }
        }
        return "sha256:" + HexFormat.of().formatHex(md.digest());
    }

    /**
     * 比对文件与期望的 digest。
     *
     * @param file           待校验文件
     * @param expectedDigest {@code sha256:...} 格式；为 null 或空表示上游未提供
     * @return 校验结果
     */
    public static Result verify(Path file, String expectedDigest) throws IOException {
        if (expectedDigest == null || expectedDigest.isBlank()) {
            // 上游没提供校验和：不能算通过，只能标记"未校验"
            return Result.unverified();
        }
        String actual = computeDigest(file);
        if (actual.equalsIgnoreCase(expectedDigest.trim())) {
            return Result.matched(actual);
        }
        return Result.mismatched(expectedDigest, actual);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，不可能缺失
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    /** 校验结果。三态，不能简化成 boolean——"未校验"必须与"通过"区分。 */
    public record Result(Status status, String expected, String actual) {

        public enum Status {
            /** 校验通过。 */
            MATCHED,
            /** 校验失败，文件损坏或被篡改。 */
            MISMATCHED,
            /** 上游未提供校验和，无法校验。 */
            UNVERIFIED
        }

        static Result matched(String actual) {
            return new Result(Status.MATCHED, actual, actual);
        }

        static Result mismatched(String expected, String actual) {
            return new Result(Status.MISMATCHED, expected, actual);
        }

        static Result unverified() {
            return new Result(Status.UNVERIFIED, null, null);
        }

        public boolean isOk() {
            return status == Status.MATCHED;
        }

        /** 是否可用于应用。未校验的需用户确认后才能应用。 */
        public boolean canApplyWithoutConfirm() {
            return status == Status.MATCHED;
        }
    }
}
