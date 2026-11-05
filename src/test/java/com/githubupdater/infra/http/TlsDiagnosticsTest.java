package com.githubupdater.infra.http;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertPathBuilderException;
import java.util.Optional;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中间人识别。
 *
 * <p>这个类的价值全在"能不能说清是谁在劫持"，所以测试的重点是
 * <b>真的起一个自签 HTTPS 服务</b>然后确认能读出签发者，而不是
 * 在 mock 上断言字符串拼接。
 */
class TlsDiagnosticsTest {

    @TempDir
    Path tempDir;

    private HttpsServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * 起一个用测试自签证书的 HTTPS 服务，模拟"本机代理在解密流量"。
     *
     * @return 实际监听端口
     */
    private int startSelfSignedServer() throws Exception {
        // 用 keytool 生成的测试证书不便直接当服务端证书用，
        // 这里现生成一张自签的服务端证书（纯 JDK，无外部依赖）。
        // 生成逻辑见 makeSelfSignedServerContext()。
        SSLContext ctx = SelfSignedServer.context();
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/", ex -> {
            byte[] body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    @DisplayName("自签证书服务：应读出签发者并判定为自签名")
    void detectsSelfSignedIssuer() throws Exception {
        int port = startSelfSignedServer();

        Optional<TlsDiagnostics.Diagnosis> result = TlsDiagnostics.probe("127.0.0.1", port);

        assertTrue(result.isPresent(), "应能读到对端证书");
        var d = result.get();
        assertNotNull(d.issuer());
        assertTrue(d.issuer().contains("GithubUpdater"),
                "签发者应指向测试证书，实际：" + d.issuer());
        assertTrue(d.selfSigned(), "自签证书的 subject 应等于 issuer");
        assertEquals(d.subject(), d.issuer());
    }

    @Test
    @DisplayName("端口没开时应返回 empty，不抛异常")
    void closedPortReturnsEmpty() {
        // 找一个几乎肯定没在监听的端口
        Optional<TlsDiagnostics.Diagnosis> result =
                TlsDiagnostics.probe("127.0.0.1", 1);

        assertTrue(result.isEmpty(), "连不上时必须是 empty，而不是异常或伪造结果");
    }

    @Test
    @DisplayName("非法参数应返回 empty")
    void invalidArguments() {
        assertTrue(TlsDiagnostics.probe(null, 443).isEmpty());
        assertTrue(TlsDiagnostics.probe("", 443).isEmpty());
        assertTrue(TlsDiagnostics.probe("127.0.0.1", 0).isEmpty());
        assertTrue(TlsDiagnostics.probe("127.0.0.1", 70000).isEmpty());
    }

    // ── 异常归类 ────────────────────────────────────────

    @Test
    @DisplayName("PKIX 失败应被识别为证书不受信任")
    void recognizesPkixFailure() {
        Throwable pkix = new javax.net.ssl.SSLHandshakeException(
                "PKIX path building failed: unable to find valid certification path");
        pkix.initCause(new CertPathBuilderException("unable to find valid certification path"));

        assertTrue(TlsDiagnostics.isCertificateUntrusted(pkix));
    }

    @Test
    @DisplayName("深层嵌套的 cause 也要能识别")
    void recognizesDeeplyNestedCause() {
        // 实测真实结构是 4 层：SSLHandshakeException -> SSLHandshakeException
        // -> ValidatorException -> CertPathBuilderException
        Throwable root = new CertPathBuilderException("unable to find valid certification path");
        Throwable mid = new CertificateException("PKIX path building failed");
        mid.initCause(root);
        Throwable top = new javax.net.ssl.SSLHandshakeException("PKIX path building failed");
        top.initCause(mid);

        assertTrue(TlsDiagnostics.isCertificateUntrusted(top));
    }

    @Test
    @DisplayName("普通网络错误不应被误判成证书问题")
    void doesNotMisclassifyNetworkErrors() {
        assertFalse(TlsDiagnostics.isCertificateUntrusted(
                new java.net.ConnectException("Connection refused")));
        assertFalse(TlsDiagnostics.isCertificateUntrusted(
                new java.net.SocketTimeoutException("Read timed out")));
        assertFalse(TlsDiagnostics.isCertificateUntrusted(
                new javax.net.ssl.SSLHandshakeException("Received fatal alert: protocol_version")));
        assertFalse(TlsDiagnostics.isCertificateUntrusted(null));
    }

    @Test
    @DisplayName("cause 链成环时不能死循环")
    void handlesCyclicCauseChain() {
        Exception a = new RuntimeException("a");
        Exception b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);

        // 只要能在合理时间内返回即可，结果本身不重要
        assertFalse(TlsDiagnostics.isCertificateUntrusted(a));
    }

    /** 现生成自签服务端证书的辅助类：纯 JDK，不依赖 keytool。 */
    private static final class SelfSignedServer {

        static SSLContext context() throws Exception {
            // 用 KeyPairGenerator + 手工构造证书太啰嗦，这里复用 sun.security 不现实。
            // 改为：用一个固定生成的 PKCS12（下方内嵌的 base64）——但它含私钥，
            // 不适合提交进仓库。
            //
            // 折中方案：用 JDK 自带的 keytool 在临时目录现生成一次。
            // 测试环境必然有 keytool（跑测试就有 JDK），且生成只需 ~200ms。
            Path dir = Files.createTempDirectory("gu-test-server-cert");
            Path ks = dir.resolve("server.p12");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();

            var pb = new ProcessBuilder(keytool, "-genkeypair",
                    "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                    "-dname", "CN=GithubUpdater Test MITM, O=GithubUpdaterTest, C=CN",
                    "-validity", "3650",
                    "-keystore", ks.toString(), "-storetype", "PKCS12",
                    "-storepass", "changeit", "-noprompt");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (InputStream in = p.getInputStream()) {
                in.readAllBytes();
            }
            int code = p.waitFor();
            if (code != 0) {
                throw new IllegalStateException("keytool 生成测试证书失败，退出码 " + code);
            }

            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(ks)) {
                store.load(in, "changeit".toCharArray());
            }
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, "changeit".toCharArray());

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        }
    }
}
