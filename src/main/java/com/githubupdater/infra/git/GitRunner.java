package com.githubupdater.infra.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.TagOpt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Git 操作封装（基于 JGit）。
 *
 * <p><b>为什么用 JGit 而不是系统 git CLI</b>（ADR-007）：JGit 核心 2.8MB、
 * 纯 Java、许可证 BSD 系。git CLI 方案要么要求用户装 git，要么捆绑
 * GPLv2 的 Git for Windows。
 *
 * <p><b>Windows 平台陷阱</b>（必须处理，否则会出现难以理解的失败）：
 * <ol>
 *   <li>仓库与 {@code FileBasedConfig}（默认 {@code ~/.config/jgit/config}）
 *       位于<b>不同盘符</b>时，首次写文件系统属性会死锁。规避：显式把配置路径
 *       指向与仓库同盘的位置。</li>
 *   <li>进程被杀后 {@code .lock} 文件残留（{@code index.lock}、
 *       {@code packed-refs.lock}），导致后续操作全部失败。启动时清理陈旧锁。</li>
 *   <li>文件被其他进程（杀软、编辑器）打开时 checkout 失败，需要重试。</li>
 *   <li>{@code core.autocrlf} 与 CLI 行为有差异，clone 时显式设定。</li>
 * </ol>
 */
public final class GitRunner {

    private static final Logger log = LoggerFactory.getLogger(GitRunner.class);

    /** checkout 遇到文件占用时的重试次数。 */
    private static final int CHECKOUT_RETRIES = 3;

    /** 陈旧锁的判定阈值。 */
    private static final long STALE_LOCK_MS = 60 * 60 * 1000L;   // 1 小时

    private GitRunner() {
    }

    /**
     * 配置 JGit 的用户配置文件路径。
     *
     * <p>必须在任何 Git 操作之前调用。传入与仓库同盘的数据目录，
     * 避免跨盘死锁。
     */
    public static void configureUserConfig(Path dataDir) {
        try {
            Path configDir = dataDir.resolve("jgit");
            Files.createDirectories(configDir);
            Path userConfig = configDir.resolve("user.config");
            System.setProperty("org.eclipse.jgit.util.FS_CONFIG", userConfig.toString());
            log.debug("JGit 用户配置指向：{}", userConfig);
        } catch (IOException e) {
            log.warn("设置 JGit 配置路径失败：{}", e.getMessage());
        }
    }

    /** 操作结果。 */
    public sealed interface Result permits Ok, Dirty, Failed {
    }

    /** 成功。{@code message} 描述做了什么。 */
    public record Ok(String message) implements Result {
    }

    /**
     * 工作副本有未提交改动，已中止。
     *
     * <p>这是<b>刻意设计</b>：绝不 stash/reset 用户的改动。
     */
    public record Dirty(List<String> changedFiles) implements Result {
    }

    /** 失败。 */
    public record Failed(String message, Throwable cause) implements Result {
    }

    /**
     * 更新源码工作副本到指定 tag。
     *
     * @param repoDir   本地工作副本目录
     * @param cloneUrl  clone 地址（目录不存在时使用）
     * @param tag       目标 tag
     * @param branch    tag 为空时的回退分支（通常是默认分支）
     */
    public static Result updateToTag(Path repoDir, String cloneUrl, String tag, String branch) {
        boolean fresh = !Files.exists(repoDir.resolve(".git"));

        if (fresh) {
            return clone(repoDir, cloneUrl, tag);
        }

        cleanupStaleLocks(repoDir);

        try (Git git = Git.open(repoDir.toFile())) {
            // 1. 检查本地改动 —— 有就中止，绝不 stash/reset
            Status status = git.status().call();
            if (!status.isClean()) {
                List<String> changed = new ArrayList<>();
                changed.addAll(status.getModified());
                changed.addAll(status.getUntracked());
                changed.addAll(status.getAdded());
                changed.addAll(status.getMissing());
                log.info("工作副本有 {} 处未提交改动，中止更新", changed.size());
                return new Dirty(changed);
            }

            // 2. fetch（带 tag）
            log.info("fetch {} ...", repoDir.getFileName());
            git.fetch()
                    .setRemote("origin")
                    .setTagOpt(TagOpt.FETCH_TAGS)
                    .call();

            // 3. checkout 到 tag（或分支）
            String ref = (tag != null && !tag.isBlank()) ? "refs/tags/" + tag : branch;
            if (ref == null || ref.isBlank()) {
                return new Failed("未指定 tag 或分支", null);
            }

            checkoutWithRetry(git, ref);
            return new Ok("已更新到 " + ref);

        } catch (GitAPIException | IOException e) {
            return new Failed("git 操作失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            // JGit 内部会抛 JGitInternalException 等 RuntimeException，
            // 不兜住就会变成 HTTP 500 且用户看不到原因
            return new Failed("git 内部错误：" + e.getMessage(), e);
        }
    }

    private static Result clone(Path repoDir, String cloneUrl, String tag) {
        // JGit 的 CloneCommand 遇到"目录已存在且非空"会抛 JGitInternalException，
        // 而那不是 GitAPIException，不会被常规 catch 捕获，最终变成 500。
        // 这里提前判掉，给出可操作的错误信息。
        if (Files.exists(repoDir)) {
            try (var stream = Files.list(repoDir)) {
                if (stream.findAny().isPresent()) {
                    return new Failed("目标目录已存在且非空，但不是一个 git 仓库："
                            + repoDir + "。请清空该目录，或把它指向一个空目录 / 已有仓库", null);
                }
            } catch (IOException e) {
                return new Failed("检查目标目录失败：" + e.getMessage(), e);
            }
        }

        try {
            Path parent = repoDir.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // core.autocrlf 必须在 clone 之前就生效。写成 clone 完再设是无效的：
            // clone 时已按默认值处理了换行，之后所有文本文件都会被判为 modified，
            // 于是下一次更新被误判为 Dirty 而永远中止（实测踩到）。
            // CloneCommand 没有 setConfig，只能在 clone 前写到 JGit 的配置文件。
            setAutocrlfBeforeClone();

            log.info("clone {} -> {}", cloneUrl, repoDir);

            CloneCommand command = Git.cloneRepository()
                    .setURI(cloneUrl)
                    .setDirectory(repoDir.toFile())
                    .setCloneAllBranches(true);

            // 直接 clone 到目标 tag。
            //
            // 不能"clone 完再 checkout"：默认分支的文件已全部落地，再切 tag 会报
            // "Checkout conflict with files"（实测踩到）。
            // 也不能用 setNoCheckout(true) 规避：那样工作区是空的，但 JGit 的
            // DirCache 未初始化，checkout 依然报同一个冲突（实测矩阵验证）。
            // 正确做法是 setBranch 直接指定 refs/tags/<tag>，JGit 会以 detached HEAD
            // 检出该 tag 的内容。
            if (tag != null && !tag.isBlank()) {
                command.setBranch("refs/tags/" + tag);
            }

            try (Git git = command.call()) {
                setAutocrlfFalse(git);
                return new Ok("已 clone 到 " + repoDir.getFileName()
                        + ((tag == null || tag.isBlank()) ? "" : "，并检出 " + tag));
            }
        } catch (GitAPIException | IOException e) {
            return new Failed("clone 失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            return new Failed("clone 失败（JGit 内部错误）：" + e.getMessage(), e);
        }
    }

    /**
     * 在 clone 之前把 {@code core.autocrlf=false} 写进 JGit 的用户配置。
     *
     * <p>只影响本工具自己的 JGit 配置（{@link #configureUserConfig} 已把它指向
     * 数据目录），不会污染用户全局的 git 配置。
     */
    private static void setAutocrlfBeforeClone() {
        try {
            var config = org.eclipse.jgit.util.SystemReader.getInstance().getUserConfig();
            config.setString("core", null, "autocrlf", "false");
            config.save();
        } catch (Exception e) {
            log.warn("clone 前写入 core.autocrlf 失败：{}", e.getMessage());
        }
    }

    /**
     * 把仓库级 {@code core.autocrlf} 设为 false。
     *
     * <p>JGit 与 git CLI 对该项默认值处理不同，不显式设定会出现"文件明明没改，
     * 却被判定为全量修改"，进而让 {@link #updateToTag} 误判为 Dirty 而中止。
     */
    private static void setAutocrlfFalse(Git git) {
        try {
            var config = git.getRepository().getConfig();
            config.setString("core", null, "autocrlf", "false");
            config.save();
        } catch (IOException e) {
            log.warn("写入 core.autocrlf 失败：{}", e.getMessage());
        }
    }

    /**
     * checkout，带重试。
     *
     * <p>Windows 上文件被短暂占用（杀软扫描、编辑器）会导致失败，
     * 重试通常能成功。
     */
    private static void checkoutWithRetry(Git git, String ref) throws GitAPIException {
        GitAPIException last = null;
        for (int attempt = 1; attempt <= CHECKOUT_RETRIES; attempt++) {
            try {
                git.checkout().setName(ref).call();
                log.debug("checkout {} 成功（第 {} 次）", ref, attempt);
                return;
            } catch (GitAPIException e) {
                last = e;
                log.warn("checkout {} 第 {} 次失败：{}", ref, attempt, e.getMessage());
                if (attempt < CHECKOUT_RETRIES) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }

    /**
     * 清理陈旧的锁文件。
     *
     * <p>进程被杀会留下 {@code index.lock} 等文件，让后续所有 Git 操作失败。
     * 只删除超过阈值的，避免误删另一个正在进行的操作。
     */
    static void cleanupStaleLocks(Path repoDir) {
        List<Path> candidates = List.of(
                repoDir.resolve(".git").resolve("index.lock"),
                repoDir.resolve(".git").resolve("HEAD.lock"),
                repoDir.resolve(".git").resolve("packed-refs.lock"),
                repoDir.resolve(".git").resolve("config.lock")
        );
        long now = System.currentTimeMillis();
        for (Path lock : candidates) {
            try {
                if (Files.exists(lock)) {
                    long age = now - Files.getLastModifiedTime(lock).toMillis();
                    if (age > STALE_LOCK_MS) {
                        Files.deleteIfExists(lock);
                        log.warn("清理陈旧锁文件：{}（已存在 {} 分钟）", lock.getFileName(), age / 60000);
                    }
                }
            } catch (IOException e) {
                log.debug("检查锁文件失败：{}", e.getMessage());
            }
        }
    }

    /**
     * 读取仓库当前 HEAD 的描述（用于显示）。
     */
    public static String describeHead(Path repoDir) {
        if (!Files.exists(repoDir.resolve(".git"))) {
            return "（非 git 仓库）";
        }
        try (Git git = Git.open(repoDir.toFile())) {
            Repository repo = git.getRepository();
            String branch = repo.getBranch();
            var head = repo.resolve("HEAD");
            String hash = head == null ? "?" : head.getName().substring(0, 8);
            return branch + "@" + hash;
        } catch (Exception e) {
            return "（读取失败：" + e.getMessage() + "）";
        }
    }
}
