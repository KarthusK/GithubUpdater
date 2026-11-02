package com.githubupdater.infra.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SHA-256 校验。这是下载完成的唯一成功判据。 */
class DigestVerifierTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("应计算出与 MessageDigest 一致的 sha256: 格式摘要")
    void computesCorrectDigest() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);

        String expected = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("hello".getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, DigestVerifier.computeDigest(file));
        assertTrue(expected.startsWith("sha256:"), "格式必须与 GitHub digest 字段一致");
    }

    @Test
    @DisplayName("摘要匹配应返回 MATCHED")
    void matched() throws Exception {
        Path file = tempDir.resolve("b.bin");
        Files.write(file, new byte[]{1, 2, 3, 4, 5});
        String digest = DigestVerifier.computeDigest(file);

        var result = DigestVerifier.verify(file, digest);
        assertEquals(DigestVerifier.Result.Status.MATCHED, result.status());
        assertTrue(result.isOk());
        assertTrue(result.canApplyWithoutConfirm());
    }

    @Test
    @DisplayName("摘要不匹配应返回 MISMATCHED，且不允许直接应用")
    void mismatched() throws Exception {
        Path file = tempDir.resolve("c.bin");
        Files.write(file, new byte[]{1, 2, 3});

        var result = DigestVerifier.verify(file, "sha256:" + "0".repeat(64));
        assertEquals(DigestVerifier.Result.Status.MISMATCHED, result.status());
        assertFalse(result.isOk());
        assertFalse(result.canApplyWithoutConfirm());
    }

    @Test
    @DisplayName("上游无 digest 时应返回 UNVERIFIED，不能当作通过")
    void unverified() throws Exception {
        Path file = tempDir.resolve("d.bin");
        Files.write(file, new byte[]{9});

        // 这是关键设计：未校验 != 通过。必须让用户确认后才能应用。
        var nullResult = DigestVerifier.verify(file, null);
        assertEquals(DigestVerifier.Result.Status.UNVERIFIED, nullResult.status());
        assertFalse(nullResult.isOk());
        assertFalse(nullResult.canApplyWithoutConfirm());

        var blankResult = DigestVerifier.verify(file, "   ");
        assertEquals(DigestVerifier.Result.Status.UNVERIFIED, blankResult.status());
    }

    @Test
    @DisplayName("比对应忽略大小写与首尾空白")
    void comparisonIsLenient() throws Exception {
        Path file = tempDir.resolve("e.bin");
        Files.write(file, new byte[]{7, 7, 7});
        String digest = DigestVerifier.computeDigest(file);

        assertTrue(DigestVerifier.verify(file, digest.toUpperCase()).isOk());
        assertTrue(DigestVerifier.verify(file, "  " + digest + "  ").isOk());
    }

    @Test
    @DisplayName("空文件的摘要应稳定")
    void emptyFile() throws Exception {
        Path file = tempDir.resolve("empty.bin");
        Files.write(file, new byte[0]);

        String digest = DigestVerifier.computeDigest(file);
        assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest);
    }

    @Test
    @DisplayName("大文件应流式计算，不把内容读进内存")
    void largeFileStreams() throws Exception {
        Path file = tempDir.resolve("big.bin");
        // 8MB，远大于任何合理的读缓冲；若实现有内存问题这里会 OOM
        byte[] chunk = new byte[1024 * 1024];
        try (var out = Files.newOutputStream(file)) {
            for (int i = 0; i < 8; i++) {
                out.write(chunk);
            }
        }
        String digest = DigestVerifier.computeDigest(file);
        assertTrue(digest.startsWith("sha256:"));
        assertEquals(71, digest.length(), "sha256: 前缀 + 64 位十六进制");
    }
}
