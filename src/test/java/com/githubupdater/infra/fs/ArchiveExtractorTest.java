package com.githubupdater.infra.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 解压：Zip Slip 防护、非空校验、覆盖策略。 */
class ArchiveExtractorTest {

    @TempDir
    Path tempDir;

    private static Path makeZip(Path zip, String[][] entries) throws Exception {
        try (OutputStream fos = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (String[] e : entries) {
                zos.putNextEntry(new ZipEntry(e[0]));
                zos.write(e[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }

    @Test
    @DisplayName("正常压缩包应解压出全部文件")
    void extractsNormally() throws Exception {
        Path zip = makeZip(tempDir.resolve("a.zip"), new String[][]{
                {"bin/tool.exe", "MZ"},
                {"README.txt", "hello"}
        });
        Path staging = tempDir.resolve("staging");

        ArchiveExtractor.ExtractResult r = ArchiveExtractor.extract(zip, staging);

        assertTrue(r.ok(), "应解压成功：" + r.message());
        assertEquals(2, r.fileCount());
        assertEquals("MZ", Files.readString(staging.resolve("bin/tool.exe")));
        assertEquals("hello", Files.readString(staging.resolve("README.txt")));
    }

    @Test
    @DisplayName("Zip Slip：越界条目应被跳过，不写到 staging 之外")
    void rejectsZipSlip() throws Exception {
        Path zip = makeZip(tempDir.resolve("evil.zip"), new String[][]{
                {"../../escaped.txt", "pwned"},
                {"ok.txt", "fine"}
        });
        Path staging = tempDir.resolve("staging");

        ArchiveExtractor.ExtractResult r = ArchiveExtractor.extract(zip, staging);

        assertTrue(r.ok(), "有一个合法条目，整体应算成功");
        assertFalse(Files.exists(tempDir.getParent().resolve("escaped.txt")),
                "绝不能写到 staging 之外");
        assertTrue(Files.exists(staging.resolve("ok.txt")));
    }

    @Test
    @DisplayName("绝对路径条目也应被拒绝")
    void rejectsAbsolutePath() throws Exception {
        Path zip = makeZip(tempDir.resolve("abs.zip"), new String[][]{
                {"C:/Windows/evil.dll", "pwned"},
                {"ok.txt", "fine"}
        });
        Path staging = tempDir.resolve("staging");

        ArchiveExtractor.ExtractResult r = ArchiveExtractor.extract(zip, staging);

        assertTrue(r.ok());
        assertFalse(Files.exists(Path.of("C:/Windows/evil.dll")));
    }

    @Test
    @DisplayName("空压缩包应被拒绝，避免清空目标目录")
    void rejectsEmptyArchive() throws Exception {
        Path zip = makeZip(tempDir.resolve("empty.zip"), new String[][]{});
        Path staging = tempDir.resolve("staging");

        ArchiveExtractor.ExtractResult r = ArchiveExtractor.extract(zip, staging);

        assertFalse(r.ok(), "空包必须拒绝");
        assertTrue(r.message().contains("为空"));
    }

    @Test
    @DisplayName("覆盖不应删除目标目录中多出的文件（用户的配置）")
    void swapKeepsExtraFiles() throws Exception {
        Path staging = tempDir.resolve("staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("app.exe"), "v2");

        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("app.exe"), "v1");
        Files.writeString(target.resolve("config.ini"), "user-settings");

        ReplaceResult r = ArchiveExtractor.swapInto(staging, target, 2);

        assertTrue(r instanceof ReplaceResult.Success, "覆盖应成功：" + r);
        assertEquals("v2", Files.readString(target.resolve("app.exe")));
        assertTrue(Files.exists(target.resolve("config.ini")),
                "用户放在目录里的配置绝不能被删");
        assertEquals("user-settings", Files.readString(target.resolve("config.ini")));
    }

    @Test
    @DisplayName("覆盖应留下旧目录备份")
    void swapCreatesBackup() throws Exception {
        Path staging = tempDir.resolve("staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("a.txt"), "new");

        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("a.txt"), "old");

        ReplaceResult.Success s = (ReplaceResult.Success) ArchiveExtractor.swapInto(staging, target, 2);

        assertTrue(s.backup() != null && Files.exists(s.backup()), "必须留下目录备份");
        assertEquals("old", Files.readString(s.backup().resolve("a.txt")));
    }
}
