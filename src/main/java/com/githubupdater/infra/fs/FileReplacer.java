package com.githubupdater.infra.fs;

import com.githubupdater.infra.win.RestartManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文件替换：备份 → 原子替换 → 失败回滚。
 *
 * <p><b>核心安全原则</b>：
 * <ol>
 *   <li>替换前必须把旧文件改名为备份，绝不直接覆盖</li>
 *   <li>写入失败必须回滚，不能留下"新旧都不完整"的状态</li>
 *   <li>永不静默删除旧版本——备份要按 {@code keepBackups} 保留</li>
 * </ol>
 *
 * <p><b>关于 {@code ATOMIC_MOVE}</b>：它只在<b>同卷内</b>保证原子。
 * 跨卷会抛 {@link AtomicMoveNotSupportedException}，此时必须先复制到目标盘的
 * 同级临时文件再改名。所以 {@code .ready} 文件必须与目标文件同卷。
 *
 * <p><b>为什么不用 {@code MoveFileEx + MOVEFILE_DELAY_UNTIL_REBOOT}</b>：
 * 它要求调用者属于管理员组，普通用户直接失败；而且实现是写注册表
 * {@code PendingFileRenameOperations}，会与其他安装器的待重命名操作互相干扰。
 * 本方案用"先重命名运行中的 exe，再写新文件"——Windows 允许重命名正在运行的
 * exe（映像以 {@code FILE_SHARE_DELETE} 打开），无需重启也无需提权。
 */
public final class FileReplacer {

    private static final Logger log = LoggerFactory.getLogger(FileReplacer.class);

    /** 等待文件释放的超时（强制结束进程后）。 */
    private static final long RELEASE_TIMEOUT_MS = 5000;

    private final int keepBackups;

    public FileReplacer(int keepBackups) {
        this.keepBackups = Math.max(0, keepBackups);
    }

    /**
     * 用 {@code readyFile} 替换 {@code target}。
     *
     * @param readyFile 已校验通过的暂存文件
     * @param target    目标文件
     * @param force     目标被占用时是否强制结束占用进程
     * @return 替换结果
     */
    public ReplaceResult replace(Path readyFile, Path target, boolean force) {
        if (!Files.exists(readyFile)) {
            return new ReplaceResult.Failed("暂存文件不存在：" + readyFile, null, false);
        }

        // 1. 检测占用
        List<RestartManager.LockingProcess> locks = List.of();
        if (Files.exists(target)) {
            locks = RestartManager.findLockingProcesses(target.toString());
        }

        if (!locks.isEmpty()) {
            if (!force) {
                List<ReplaceResult.ProcessInfo> infos = new ArrayList<>();
                for (RestartManager.LockingProcess lp : locks) {
                    infos.add(new ReplaceResult.ProcessInfo(lp.pid(), lp.name()));
                }
                log.info("目标文件被占用，未执行替换：{} -> {} 个进程", target, infos.size());
                return new ReplaceResult.Locked(infos);
            }
            // 强制：结束占用进程
            for (RestartManager.LockingProcess lp : locks) {
                log.warn("强制结束占用进程 {} ({})", lp.name(), lp.pid());
                ProcessHandle.of(lp.pid()).ifPresent(ProcessHandle::destroyForcibly);
            }
            if (!waitForRelease(target, RELEASE_TIMEOUT_MS)) {
                return new ReplaceResult.Failed(
                        "结束进程后文件仍被占用，请稍后重试", null, false);
            }
        }

        // 2. 同卷检查
        try {
            if (!isSameVolume(readyFile, target)) {
                // 跨卷：先复制到目标盘的同级临时文件，保证后续改名是原子的
                Path staged = target.resolveSibling(target.getFileName() + ".staging");
                Files.copy(readyFile, staged, StandardCopyOption.REPLACE_EXISTING);
                readyFile = staged;
                log.debug("跨卷替换：已复制到同级暂存 {}", staged);
            }
        } catch (IOException e) {
            return new ReplaceResult.Failed("准备暂存文件失败：" + e.getMessage(), e, false);
        }

        // 3. 备份旧文件
        Path backup = null;
        long backupBytes = 0;
        if (Files.exists(target)) {
            backup = backupPathFor(target);
            try {
                backupBytes = Files.size(target);
                moveAtomic(target, backup);
                log.info("已备份旧文件：{} -> {}", target.getFileName(), backup.getFileName());
            } catch (IOException e) {
                return new ReplaceResult.Failed("备份旧文件失败：" + e.getMessage(), e, false);
            }
        }

        // 4. 写入新文件
        try {
            moveAtomic(readyFile, target);
            log.info("替换成功：{}（{} 字节）", target, Files.size(target));
            pruneBackups(target);
            return new ReplaceResult.Success(target, backup, backupBytes);
        } catch (IOException e) {
            // 回滚：把备份改回原名
            boolean rolledBack = false;
            if (backup != null && Files.exists(backup)) {
                try {
                    moveAtomic(backup, target);
                    rolledBack = true;
                    log.warn("替换失败，已回滚：{}", target);
                } catch (IOException rollbackError) {
                    log.error("回滚也失败！目标文件可能丢失：{}，备份仍在 {}",
                            target, backup, rollbackError);
                }
            }
            return new ReplaceResult.Failed("写入新文件失败：" + e.getMessage(), e, rolledBack);
        }
    }

    /** 生成备份路径：{@code name.bak.<时间戳>}。 */
    static Path backupPathFor(Path target) {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now());
        return target.resolveSibling(target.getFileName() + ".bak." + stamp);
    }

    /**
     * 原子改名。跨卷时退化为普通 move（调用方应已处理跨卷情形）。
     */
    private static void moveAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            log.warn("ATOMIC_MOVE 不支持（跨卷？），退化为普通移动：{} -> {}", from, to);
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 两个路径是否在同一卷（盘符）。 */
    static boolean isSameVolume(Path a, Path b) {
        try {
            Path ra = a.toAbsolutePath().getRoot();
            Path rb = b.toAbsolutePath().getRoot();
            return ra != null && ra.equals(rb);
        } catch (Exception e) {
            return false;
        }
    }

    /** 等待文件被释放。 */
    private static boolean waitForRelease(Path target, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (RestartManager.findLockingProcesses(target.toString()).isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 清理超出 {@code keepBackups} 的旧备份。 */
    void pruneBackups(Path target) {
        if (keepBackups <= 0) {
            return;
        }
        Path dir = target.getParent();
        String prefix = target.getFileName() + ".bak.";
        try (var stream = Files.list(dir)) {
            List<Path> backups = stream
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (int i = keepBackups; i < backups.size(); i++) {
                Files.deleteIfExists(backups.get(i));
                log.debug("清理旧备份：{}", backups.get(i).getFileName());
            }
        } catch (IOException e) {
            log.debug("清理备份失败：{}", e.getMessage());
        }
    }

    /**
     * 回滚：把指定备份恢复到目标路径。
     *
     * @param backup 备份文件
     * @param target 目标路径
     */
    public ReplaceResult rollback(Path backup, Path target) {
        if (!Files.exists(backup)) {
            return new ReplaceResult.Failed("备份文件不存在：" + backup, null, false);
        }
        try {
            // 当前文件先另存，避免回滚本身导致数据丢失
            if (Files.exists(target)) {
                Path current = backupPathFor(target);
                moveAtomic(target, current);
                log.info("回滚前已保存当前文件：{}", current.getFileName());
            }
            moveAtomic(backup, target);
            log.info("已回滚：{} <- {}", target, backup.getFileName());
            return new ReplaceResult.Success(target, null, 0);
        } catch (IOException e) {
            return new ReplaceResult.Failed("回滚失败：" + e.getMessage(), e, false);
        }
    }
}
