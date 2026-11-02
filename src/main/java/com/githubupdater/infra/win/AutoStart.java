package com.githubupdater.infra.win;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 开机自启（写 HKCU 的 Run 项）。
 *
 * <p>用 {@code HKCU}（当前用户）而非 {@code HKLM}，<b>不需要管理员权限</b>——
 * 本工具的定位就是普通用户双击即用的便携程序，不应该要求提权。
 *
 * <h2>便携模式的路径自愈</h2>
 *
 * <p>便携模式下用户可能把整个目录移到别处。此时注册表里记录的<b>旧绝对路径失效，
 * 自启会静默失败</b>——用户开机后发现工具没启动，却完全不知道原因。
 * 这是这类工具最典型的"看起来正常其实坏了"的故障。
 *
 * <p>所以每次启动都要比对"注册表里的路径"与"当前实际 exe 路径"，
 * 不一致就自动修正，并记日志。用户无需干预。
 *
 * <h2>为什么值数据要加引号</h2>
 *
 * <p>路径里可能有空格（{@code C:\Program Files\...}）。Windows 解析 Run 值时
 * 按空格切分命令行，不加引号会把路径截断成 {@code C:\Program}。
 * 所以格式必须是 {@code "C:\path with space\app.exe" --minimized}。
 */
public final class AutoStart {

    private static final Logger log = LoggerFactory.getLogger(AutoStart.class);

    /** Run 项下的值名。 */
    private static final String VALUE_NAME = "GithubUpdater";

    /** 注册表路径。 */
    private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    /** 开机自启时附带的参数，表示"静默启动，不要弹浏览器"。 */
    public static final String MINIMIZED_FLAG = "--minimized";

    private AutoStart() {
    }

    /**
     * 当前进程对应的可执行文件路径。
     *
     * <p>三种情况：
     * <ol>
     *   <li>jpackage 产物：{@code jpackage.app-path} 指向 exe</li>
     *   <li>{@code java -jar}：命令行是 javaw.exe + 参数，此时没有稳定的 exe 可自启</li>
     *   <li>拿不到命令行：返回 empty</li>
     * </ol>
     *
     * @return exe 绝对路径；不是打包产物时返回 empty
     */
    public static Optional<Path> currentExecutable() {
        // jpackage 启动器会设置这个属性，指向 GithubUpdater.exe
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            return Optional.of(Path.of(appPath).toAbsolutePath());
        }

        // 退化路径：从命令行取第一个 token，但必须是 .exe 且不是 java.exe
        // （java.exe 自启没有意义——没有 -jar 参数它什么也不做）
        return ProcessHandle.current().info().command()
                .map(Path::of)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    return name.endsWith(".exe")
                            && !name.equals("java.exe")
                            && !name.equals("javaw.exe");
                })
                .map(p -> p.toAbsolutePath());
    }

    /** 是否已启用自启（注册表里有值）。 */
    public static boolean isEnabled() {
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME);
        } catch (RuntimeException e) {
            log.debug("读取自启状态失败：{}", e.getMessage());
            return false;
        }
    }

    /** 读注册表里记录的命令行。 */
    public static Optional<String> registeredCommand() {
        try {
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
                return Optional.empty();
            }
            return Optional.of(Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME));
        } catch (RuntimeException e) {
            log.debug("读取自启命令失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 启用自启。
     *
     * @param exe 可执行文件路径
     * @return 是否成功
     */
    public static boolean enable(Path exe) {
        String command = buildCommand(exe);
        try {
            Advapi32Util.registrySetStringValue(
                    WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, command);
            log.info("已启用开机自启：{}", command);
            return true;
        } catch (RuntimeException e) {
            log.warn("启用开机自启失败：{}", e.getMessage());
            return false;
        }
    }

    /** 关闭自启（删除注册表值）。 */
    public static boolean disable() {
        try {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME);
            }
            log.info("已关闭开机自启");
            return true;
        } catch (RuntimeException e) {
            log.warn("关闭开机自启失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 路径自愈：比对注册表里的路径与当前 exe，不一致就修正。
     *
     * <p>启动时调用。只有"已启用自启"时才需要检查。
     *
     * @return 是否做了修正
     */
    public static boolean selfHeal() {
        if (!isEnabled()) {
            return false;
        }

        Optional<Path> currentExe = currentExecutable();
        if (currentExe.isEmpty()) {
            // 开发环境（java -jar）没有稳定的 exe，无法自愈，也不该乱改注册表
            log.debug("当前不是打包产物，跳过自启路径自愈");
            return false;
        }

        Optional<String> registered = registeredCommand();
        String expected = buildCommand(currentExe.get());

        if (registered.isEmpty()) {
            // 值存在但读不出来，按需重建
            log.warn("自启项存在但读取失败，重建");
            return enable(currentExe.get());
        }

        if (!sameCommand(registered.get(), expected)) {
            log.info("开机自启路径已失效，自动修正：{} -> {}", registered.get(), expected);
            return enable(currentExe.get());
        }

        log.debug("开机自启路径正常：{}", registered.get());
        return false;
    }

    /**
     * 组装 Run 值。
     *
     * <p>路径必须加引号：Windows 按空格切分命令行，不加引号会把
     * {@code C:\Program Files\x.exe} 截断成 {@code C:\Program}。
     */
    static String buildCommand(Path exe) {
        return "\"" + exe.toAbsolutePath() + "\" " + MINIMIZED_FLAG;
    }

    /**
     * 比较两条命令是否等价。
     *
     * <p>只比路径部分，忽略大小写与参数顺序——注册表里可能被用户手工改过，
     * 也可能带了额外参数，这些都不该触发"自愈"。
     */
    static boolean sameCommand(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    /** 提取命令行里的 exe 路径并规范化。 */
    private static String normalize(String command) {
        if (command == null) {
            return "";
        }
        String s = command.trim();

        String path;
        if (s.startsWith("\"")) {
            int end = s.indexOf('"', 1);
            path = end > 0 ? s.substring(1, end) : s.substring(1);
        } else {
            // 没引号：取第一个空格之前的部分
            int space = s.indexOf(' ');
            path = space > 0 ? s.substring(0, space) : s;
        }

        // Windows 路径大小写不敏感，且反斜杠/正斜杠等价
        return path.replace('/', '\\').toLowerCase(java.util.Locale.ROOT);
    }
}
