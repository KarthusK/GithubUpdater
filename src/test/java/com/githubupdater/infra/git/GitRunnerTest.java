package com.githubupdater.infra.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitRunner：用<b>本地仓库</b>验证，不依赖 GitHub 网络。
 *
 * <p>这些用例锁定三个实测踩到的坑：
 * <ol>
 *   <li>clone 完再 checkout 到 tag 会报 "Checkout conflict with files"</li>
 *   <li>{@code core.autocrlf} 写在 clone 之后无效，会让所有文本文件被误判为 modified</li>
 *   <li>工作副本有改动必须中止，绝不 stash/reset</li>
 * </ol>
 */
class GitRunnerTest {

    @TempDir
    Path tempDir;

    private Path origin;
    private Path dataDir;

    /** 用系统 git 造一个带两个 tag 的本地仓库，作为 clone 源。 */
    @BeforeEach
    void setUp() throws Exception {
        origin = tempDir.resolve("origin");
        Files.createDirectories(origin);
        dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);

        git("init", "-q", ".");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "tester");

        // 用 CRLF 内容，让 autocrlf 的坑能被暴露出来
        for (int i = 1; i <= 30; i++) {
            Files.writeString(origin.resolve("f" + i + ".txt"),
                    "line1\r\nline2 " + i + "\r\n", StandardCharsets.UTF_8);
        }
        git("add", "-A");
        git("commit", "-q", "-m", "v1");
        git("tag", "v1.0");

        for (int i = 1; i <= 30; i++) {
            Files.writeString(origin.resolve("f" + i + ".txt"),
                    "v2 line1\r\nv2 line2 " + i + "\r\n", StandardCharsets.UTF_8);
        }
        git("add", "-A");
        git("commit", "-q", "-m", "v2");
        git("tag", "v2.0");
    }

    private void git(String... args) throws Exception {
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(origin.toString());
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败：" + out);
        }
    }

    @Test
    @DisplayName("clone 应直接检出目标 tag，且不报 Checkout conflict")
    void cloneChecksOutTag() {
        Path work = tempDir.resolve("work");
        GitRunner.configureUserConfig(dataDir);

        GitRunner.Result r = GitRunner.updateToTag(work, origin.toString(), "v1.0", null);

        assertTrue(r instanceof GitRunner.Ok, "应成功，实际：" + r);
        assertTrue(Files.exists(work.resolve("f1.txt")));
        try {
            assertTrue(Files.readString(work.resolve("f1.txt")).contains("line2 1"),
                    "应检出 v1.0 的内容");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("刚 clone 完不应被误判为有本地改动（core.autocrlf 必须在 clone 前生效）")
    void freshCloneIsClean() {
        Path work = tempDir.resolve("work2");
        GitRunner.configureUserConfig(dataDir);

        GitRunner.Result first = GitRunner.updateToTag(work, origin.toString(), "v1.0", null);
        assertTrue(first instanceof GitRunner.Ok, "首次 clone 应成功，实际：" + first);

        // 再更新一次：若 autocrlf 处理不当，这里会返回 Dirty
        GitRunner.Result second = GitRunner.updateToTag(work, origin.toString(), "v2.0", null);
        assertTrue(second instanceof GitRunner.Ok,
                "刚 clone 的仓库必须干净，实际：" + second);
    }

    @Test
    @DisplayName("应能在两个 tag 之间来回切换")
    void switchesBetweenTags() throws Exception {
        Path work = tempDir.resolve("work3");
        GitRunner.configureUserConfig(dataDir);

        assertTrue(GitRunner.updateToTag(work, origin.toString(), "v1.0", null)
                instanceof GitRunner.Ok);
        String head1 = GitRunner.describeHead(work);

        assertTrue(GitRunner.updateToTag(work, origin.toString(), "v2.0", null)
                instanceof GitRunner.Ok);
        String head2 = GitRunner.describeHead(work);
        assertFalse(head1.equals(head2), "切到不同 tag 后 HEAD 必须变化");
        assertTrue(Files.readString(work.resolve("f1.txt")).contains("v2 line2 1"));

        assertTrue(GitRunner.updateToTag(work, origin.toString(), "v1.0", null)
                instanceof GitRunner.Ok);
        assertEquals(head1, GitRunner.describeHead(work), "切回后 HEAD 应与首次一致");
    }

    @Test
    @DisplayName("工作副本有改动必须返回 Dirty，且不改动用户的文件")
    void dirtyWorkingTreeAborts() throws Exception {
        Path work = tempDir.resolve("work4");
        GitRunner.configureUserConfig(dataDir);
        assertTrue(GitRunner.updateToTag(work, origin.toString(), "v1.0", null)
                instanceof GitRunner.Ok);

        String headBefore = GitRunner.describeHead(work);
        Path userFile = work.resolve("f1.txt");
        Files.writeString(userFile, "MY LOCAL EDIT", StandardCharsets.UTF_8);

        GitRunner.Result r = GitRunner.updateToTag(work, origin.toString(), "v2.0", null);

        assertTrue(r instanceof GitRunner.Dirty, "应返回 Dirty，实际：" + r);
        assertEquals("MY LOCAL EDIT", Files.readString(userFile),
                "用户的改动绝不能被 stash/reset");
        assertEquals(headBefore, GitRunner.describeHead(work), "HEAD 不应变化");
    }

    @Test
    @DisplayName("目标目录非空且不是 git 仓库时应给出可操作错误，而不是抛异常")
    void nonEmptyNonGitDir() throws Exception {
        Path work = tempDir.resolve("notgit");
        Files.createDirectories(work);
        Files.writeString(work.resolve("user.txt"), "data", StandardCharsets.UTF_8);

        GitRunner.Result r = GitRunner.updateToTag(work, origin.toString(), "v1.0", null);

        assertTrue(r instanceof GitRunner.Failed, "应失败，实际：" + r);
        assertTrue(((GitRunner.Failed) r).message().contains("非空"));
        assertEquals("data", Files.readString(work.resolve("user.txt")));
    }

    @Test
    @DisplayName("空目录应能正常 clone（不算冲突）")
    void emptyDirIsFine() throws Exception {
        Path work = tempDir.resolve("empty");
        Files.createDirectories(work);

        GitRunner.Result r = GitRunner.updateToTag(work, origin.toString(), "v1.0", null);

        assertTrue(r instanceof GitRunner.Ok, "空目录应能 clone，实际：" + r);
    }

    @Test
    @DisplayName("非 git 目录的描述应说明情况，不抛异常")
    void describeHeadOnNonRepo() {
        Path plain = tempDir.resolve("plain");
        assertEquals("（非 git 仓库）", GitRunner.describeHead(plain));
    }

    @Test
    @DisplayName("陈旧锁文件应被清理，新鲜锁文件应保留")
    void cleansOnlyStaleLocks() throws Exception {
        Path work = tempDir.resolve("lockrepo");
        Files.createDirectories(work.resolve(".git"));
        Path stale = work.resolve(".git/index.lock");
        Path fresh = work.resolve(".git/HEAD.lock");
        Files.writeString(stale, "x");
        Files.writeString(fresh, "x");

        // 把 stale 的 mtime 调到 2 小时前
        Files.setLastModifiedTime(stale,
                java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() - 2 * 60 * 60 * 1000L));

        GitRunner.cleanupStaleLocks(work);

        assertFalse(Files.exists(stale), "超过 1 小时的锁应被清理");
        assertTrue(Files.exists(fresh), "新鲜的锁必须保留（可能有另一个操作在进行）");
    }
}
