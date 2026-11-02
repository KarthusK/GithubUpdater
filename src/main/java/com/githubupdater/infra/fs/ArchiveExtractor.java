package com.githubupdater.infra.fs;

import com.githubupdater.util.PathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 压缩包解压。
 *
 * <p><b>必须防 Zip Slip</b>：压缩包内的条目名可能是 {@code ../../evil.exe}，
 * 直接 resolve 会写到预期目录之外。这是压缩包处理的经典漏洞，
 * 用 {@link PathGuard#resolveSafely} 校验每个条目。
 *
 * <p>解压流程：解压到 staging → 校验非空 → 备份旧目录 → 替换。
 * 不做"镜像同步"（删除目标目录中多出的文件）——那对用户放在目录里的配置是灾难。
 */
public final class ArchiveExtractor {

    private static final Logger log = LoggerFactory.getLogger(ArchiveExtractor.class);

    /** 单个条目解压后的最大字节数，防止 zip 炸弹。 */
    private static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;   // 2GB

    /** 条目总数上限。 */
    private static final int MAX_ENTRIES = 100_000;

    private ArchiveExtractor() {
    }

    /** 解压结果。 */
    public record ExtractResult(boolean ok, Path stagingDir, int fileCount,
                                long totalBytes, String message) {

        static ExtractResult failed(String message) {
            return new ExtractResult(false, null, 0, 0, message);
        }
    }

    /**
     * 解压到 staging 目录。
     *
     * @param archive  压缩包
     * @param staging  目标暂存目录（会被创建；已存在则清空）
     */
    public static ExtractResult extract(Path archive, Path staging) {
        try {
            if (Files.exists(staging)) {
                deleteRecursively(staging);
            }
            Files.createDirectories(staging);
        } catch (IOException e) {
            return ExtractResult.failed("创建暂存目录失败：" + e.getMessage());
        }

        int count = 0;
        long total = 0;
        List<String> skipped = new ArrayList<>();

        try (InputStream fis = Files.newInputStream(archive);
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    return ExtractResult.failed("压缩包条目数超过上限 " + MAX_ENTRIES);
                }

                String name = entry.getName();
                if (name == null || name.isBlank()) {
                    continue;
                }

                Path out;
                try {
                    // 关键：Zip Slip 防护
                    out = PathGuard.resolveSafely(staging, name);
                } catch (SecurityException e) {
                    log.warn("跳过越界条目：{}", name);
                    skipped.add(name);
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }

                Files.createDirectories(out.getParent());

                long written = 0;
                byte[] buffer = new byte[64 * 1024];
                try (var os = Files.newOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buffer)) != -1) {
                        written += n;
                        if (written > MAX_ENTRY_BYTES) {
                            return ExtractResult.failed("条目过大：" + name);
                        }
                        os.write(buffer, 0, n);
                    }
                }
                total += written;
            }
        } catch (IOException e) {
            return ExtractResult.failed("解压失败：" + e.getMessage());
        }

        // 校验非空：空包覆盖上去会把目标目录清空
        try (var stream = Files.list(staging)) {
            if (stream.findAny().isEmpty()) {
                return ExtractResult.failed("解压结果为空，拒绝覆盖");
            }
        } catch (IOException e) {
            return ExtractResult.failed("检查解压结果失败：" + e.getMessage());
        }

        if (!skipped.isEmpty()) {
            log.warn("解压时跳过了 {} 个越界条目", skipped.size());
        }
        log.info("解压完成：{} 个条目，{} 字节 -> {}", count, total, staging);
        return new ExtractResult(true, staging, count, total, null);
    }

    /**
     * 把 staging 目录内容替换到目标目录。
     *
     * <p><b>不删除目标目录中多出的文件</b>：用户可能在那里放了配置。
     * 只覆盖同名文件。
     *
     * @param staging 已解压的暂存目录
     * @param target  目标目录
     */
    public static ReplaceResult swapInto(Path staging, Path target, int keepBackups) {
        Path backupDir = null;
        try {
            if (Files.exists(target)) {
                // 备份整个旧目录
                String stamp = java.time.format.DateTimeFormatter
                        .ofPattern("yyyyMMdd-HHmmss-SSS")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(java.time.Instant.now());
                backupDir = target.resolveSibling(target.getFileName() + ".bak." + stamp);
                copyRecursively(target, backupDir);
                log.info("已备份旧目录：{} -> {}", target, backupDir.getFileName());
            }

            Files.createDirectories(target);

            // 逐文件覆盖（不删除目标目录中多出的文件）
            int copied = 0;
            try (var stream = Files.walk(staging)) {
                for (Path src : stream.filter(Files::isRegularFile).toList()) {
                    Path rel = staging.relativize(src);
                    Path dst = target.resolve(rel);
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }

            log.info("覆盖完成：{} 个文件 -> {}", copied, target);
            return new ReplaceResult.Success(target, backupDir, 0);

        } catch (IOException e) {
            // 回滚：把备份目录内容恢复回去
            boolean rolledBack = false;
            if (backupDir != null && Files.exists(backupDir)) {
                try {
                    copyRecursively(backupDir, target);
                    rolledBack = true;
                    log.warn("覆盖失败，已从备份恢复：{}", target);
                } catch (IOException rollbackError) {
                    log.error("回滚失败，备份仍在：{}", backupDir, rollbackError);
                }
            }
            return new ReplaceResult.Failed("覆盖目录失败：" + e.getMessage(), e, rolledBack);
        }
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        try (var stream = Files.walk(from)) {
            for (Path src : stream.toList()) {
                Path rel = from.relativize(src);
                Path dst = to.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 递归删除目录。 */
    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
