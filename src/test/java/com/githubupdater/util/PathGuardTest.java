package com.githubupdater.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 路径越界防护的单元测试。这部分是纯逻辑，不需要 IO。 */
class PathGuardTest {

    @Test
    @DisplayName("正常的绝对路径应通过")
    void acceptsNormalAbsolutePath() {
        var result = PathGuard.checkTargetDir("D:\\tools\\jq");
        assertTrue(result.ok(), result.message());
        assertEquals(Path.of("D:\\tools\\jq"), result.normalizedPath());
    }

    @Test
    @DisplayName("空值与空白应被拒绝")
    void rejectsBlank() {
        assertFalse(PathGuard.checkTargetDir(null).ok());
        assertFalse(PathGuard.checkTargetDir("").ok());
        assertFalse(PathGuard.checkTargetDir("   ").ok());
    }

    @Test
    @DisplayName("相对路径应被拒绝")
    void rejectsRelativePath() {
        var result = PathGuard.checkTargetDir("tools\\jq");
        assertFalse(result.ok());
        assertTrue(result.message().contains("绝对路径"));
    }

    @Test
    @DisplayName("磁盘根目录应被拒绝")
    void rejectsDriveRoot() {
        assertFalse(PathGuard.checkTargetDir("D:\\").ok());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "C:\\Windows",
            "C:\\Windows\\System32",
            "C:\\Program Files\\Foo",
            "C:\\Program Files (x86)\\Bar",
            "C:\\ProgramData\\Baz"
    })
    @DisplayName("系统目录应被拒绝")
    void rejectsSystemDirectories(String path) {
        var result = PathGuard.checkTargetDir(path);
        assertFalse(result.ok(), "应拒绝: " + path);
        assertTrue(result.message().contains("系统目录"));
    }

    @Test
    @DisplayName("系统目录判断应忽略大小写")
    void rejectsSystemDirectoryCaseInsensitive() {
        assertFalse(PathGuard.checkTargetDir("c:\\windows\\system32").ok());
        assertFalse(PathGuard.checkTargetDir("C:\\WINDOWS").ok());
    }

    @Test
    @DisplayName("路径穿越应被拒绝")
    void rejectsTraversal() {
        var result = PathGuard.checkTargetDir("D:\\tools\\..\\..\\Windows");
        assertFalse(result.ok());
    }

    @Test
    @DisplayName("邻近系统目录名的正常路径不应误伤")
    void doesNotRejectLookAlikePaths() {
        // c:\windows-tools 不是 c:\windows 的子目录，不应被拒绝
        assertTrue(PathGuard.checkTargetDir("D:\\windows-tools\\app").ok());
        assertTrue(PathGuard.checkTargetDir("D:\\tools\\Program Files\\x").ok());
    }

    // ──────────────── Zip Slip ────────────────

    @Test
    @DisplayName("解压条目在根目录内应通过")
    void resolveSafelyAcceptsInsideEntry() {
        Path base = Path.of("D:\\staging");
        assertEquals(Path.of("D:\\staging\\bin\\app.exe"),
                PathGuard.resolveSafely(base, "bin/app.exe"));
        assertEquals(Path.of("D:\\staging\\readme.txt"),
                PathGuard.resolveSafely(base, "readme.txt"));
    }

    @Test
    @DisplayName("Zip Slip 穿越应抛出 SecurityException")
    void resolveSafelyRejectsZipSlip() {
        Path base = Path.of("D:\\staging");
        assertThrows(SecurityException.class,
                () -> PathGuard.resolveSafely(base, "../../evil.exe"));
        assertThrows(SecurityException.class,
                () -> PathGuard.resolveSafely(base, "bin/../../../evil.exe"));
    }

    @Test
    @DisplayName("指向根目录自身与空条目名应被拒绝")
    void resolveSafelyRejectsEdgeCases() {
        Path base = Path.of("D:\\staging");
        assertThrows(SecurityException.class, () -> PathGuard.resolveSafely(base, ""));
        assertThrows(SecurityException.class, () -> PathGuard.resolveSafely(base, " "));
        assertThrows(SecurityException.class, () -> PathGuard.resolveSafely(base, "."));
    }
}
