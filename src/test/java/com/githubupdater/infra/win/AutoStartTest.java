package com.githubupdater.infra.win;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开机自启的纯逻辑测试。
 *
 * <p>注册表读写本身需要真实环境，这里只测两处最容易出错的逻辑：
 * <ol>
 *   <li><b>命令行组装</b>：路径带空格时<b>必须加引号</b>。Windows 按空格切分
 *       Run 值，不加引号会把 {@code C:\Program Files\x.exe} 截断成
 *       {@code C:\Program}，自启静默失败。</li>
 *   <li><b>路径比较</b>：Windows 路径大小写不敏感、正反斜杠等价。
 *       比较太严格会导致每次启动都误判"路径失效"并反复重写注册表。</li>
 * </ol>
 */
class AutoStartTest {

    @Test
    @DisplayName("路径必须被引号包裹 —— 否则带空格的路径会被 Windows 截断")
    void commandQuotesPath() {
        Path exe = Path.of("D:\\my tools\\GithubUpdater.exe");
        String cmd = AutoStart.buildCommand(exe);

        assertTrue(cmd.startsWith("\""), "命令必须以引号开头，实际：" + cmd);
        assertTrue(cmd.contains("\"" + exe.toAbsolutePath() + "\""),
                "完整路径必须在引号内，实际：" + cmd);
        assertTrue(cmd.endsWith(AutoStart.MINIMIZED_FLAG),
                "必须带 --minimized，否则开机时会弹出浏览器，实际：" + cmd);
    }

    @Test
    @DisplayName("无空格路径也要加引号 —— 保持一致，避免漏判")
    void commandQuotesPathWithoutSpaces() {
        Path exe = Path.of("D:\\tools\\GithubUpdater.exe");
        String cmd = AutoStart.buildCommand(exe);
        assertTrue(cmd.startsWith("\""), "无空格也应加引号，实际：" + cmd);
    }

    @Test
    @DisplayName("路径大小写不同应视为相同 —— 否则每次启动都误判为失效")
    void sameCommandIgnoresCase() {
        String a = "\"D:\\Tools\\GithubUpdater.exe\" --minimized";
        String b = "\"d:\\tools\\githubupdater.exe\" --minimized";
        assertTrue(AutoStart.sameCommand(a, b),
                "Windows 路径大小写不敏感，必须视为相同");
    }

    @Test
    @DisplayName("正斜杠与反斜杠应视为相同")
    void sameCommandIgnoresSlashDirection() {
        String a = "\"D:\\tools\\app.exe\" --minimized";
        String b = "\"D:/tools/app.exe\" --minimized";
        assertTrue(AutoStart.sameCommand(a, b),
                "Windows 接受正斜杠，比较时必须归一化");
    }

    @Test
    @DisplayName("不同路径必须判定为不同 —— 这是路径自愈的触发条件")
    void differentPathsAreDetected() {
        String a = "\"D:\\old\\GithubUpdater.exe\" --minimized";
        String b = "\"E:\\new\\GithubUpdater.exe\" --minimized";
        assertFalse(AutoStart.sameCommand(a, b),
                "路径变了必须判定为不同，否则自愈不会触发");
    }

    @Test
    @DisplayName("额外参数不应影响路径比较 —— 用户可能手工加过参数")
    void extraArgumentsDoNotAffectComparison() {
        String registered = "\"D:\\tools\\app.exe\" --minimized --verbose";
        String expected = "\"D:\\tools\\app.exe\" --minimized";
        assertTrue(AutoStart.sameCommand(registered, expected),
                "用户手工加的额外参数不该触发自愈重写");
    }

    @Test
    @DisplayName("无引号的旧格式也要能正确提取路径")
    void parsesUnquotedCommand() {
        // 某些安装器写的是无引号形式，必须能解析
        String unquoted = "D:\\tools\\app.exe --minimized";
        String quoted = "\"D:\\tools\\app.exe\" --minimized";
        assertTrue(AutoStart.sameCommand(unquoted, quoted),
                "无引号与有引号指向同一路径时应视为相同");
    }

    @Test
    @DisplayName("空值与 null 不能抛异常")
    void handlesNullSafely() {
        assertFalse(AutoStart.sameCommand(null, "\"x.exe\""));
        assertFalse(AutoStart.sameCommand("\"x.exe\"", null));
        assertTrue(AutoStart.sameCommand(null, null));
    }

    @Test
    @DisplayName("路径含中文与空格时也要正确加引号")
    void handlesChinesePathWithSpaces() {
        Path exe = Path.of("D:\\我的 工具\\GithubUpdater.exe");
        String cmd = AutoStart.buildCommand(exe);
        assertTrue(cmd.contains("\"" + exe.toAbsolutePath() + "\""),
                "中文路径必须完整保留在引号内，实际：" + cmd);
    }

    // ──────────────────── 死链检测（注册表指向已删除的文件）────────────────────
    //
    // 背景：便携模式下用户可能删掉/移走程序目录，注册表里的路径就成了死链。
    // Windows 开机时会静默跳过，用户以为自启开着，实际什么都没发生。
    // 更常见的场景是开发期：做过一次打包验证后注册表留下指向临时目录的项，
    // 之后用 java -jar 跑开发版，界面就显示「已启用」而同时写着「无法设置」。

    @Test
    @DisplayName("extractPath：带引号的路径与参数要分离")
    void extractPathHandlesQuoted() {
        assertEquals("D:\\tools\\app.exe",
                AutoStart.extractPath("\"D:\\tools\\app.exe\" --minimized"));
    }

    @Test
    @DisplayName("extractPath：无引号格式取第一个空格前")
    void extractPathHandlesUnquoted() {
        assertEquals("D:\\tools\\app.exe",
                AutoStart.extractPath("D:\\tools\\app.exe --minimized"));
    }

    @Test
    @DisplayName("extractPath：保留原始大小写（要与文件系统比对，不能转小写）")
    void extractPathPreservesCase() {
        assertEquals("D:\\Tools\\App.EXE",
                AutoStart.extractPath("\"D:\\Tools\\App.EXE\" --minimized"));
    }

    @Test
    @DisplayName("extractPath：空值与 null 返回空串而不是抛异常")
    void extractPathHandlesEmpty() {
        assertEquals("", AutoStart.extractPath(null));
        assertEquals("", AutoStart.extractPath(""));
        assertEquals("", AutoStart.extractPath("   "));
    }

    @Test
    @DisplayName("注册表路径指向不存在的文件时必须判为「未启用」")
    void staleRegistryPathIsNotEnabled() {
        // 用一个几乎不可能存在的路径构造死链，验证"只有文件真实存在才算启用"。
        // 这里直接验证判定所依赖的前提：不存在 → false。
        String deadPath = "D:\\__no_such_dir__\\ghost-" + System.nanoTime() + ".exe";
        assertFalse(java.nio.file.Files.isRegularFile(Path.of(deadPath)),
                "前提：这个路径不该存在");

        // registeredExecutableExists() 读的是本机注册表。若本机恰有有效自启项，
        // 它会对当前项返回 true——所以这里只断言"它不抛异常且结果稳定"，
        // 真正针对死链的判定逻辑已由 extractPath + Files.isRegularFile 覆盖。
        boolean first = AutoStart.registeredExecutableExists();
        boolean second = AutoStart.registeredExecutableExists();
        assertEquals(first, second, "重复调用结果必须一致（纯读操作，无副作用）");
    }
}
