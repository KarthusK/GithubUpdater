package com.githubupdater.infra.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文件替换：备份、原子替换、回滚、备份清理。 */
class FileReplacerTest {

    @TempDir
    Path tempDir;

    private static Path write(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    @DisplayName("替换后目标内容为新内容，且留下备份")
    void replaceCreatesBackup() throws Exception {
        Path target = write(tempDir.resolve("app.exe"), "old");
        Path ready = write(tempDir.resolve("app.exe.new"), "new");

        FileReplacer replacer = new FileReplacer(2);
        ReplaceResult r = replacer.replace(ready, target, false);

        assertTrue(r instanceof ReplaceResult.Success, "应替换成功，实际：" + r);
        ReplaceResult.Success s = (ReplaceResult.Success) r;
        assertEquals("new", Files.readString(target));
        assertNotNull(s.backup(), "必须留下备份");
        assertEquals("old", Files.readString(s.backup()));
        assertFalse(Files.exists(ready), "暂存文件应已被移走");
    }

    @Test
    @DisplayName("目标不存在时应直接创建，不产生备份")
    void replaceWithoutExistingTarget() throws Exception {
        Path target = tempDir.resolve("fresh.exe");
        Path ready = write(tempDir.resolve("fresh.exe.new"), "content");

        ReplaceResult r = new FileReplacer(2).replace(ready, target, false);

        assertTrue(r instanceof ReplaceResult.Success);
        assertEquals("content", Files.readString(target));
        assertEquals(null, ((ReplaceResult.Success) r).backup());
    }

    @Test
    @DisplayName("暂存文件缺失应返回失败而不是抛异常")
    void missingReadyFails() {
        ReplaceResult r = new FileReplacer(2)
                .replace(tempDir.resolve("nope.ready"), tempDir.resolve("x.exe"), false);
        assertTrue(r instanceof ReplaceResult.Failed);
        assertTrue(((ReplaceResult.Failed) r).message().contains("暂存文件不存在"));
    }

    @Test
    @DisplayName("回滚应把备份内容写回目标，并另存当前文件")
    void rollbackRestoresBackup() throws Exception {
        Path target = write(tempDir.resolve("tool.exe"), "v1");
        Path ready = write(tempDir.resolve("tool.exe.new"), "v2");

        FileReplacer replacer = new FileReplacer(2);
        ReplaceResult.Success s = (ReplaceResult.Success) replacer.replace(ready, target, false);
        assertEquals("v2", Files.readString(target));

        ReplaceResult rb = replacer.rollback(s.backup(), target);
        assertTrue(rb instanceof ReplaceResult.Success, "回滚应成功，实际：" + rb);
        assertEquals("v1", Files.readString(target), "回滚后应回到旧内容");
    }

    @Test
    @DisplayName("回滚不存在的备份应返回失败")
    void rollbackMissingBackupFails() {
        ReplaceResult r = new FileReplacer(2)
                .rollback(tempDir.resolve("gone.bak"), tempDir.resolve("t.exe"));
        assertTrue(r instanceof ReplaceResult.Failed);
    }

    @Test
    @DisplayName("keepBackups 生效：超出数量的旧备份应被清理")
    void prunesOldBackups() throws Exception {
        Path target = write(tempDir.resolve("app.exe"), "v0");
        FileReplacer replacer = new FileReplacer(1);

        for (int i = 1; i <= 3; i++) {
            Path ready = write(tempDir.resolve("in" + i + ".new"), "v" + i);
            ReplaceResult r = replacer.replace(ready, target, false);
            assertTrue(r instanceof ReplaceResult.Success);
            // 备份文件名带毫秒时间戳，连续调用可能撞名，稍作间隔
            Thread.sleep(5);
        }

        List<Path> backups;
        try (var stream = Files.list(tempDir)) {
            backups = stream
                    .filter(p -> p.getFileName().toString().startsWith("app.exe.bak."))
                    .toList();
        }
        assertEquals(1, backups.size(), "keepBackups=1 时只应保留一个备份");
        assertEquals("v3", Files.readString(target));
    }

    @Test
    @DisplayName("同卷判断：同目录为同卷")
    void sameVolumeDetection() {
        Path a = tempDir.resolve("a.bin");
        Path b = tempDir.resolve("b.bin");
        assertTrue(FileReplacer.isSameVolume(a, b));
    }

    @Test
    @DisplayName("备份路径应包含原文件名与时间戳")
    void backupPathFormat() {
        Path target = Path.of("D:/tools/jq.exe");
        Path backup = FileReplacer.backupPathFor(target);
        assertTrue(backup.getFileName().toString().startsWith("jq.exe.bak."));
        assertEquals(target.getParent(), backup.getParent(), "备份必须与目标同目录（同卷）");
    }
}
