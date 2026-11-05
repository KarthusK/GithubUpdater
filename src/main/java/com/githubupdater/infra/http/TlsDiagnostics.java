package com.githubupdater.infra.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 中间人代理识别。
 *
 * <p><b>为什么需要它</b>：PKIX 报错本身只说"证书链不受信任"，这对用户毫无价值——
 * 他不知道自己被谁劫持、该去关哪个软件。而实际最常见的原因就是本机的加速器 /
 * 企业 TLS 检查网关（实测本机是 Steamcommunity_302，它把 api.github.com 写进 hosts
 * 指向 127.0.0.1，再用自签 CA 重签）。
 *
 * <p><b>做法</b>：握手失败后，用一次独立的、<b>不验证证书</b>的连接重新抓取对端
 * 叶子证书，读出它的 Issuer。这只是读一个公开的、本来就要发给客户端的证书，
 * 不发起任何真实请求，也不降低主连接的安全等级——主连接已经失败了，
 * 这次探测只是去拿"为什么失败"的证据。
 *
 * <p><b>安全边界</b>：
 * <ul>
 *   <li>只在证书验证<b>已经失败之后</b>调用，不进入任何正常路径</li>
 *   <li>绝不因此放宽后续请求的校验——诊断与信任是两件事</li>
 *   <li>返回的信息只用于渲染提示文案，不落盘、不上报</li>
 *   <li>任何异常都返回 empty，绝不让原本的报错变得更糟</li>
 * </ul>
 */
public final class TlsDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(TlsDiagnostics.class);

    /** 探测超时。诊断是附带的，不能因为对端不响应而拖住整个检查流程。 */
    private static final int PROBE_TIMEOUT_MS = 3000;

    private TlsDiagnostics() {
    }

    /**
     * 诊断结果。
     *
     * @param issuer     对端证书的 Issuer，如 {@code CN=steamcommunity.com, O=Steamcommunity302, C=CN}
     * @param subject    对端证书的 Subject
     * @param selfSigned subject == issuer，说明是自签根，几乎必定是本地中间人
     * @param hint       给用户看的归因提示，为空串表示没有可补充的
     */
    public record Diagnosis(String issuer, String subject, boolean selfSigned, String hint) {
    }

    /**
     * 探测某主机的对端证书签发者。
     *
     * @param host     主机名
     * @param port     端口
     * @return 诊断结果；无法诊断时返回 empty（调用方按"未知原因"处理）
     */
    public static Optional<Diagnosis> probe(String host, int port) {
        if (host == null || host.isBlank() || port <= 0 || port > 65535) {
            return Optional.empty();
        }
        try {
            SSLSocketFactory factory = trustAllContext().getSocketFactory();
            try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
                socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
                socket.setSoTimeout(PROBE_TIMEOUT_MS);
                socket.startHandshake();

                var session = socket.getSession();
                if (session == null) {
                    return Optional.empty();
                }
                java.security.cert.Certificate[] peers = session.getPeerCertificates();
                if (peers == null || peers.length == 0 || !(peers[0] instanceof X509Certificate leaf)) {
                    return Optional.empty();
                }

                String subject = leaf.getSubjectX500Principal().getName();
                String issuer = leaf.getIssuerX500Principal().getName();
                boolean selfSigned = leaf.getSubjectX500Principal().equals(leaf.getIssuerX500Principal());
                return Optional.of(new Diagnosis(issuer, subject, selfSigned, hintFor(issuer)));
            }
        } catch (Exception e) {
            // 连不上（例如 hosts 指向本地但代理没跑）、超时、握手被拒——
            // 都只是"诊断不出来"，不是新错误
            log.debug("中间人诊断失败（{}:{}）：{}", host, port, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 把签发者映射成用户能认出来的软件名。
     *
     * <p>刻意做成前缀匹配的软提示，不参与任何决策——签发者字符串是对方可控的，
     * 绝不能因为它"看起来像 Steam"就改变信任行为。
     */
    private static String hintFor(String issuer) {
        if (issuer == null) {
            return "";
        }
        String lower = issuer.toLowerCase(java.util.Locale.ROOT);
        for (var rule : KNOWN_MITM) {
            if (lower.contains(rule.keyword())) {
                return rule.hint();
            }
        }
        return "";
    }

    private record KnownMitm(String keyword, String hint) {
    }

    /**
     * 已知的中间人软件特征。
     *
     * <p>只用于让提示更具体（"像是 Steamcommunity_302 这类加速工具"），
     * 缺少匹配时仍会如实给出签发者原文，不影响功能。
     */
    private static final List<KnownMitm> KNOWN_MITM = List.of(
            new KnownMitm("steamcommunity302",
                    "这通常是 Steamcommunity_302 这类 Steam 加速工具在解密 HTTPS"),
            new KnownMitm("steamtools",
                    "这通常是 SteamTools 这类工具在解密 HTTPS"),
            new KnownMitm("charles", "这通常是 Charles 抓包代理在解密 HTTPS"),
            new KnownMitm("fiddler", "这通常是 Fiddler 抓包代理在解密 HTTPS"),
            new KnownMitm("mitmproxy", "这通常是 mitmproxy 在解密 HTTPS"),
            new KnownMitm("zscaler", "这通常是企业网络的安全网关在做 TLS 检查"),
            new KnownMitm("netskope", "这通常是企业网络的安全网关在做 TLS 检查"),
            new KnownMitm("kaspersky", "这通常是杀毒软件的 HTTPS 扫描功能在解密流量"),
            new KnownMitm("avast", "这通常是杀毒软件的 HTTPS 扫描功能在解密流量"),
            new KnownMitm("eset", "这通常是杀毒软件的 HTTPS 扫描功能在解密流量")
    );

    /** 只用于读取对端证书的信任管理器，不验证任何东西。 */
    private static SSLContext trustAllContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // 探测专用：不验证
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // 探测专用：不验证。主连接已经因证书失败，这里只为读出它是谁签的。
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return context;
    }

    /**
     * 判断一个异常是否是"证书与主机名不匹配"。
     *
     * <p>与"证书链不受信任"是两件事，但根因往往相同：本机代理重签了证书，
     * 却没用对域名重新签（或者只签了别的域名）。报错是
     * {@code No name matching api.github.com found}，看起来像 DNS 问题，
     * 实际上与 DNS 毫无关系——所以必须单独识别并说清楚。
     *
     * @param error 异常
     * @return 是主机名不匹配返回 true
     */
    public static boolean isHostnameMismatch(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("no name matching")
                        || lower.contains("no subject alternative")
                        || lower.contains("hostnameverifier")
                        || lower.contains("doesn't match the certificate"))
                    return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 判断一个异常是否属于"证书层面的问题"（不受信任或不匹配）。
     *
     * <p>两者对用户的含义是一样的：本机的中间人在解密 HTTPS，但它的证书
     * 本程序不认。分开判断是为了给出更精确的文案，合起来判断是为了让
     * 上层只需要问一次"要不要做 TLS 诊断"。
     */
    public static boolean isTlsCertificateProblem(Throwable error) {
        return isCertificateUntrusted(error) || isHostnameMismatch(error);
    }

    /**
     * 判断一个异常是否是"证书链不受信任"。
     *
     * <p>沿 cause 链向下找，因为实测结构是
     * {@code SSLHandshakeException -> SSLHandshakeException -> ValidatorException -> CertPathBuilderException}，
     * 顶层只有 {@code SSLHandshakeException}，光看顶层无法与"协议版本不匹配"之类的
     * 其它握手失败区分开。
     *
     * @param error 异常
     * @return 是证书信任问题返回 true
     */
    public static boolean isCertificateUntrusted(Throwable error) {
        Throwable current = error;
        // 加深度上限：异常的 cause 链有可能成环，不能无限走
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof java.security.cert.CertPathBuilderException
                    || current instanceof java.security.cert.CertPathValidatorException
                    || current instanceof CertificateException) {
                // CertificateException 覆盖面偏宽，所以还要确认是信任问题而不是别的证书错误
                String msg = current.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("pkix path building failed")
                            || lower.contains("unable to find valid certification path")
                            || lower.contains("certificate_unknown")
                            || lower.contains("unable to find acceptable"))
                        return true;
                }
                if (current instanceof java.security.cert.CertPathBuilderException) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
