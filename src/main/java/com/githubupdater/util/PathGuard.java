package com.githubupdater.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 路径越界防护。
 *
 * <p>为什么必须有：如果攻击者能控制 {@code targetDir}（例如通过本地 HTTP 服务），
 * 就能让工具往任意位置写文件。见 docs/03-API接口.md 的安全清单。
 *
 * <p>规则：
 * <ul>
 *   <li>拒绝包含 {@code ..} 的相对路径</li>
 *   <li>拒绝系统目录（Windows 目录、Program Files、System32 等）</li>
 *   <li>规范化后必须仍在允许的根目录之内</li>
 * </ul>
 */
public final class PathGuard {

    /** 绝对禁止作为目标目录的系统位置（按规范化后的小写绝对路径前缀比对）。 */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "c:\\windows",
            "c:\\program files",
            "c:\\program files (x86)",
            "c:\\programdata",
            "c:\\$recycle.bin",
            "c:\\system volume information"
    );

    private PathGuard() {
    }

    /**
     * 校验目标目录是否可安全写入。
     *
     * @param targetDir 用户配置的目标目录
     * @return 校验结果；通过时 {@link Result#ok()} 为 true
     */
    public static Result checkTargetDir(String targetDir) {
        if (targetDir == null || targetDir.isBlank()) {
            return Result.reject("目标目录不能为空");
        }

        Path path;
        try {
            path = Path.of(targetDir);
        } catch (Exception e) {
            return Result.reject("目标目录格式非法：" + e.getMessage());
        }

        if (!path.isAbsolute()) {
            return Result.reject("目标目录必须是绝对路径：" + targetDir);
        }

        // 必须在 normalize 之前检查：normalize 会把 ".." 消解掉，
        // 之后再看路径段就发现不了穿越了。这里只看原始输入里是否出现 ".."。
        if (containsTraversal(path)) {
            return Result.reject("目标目录包含路径穿越：" + targetDir);
        }

        Path normalized = path.normalize();
        if (normalized.getNameCount() == 0 || normalized.getParent() == null) {
            return Result.reject("不允许使用磁盘根目录：" + targetDir);
        }

        String lower = normalized.toString().toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_PREFIXES) {
            if (lower.equals(forbidden) || lower.startsWith(forbidden + "\\")) {
                return Result.reject("不允许写入系统目录：" + targetDir);
            }
        }

        // 额外拦一层：任何一个盘符根下的 Windows 目录（如 D:\Windows）也不许写。
        // 只判断「根目录 + 第一段」这种情况，避免误伤 D:\projects\windows\ 这类正常路径。
        if (normalized.getNameCount() >= 1) {
            Path first = normalized.getName(0);
            if ("windows".equals(first.toString().toLowerCase(Locale.ROOT))) {
                return Result.reject("不允许写入系统目录：" + targetDir);
            }
        }

        return Result.ok(normalized);
    }

    /**
     * 解压时的路径穿越防护（Zip Slip）。
     *
     * <p>压缩包内的条目名可能是 {@code ../../evil.exe}，直接 resolve 会写到预期目录之外。
     * 本方法校验解析结果仍在 {@code baseDir} 之内。
     *
     * @param baseDir  解压根目录
     * @param entryName 压缩包内的条目名
     * @return 安全的绝对路径
     * @throws SecurityException 条目越界时抛出
     */
    public static Path resolveSafely(Path baseDir, String entryName) {
        if (entryName == null || entryName.isBlank()) {
            throw new SecurityException("压缩包条目名为空");
        }

        Path base = baseDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(entryName).normalize();

        if (!resolved.startsWith(base)) {
            throw new SecurityException("压缩包条目越界：" + entryName);
        }
        if (resolved.equals(base)) {
            throw new SecurityException("压缩包条目指向根目录自身：" + entryName);
        }
        return resolved;
    }

    private static boolean containsTraversal(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /** 校验结果。 */
    public record Result(boolean ok, String message, Path normalizedPath) {

        public static Result ok(Path path) {
            return new Result(true, null, path);
        }

        public static Result reject(String message) {
            return new Result(false, message, null);
        }
    }
}
