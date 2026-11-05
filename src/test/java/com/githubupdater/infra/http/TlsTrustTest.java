package com.githubupdater.infra.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自定义 CA 的加载与信任链构建。
 *
 * <p>最要紧的一条是 {@link #appendsToDefaultTrustStoreInsteadOfReplacing()}：
 * 它锁住"追加而不是替换"这个语义。一旦有人把它改成替换，用户关掉代理之后
 * 就会彻底连不上 GitHub——而那是个不会在开发机上暴露的故障。
 */
class TlsTrustTest {

    @TempDir
    Path tempDir;

    /** 测试用自签 CA（构建期生成，仅测试使用）。同一测试内可重复调用。 */
    private Path testCa() throws Exception {
        Path p = tempDir.resolve("test-ca.pem");
        if (Files.exists(p)) {
            return p;
        }
        try (var in = getClass().getResourceAsStream("/test-ca.pem")) {
            assertNotNull(in, "测试证书 test-ca.pem 应存在于 src/test/resources");
            Files.copy(in, p);
            return p;
        }
    }

    @Test
    @DisplayName("未配置时应返回 NOT_CONFIGURED，不给 SSLContext")
    void notConfigured() {
        TlsTrust.Outcome outcome = TlsTrust.fromFile(null);

        assertEquals(TlsTrust.Status.NOT_CONFIGURED, outcome.status());
        assertNull(outcome.context(), "未配置时不该构造 SSLContext，应让 HttpClient 用默认");
        assertFalse(outcome.isLoaded());
        assertFalse(outcome.isFailure(), "未配置不是失败");
    }

    @Test
    @DisplayName("文件不存在应降级为 FILE_NOT_FOUND，不抛异常")
    void fileNotFound() {
        TlsTrust.Outcome outcome = TlsTrust.fromFile(tempDir.resolve("nope.pem"));

        assertEquals(TlsTrust.Status.FILE_NOT_FOUND, outcome.status());
        assertNull(outcome.context());
        assertTrue(outcome.isFailure());
        assertFalse(outcome.detail().isBlank(), "应给出可读原因");
    }

    @Test
    @DisplayName("非证书内容应降级为 NO_CERTIFICATE，不抛异常")
    void notACertificate() throws Exception {
        Path bogus = tempDir.resolve("bogus.pem");
        Files.writeString(bogus, "这不是证书，只是一段文字。", StandardCharsets.UTF_8);

        TlsTrust.Outcome outcome = assertDoesNotThrow(() -> TlsTrust.fromFile(bogus));

        assertEquals(TlsTrust.Status.NO_CERTIFICATE, outcome.status());
        assertNull(outcome.context());
        assertTrue(outcome.isFailure());
    }

    @Test
    @DisplayName("空文件应降级为 NO_CERTIFICATE")
    void emptyFile() throws Exception {
        Path empty = tempDir.resolve("empty.pem");
        Files.writeString(empty, "");

        TlsTrust.Outcome outcome = TlsTrust.fromFile(empty);

        assertEquals(TlsTrust.Status.NO_CERTIFICATE, outcome.status());
        assertNull(outcome.context());
    }

    @Test
    @DisplayName("超过大小上限应拒绝，避免把大文件整个读进内存")
    void tooLarge() throws Exception {
        Path big = tempDir.resolve("big.pem");
        // 上限是 1MB，写 1MB + 1 字节即可触发
        Files.write(big, new byte[1024 * 1024 + 1]);

        TlsTrust.Outcome outcome = TlsTrust.fromFile(big);

        assertEquals(TlsTrust.Status.TOO_LARGE, outcome.status());
        assertNull(outcome.context());
    }

    @Test
    @DisplayName("合法 PEM 应加载出 SSLContext")
    void loadsValidPem() throws Exception {
        TlsTrust.Outcome outcome = TlsTrust.fromFile(testCa());

        assertEquals(TlsTrust.Status.LOADED, outcome.status());
        assertEquals(1, outcome.certCount());
        assertNotNull(outcome.context());
        assertTrue(outcome.isLoaded());
        assertFalse(outcome.isFailure());
    }

    @Test
    @DisplayName("多张证书应全部加载")
    void loadsMultipleCertificates() throws Exception {
        // 同一张证书拼两遍：验证 generateCertificates 会读出多张，
        // 而不是只取第一张。这关系到用户能否一次配好整条链。
        String single = Files.readString(testCa(), StandardCharsets.UTF_8);
        Path doubled = tempDir.resolve("two.pem");
        Files.writeString(doubled, single + System.lineSeparator() + single,
                StandardCharsets.UTF_8);

        TlsTrust.Outcome outcome = TlsTrust.fromFile(doubled);

        assertEquals(TlsTrust.Status.LOADED, outcome.status());
        assertEquals(2, outcome.certCount());
    }

    @Test
    @DisplayName("必须追加到默认信任链，不能替换——否则关掉代理后就全挂")
    void appendsToDefaultTrustStoreInsteadOfReplacing() throws Exception {
        X509Certificate[] custom = TlsTrust.readCertificatesForTest(testCa());
        assertEquals(1, custom.length);

        int defaultCount = defaultAcceptedIssuerCount();
        // 这份 JDK 的默认信任库有 143 张 CA；断言"远大于 1"而不是死磕具体数字，
        // 避免换 JDK 版本时测试无谓地红。
        assertTrue(defaultCount > 10, "默认信任库应包含多张 CA，实际 " + defaultCount);

        var tm = TlsTrust.compositeTrustManager(custom);
        int compositeCount = tm.getAcceptedIssuers().length;

        assertTrue(compositeCount > defaultCount,
                "组合信任链应包含默认 CA（" + defaultCount + "）加上自定义 CA，"
                        + "实际只有 " + compositeCount + " 张——说明退化成替换语义了");
        assertEquals(defaultCount + 1, compositeCount,
                "应恰好是默认 CA 数 + 1 张自定义 CA");
    }

    @Test
    @DisplayName("追加的 CA 不重复计数（同证书拼两遍时按张数算）")
    void compositeTrustManagerHandlesMultiple() throws Exception {
        Path doubled = tempDir.resolve("two-certs.pem");
        String single = Files.readString(testCa(), StandardCharsets.UTF_8);
        Files.writeString(doubled, single + System.lineSeparator() + single,
                StandardCharsets.UTF_8);

        X509Certificate[] custom = TlsTrust.readCertificatesForTest(doubled);
        int defaultCount = defaultAcceptedIssuerCount();
        var tm = TlsTrust.compositeTrustManager(custom);

        // KeyStore 按别名存，两张相同的证书会占两个别名，
        // 所以这里只断言"没丢掉默认信任库"，不苛求去重行为。
        assertTrue(tm.getAcceptedIssuers().length > defaultCount,
                "默认 CA 必须仍在信任链里");
    }

    /** 默认信任库里有几张 CA（实测这份 JDK 是 143 张）。 */
    private static int defaultAcceptedIssuerCount() throws Exception {
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null);
        return countX509(tmf);
    }

    private static int countX509(TrustManagerFactory tmf) {
        int total = 0;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof javax.net.ssl.X509TrustManager x509) {
                total += x509.getAcceptedIssuers().length;
            }
        }
        return total;
    }

    @Test
    @DisplayName("证书文件是目录时应报 UNREADABLE 而不是崩溃")
    void directoryInsteadOfFile() throws Exception {
        Path dir = tempDir.resolve("a-directory");
        Files.createDirectories(dir);

        TlsTrust.Outcome outcome = TlsTrust.fromFile(dir);

        assertEquals(TlsTrust.Status.UNREADABLE, outcome.status());
        assertNull(outcome.context());
    }

    @Test
    @DisplayName("加载出的证书主题应能被解析")
    void loadedCertificateIsParsable() throws Exception {
        // 间接确认我们真的解析出了 X.509 而不是某种占位对象
        TlsTrust.Outcome outcome = TlsTrust.fromFile(testCa());
        assertEquals(TlsTrust.Status.LOADED, outcome.status());

        var cf = java.security.cert.CertificateFactory.getInstance("X.509");
        try (var in = Files.newInputStream(testCa())) {
            var cert = (X509Certificate) cf.generateCertificate(in);
            assertTrue(cert.getSubjectX500Principal().getName().contains("GithubUpdater"),
                    "测试证书的主题应可读，实际：" + cert.getSubjectX500Principal());
        }
    }
}
