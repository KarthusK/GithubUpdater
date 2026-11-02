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
        return this;
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
