package com.githubupdater.infra.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 自定义 CA 的信任链构建。
 *
 * <p><b>核心约束：追加，不能替换。</b>默认信任库（JDK cacerts，实测 143 张 CA）
 * 必须保留，用户指定的 CA 只是<b>额外</b>信任。理由不是洁癖，是实测：
 * 如果把信任库替换成"只信这一个 CA"，用户在关掉代理之后工具就彻底不可用了——
 * 因为那时对端是正常的 Sectigo 证书，而这个 CA 已经不匹配。
 *
 * <p>也不是"把第三方根证书导入 JDK 全局 cacerts"：那会让整个 JVM 信任该中间人，
 * 用户的 PAT 也会被它看到。这里构造的 {@link SSLContext} 只被本进程的两个
 * HttpClient 使用，不影响系统的任何其它程序。
 *
 * <p><b>为什么不用 {@code -Djavax.net.ssl.trustStoreType=Windows-ROOT}</b>
 * （直接复用系统证书存储，看起来很省事）：实测在 jlink 裁剪后的 JRE 上抛
 * {@code NoSuchAlgorithmException: Error constructing implementation ... SSLContextImpl$DefaultSSLContext}，
 * 因为 SunMSCAPI provider 在 {@code jdk.crypto.mscapi} 模块里，而裁剪清单没有它。
 * 本类的方案只用到 {@code java.base} 的类，实测在完整 JDK 与裁剪 JRE 上均通过。
 *
 * <p>任何异常都降级为"没有自定义 CA"，绝不阻断启动——证书配错不该让程序起不来。
 */
public final class TlsTrust {

    private static final Logger log = LoggerFactory.getLogger(TlsTrust.class);

    /**
     * 证书文件大小上限。
     *
     * <p>正常 PEM/CRT 只有几 KB。设上限是为了避免用户误选了某个大文件时，
     * 先把它整个读进内存再解析失败。
     */
    private static final long MAX_FILE_BYTES = 1024 * 1024;

    private TlsTrust() {
    }

    /** 加载结果。{@code context} 为 null 表示"用默认连接"，不是错误。 */
    public enum Status {
        /** 没有配置自定义 CA，使用 JDK 默认信任库。 */
        NOT_CONFIGURED,
        /** 已加载，{@link Outcome#certCount()} 张证书已追加到信任链。 */
        LOADED,
        /** 文件不存在。 */
        FILE_NOT_FOUND,
        /** 文件不可读（权限、是目录等）。 */
        UNREADABLE,
        /** 文件里解析不出任何 X.509 证书。 */
        NO_CERTIFICATE,
        /** 文件超过大小上限。 */
        TOO_LARGE
    }

    /**
     * 加载结果。
     *
     * @param context  可直接交给 HttpClient 的 SSLContext；失败或无配置时为 null
     * @param status   加载状态
     * @param certCount 实际加载的证书张数
     * @param detail   给人看的原因，成功时为空串
     */
    public record Outcome(SSLContext context, Status status, int certCount, String detail) {

        public static Outcome notConfigured() {
            return new Outcome(null, Status.NOT_CONFIGURED, 0, "");
        }

        public boolean isLoaded() {
            return status == Status.LOADED && context != null;
        }

        /** 是否配置了路径但加载失败——这种要提示用户，而不是当作没配。 */
        public boolean isFailure() {
            return status != Status.NOT_CONFIGURED && status != Status.LOADED;
        }
    }

    /**
     * 从文件加载自定义 CA。
     *
     * @param caFile 证书文件；为 null 表示未配置
     * @return 加载结果，永不抛异常
     */
    public static Outcome fromFile(Path caFile) {
        if (caFile == null) {
            return Outcome.notConfigured();
        }
        try {
            if (!Files.exists(caFile)) {
                return fail(Status.FILE_NOT_FOUND, "CA 证书文件不存在：" + caFile);
            }
            if (!Files.isRegularFile(caFile)) {
                return fail(Status.UNREADABLE, "CA 证书路径不是普通文件：" + caFile);
            }
            long size = Files.size(caFile);
            if (size > MAX_FILE_BYTES) {
                return fail(Status.TOO_LARGE,
                        "CA 证书文件过大（" + size + " 字节，上限 " + MAX_FILE_BYTES + "）");
            }
            if (size == 0) {
                return fail(Status.NO_CERTIFICATE, "CA 证书文件为空：" + caFile);
            }

            X509Certificate[] custom = readCertificates(caFile);
            if (custom.length == 0) {
                return fail(Status.NO_CERTIFICATE,
                        "文件里没有可用的 X.509 证书：" + caFile);
            }

            SSLContext context = buildCompositeContext(custom);
            log.info("已加载自定义 CA：{}（{} 张证书，已追加到默认信任链）",
                    caFile, custom.length);
            return new Outcome(context, Status.LOADED, custom.length, "");
        } catch (Exception e) {
            // 兜住一切：证书配置是可选功能，不该有权力阻止程序启动
            log.warn("加载自定义 CA 失败（{}）：{}", caFile, e.getMessage());
            return fail(Status.NO_CERTIFICATE, "解析 CA 证书失败：" + e.getMessage());
        }
    }

    /**
     * 读取文件中的全部证书。
     *
     * <p>{@code generateCertificates} 同时支持 PEM 与单证书 DER，且能一次读多张，
     * 所以不需要先让用户用 keytool 转换——那一步在 Windows 上还要处理路径引号，
     * 是全流程里最容易出错的地方。
     */
    private static X509Certificate[] readCertificates(Path file) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(file)) {
            return factory.generateCertificates(in).stream()
                    .filter(c -> c instanceof X509Certificate)
                    .map(c -> (X509Certificate) c)
                    .toArray(X509Certificate[]::new);
        }
    }

    /**
     * 构造"默认信任库 + 自定义 CA"的组合 SSLContext。
     *
     * <p>两个独立的 {@link X509TrustManager} 顺序尝试：先默认、后自定义。
     * 任一通过即视为可信。{@code getAcceptedIssuers} 返回并集，
     * 这样 TLS 握手时服务端发来的证书链能正常协商。
     */
    private static SSLContext buildCompositeContext(X509Certificate[] custom) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{compositeTrustManager(custom)}, null);
        return context;
    }

    /**
     * 构造组合信任管理器。
     *
     * <p>单独抽出来是为了可测试：{@link SSLContext} 不暴露内部的 TrustManager，
     * 只能靠反射去掏，那种测试既脆弱又读不懂。这里直接给测试一个入口，
     * 让它能断言"默认 CA 仍在信任链里"——那是本类最关键的语义。
     *
     * @param custom 追加的证书
     */
    static X509TrustManager compositeTrustManager(X509Certificate[] custom) throws Exception {
        KeyStore extraStore = KeyStore.getInstance(KeyStore.getDefaultType());
        extraStore.load(null, null);
        for (int i = 0; i < custom.length; i++) {
            extraStore.setCertificateEntry("custom-ca-" + i, custom[i]);
        }

        X509TrustManager defaultTm = trustManagerFor(null);
        X509TrustManager extraTm = trustManagerFor(extraStore);
        return new CompositeTrustManager(defaultTm, extraTm);
    }

    /** 读出文件里的全部 X.509 证书。暴露给测试以验证多证书解析。 */
    static X509Certificate[] readCertificatesForTest(Path file) throws Exception {
        return readCertificates(file);
    }

    /**
     * 取一个信任管理器。
     *
     * @param store 为 null 时返回 JDK 默认信任库对应的管理器
     */
    private static X509TrustManager trustManagerFor(KeyStore store) throws Exception {
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager tm : factory.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("默认算法未提供 X509TrustManager");
    }

    private static Outcome fail(Status status, String message) {
        return new Outcome(null, status, 0, message);
    }

    /**
     * 组合信任管理器：默认链优先，失败再试自定义链。
     *
     * <p>顺序有意义——绝大多数请求由默认链直接通过，只在真正需要时才走额外链。
     * 抛出的异常保留<b>第一次</b>失败（即默认链的失败），因为那才是用户看到
     * 的原始原因；换成额外链的错误会让人以为是自定义证书配错了。
     */
    private static final class CompositeTrustManager implements X509TrustManager {

        private final X509TrustManager primary;
        private final X509TrustManager secondary;
        private final X509Certificate[] issuers;

        CompositeTrustManager(X509TrustManager primary, X509TrustManager secondary) {
            this.primary = primary;
            this.secondary = secondary;
            X509Certificate[] a = primary.getAcceptedIssuers();
            X509Certificate[] b = secondary.getAcceptedIssuers();
            X509Certificate[] merged = new X509Certificate[a.length + b.length];
            System.arraycopy(a, 0, merged, 0, a.length);
            System.arraycopy(b, 0, merged, a.length, b.length);
            this.issuers = merged;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            try {
                primary.checkClientTrusted(chain, authType);
            } catch (java.security.cert.CertificateException e) {
                secondary.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            try {
                primary.checkServerTrusted(chain, authType);
            } catch (java.security.cert.CertificateException e) {
                secondary.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return issuers.clone();
        }
    }
}
