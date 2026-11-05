package com.githubupdater.config;

/**
 * 应用配置，对应 {@code data/config.json}。
 *
 * <p>设计要点（见 ADR-003 与 docs/01-架构设计.md）：
 * <ul>
 *   <li>人类可读、可手改，开源项目的用户会直接改 JSON</li>
 *   <li>带 {@code schemaVersion} 支持迁移</li>
 *   <li><b>不含任何高频写入数据</b>（进度、任务状态在 SQLite）</li>
 * </ul>
 *
 * <p>用可变类而非 record：配置需要就地修改后保存，且 JSON 反序列化对可变类更宽容。
 */
public final class AppConfig {

    /** 当前配置 schema 版本。只做加法，旧配置必须能被新版本加载。 */
    public static final int CURRENT_SCHEMA = 1;

    public int schemaVersion = CURRENT_SCHEMA;

    /** PAT。为 null 或空表示匿名访问。 */
    public String githubToken;

    /** token 的存储方式：dpapi / plaintext。仅用于界面提示。 */
    public String tokenStorage = "plaintext";

    /** 镜像设置。默认全关——第三方代理本质是中间人，而下载的是可执行文件。 */
    public boolean mirrorEnabled = false;
    public String mirrorPrefix = "";

    /** 同时下载的任务数上限。默认 2，不并发分块。 */
    public int maxParallelTasks = 2;

    /** 全局限速，KB/s。0 表示不限速。 */
    public int globalRateLimitKBps = 0;

    /** 匿名模式下单次检查的条目上限，避免撞 60/h 配额。 */
    public int anonymousCheckBatchLimit = 50;

    /** 开机自启（写 HKCU 的 Run 项）。 */
    public boolean autoStart = false;

    /** BINARY 类保留的备份数量。 */
    public int keepBackups = 2;

    /**
     * 自定义 CA 证书文件路径。
     *
     * <p>用于中间人代理（加速器、企业 TLS 检查网关）环境：本机代理解密 HTTPS 后
     * 用自己的 CA 重签，而 Java 的默认信任库不认它。
     *
     * <p><b>这个证书是"追加"到默认信任链的，不是替换。</b>所以用户关掉代理之后
     * 即使这个字段还指着旧证书，也仍然能正常访问——这是刻意的设计，
     * 详见 {@code TlsTrust}。
     *
     * <p>相对路径按 {@code data/} 解析；null 表示未配置。
     */
    public String customCaFile;

    public AppConfig normalized() {
        if (maxParallelTasks < 1) {
            maxParallelTasks = 1;
        }
        if (maxParallelTasks > 8) {
            maxParallelTasks = 8;
        }
        if (globalRateLimitKBps < 0) {
            globalRateLimitKBps = 0;
        }
        if (anonymousCheckBatchLimit < 1) {
            anonymousCheckBatchLimit = 1;
        }
        if (keepBackups < 0) {
            keepBackups = 0;
        }
        if (tokenStorage == null || tokenStorage.isBlank()) {
            tokenStorage = "plaintext";
        }
        if (mirrorPrefix == null) {
            mirrorPrefix = "";
        }
        // 空白统一归 null：否则 "" 会被当成"配置了一个空路径"，
        // 在界面上显示成"已配置但加载失败"，与"未配置"混淆。
        if (customCaFile != null) {
            customCaFile = customCaFile.trim();
            if (customCaFile.isEmpty()) {
                customCaFile = null;
            }
        }
        return this;
    }

    /**
     * 解析自定义 CA 的绝对路径。
     *
     * <p>相对路径按 {@code data/} 解析——用户手写 config.json 时写
     * {@code "customCaFile": "ca.pem"} 应该指向数据目录里的文件，
     * 而不是进程工作目录（那里可能是任意位置）。
     *
     * @param dataDir 数据目录
     * @return 绝对路径；未配置时返回 null
     */
    public java.nio.file.Path resolveCustomCaFile(java.nio.file.Path dataDir) {
        if (customCaFile == null || customCaFile.isBlank()) {
            return null;
        }
        java.nio.file.Path p = java.nio.file.Path.of(customCaFile);
        if (p.isAbsolute()) {
            return p;
        }
        return dataDir.resolve(p).toAbsolutePath();
    }

    /** 是否配置了 token。界面上只用这个判断，不暴露 token 本身。 */
    public boolean hasToken() {
        return githubToken != null && !githubToken.isBlank();
    }

    /** 匿名模式下的实际上限。 */
    public int effectiveCheckLimit() {
        return hasToken() ? Integer.MAX_VALUE : anonymousCheckBatchLimit;
    }
}
