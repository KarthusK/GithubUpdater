package com.githubupdater.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 版本判定与文件名渲染。全部是纯逻辑。 */
class VersionResolverTest {

    @Test
    @DisplayName("首次检查（无历史）应视为有新版本")
    void firstCheckIsUpdate() {
        assertTrue(VersionResolver.hasUpdate(null, 123L));
    }

    @Test
    @DisplayName("release id 相同则无新版本")
    void sameIdIsNoUpdate() {
        assertFalse(VersionResolver.hasUpdate(123L, 123L));
    }

    @Test
    @DisplayName("release id 变化即视为有新版本")
    void differentIdIsUpdate() {
        assertTrue(VersionResolver.hasUpdate(123L, 124L));
        // id 减小也算变化：release 可能被删除后重建，不能假设单调
        assertTrue(VersionResolver.hasUpdate(124L, 123L));
    }

    @Test
    @DisplayName("文件名模板应正确替换三个占位符")
    void rendersTemplate() {
        // {name} 替换为不含扩展名的基础名，因此 jq-windows-amd64.exe -> jq-windows-amd64
        assertEquals("jq-windows-amd64-jq-1.8.2.exe",
                VersionResolver.renderFileName("{name}-{version}.{ext}",
                        "jq-windows-amd64.exe", "jq-1.8.2"));
        // 若只想要简洁名字，模板里就别放 {name}
        assertEquals("jq-1.8.2.exe",
                VersionResolver.renderFileName("{version}.{ext}",
                        "jq-windows-amd64.exe", "jq-1.8.2"));
    }

    @Test
    @DisplayName("扩展名应保留原大小写之外的小写形式，且基础名不含扩展名")
    void rendersBaseNameWithoutExtension() {
        assertEquals("app-1.0.zip",
                VersionResolver.renderFileName("{name}-{version}.{ext}", "app.zip", "1.0"));
    }

    @Test
    @DisplayName("无扩展名的资产不应导致异常")
    void handlesNoExtension() {
        assertEquals("tool-1.0.", 
                VersionResolver.renderFileName("{name}-{version}.{ext}", "tool", "1.0"));
    }

    @Test
    @DisplayName("文件名中的非法字符应被替换")
    void sanitizesIllegalChars() {
        String out = VersionResolver.renderFileName("{name}-{version}.{ext}",
                "a/b:c.exe", "v1/2");
        assertFalse(out.contains("/"));
        assertFalse(out.contains(":"));
    }

    @Test
    @DisplayName("空模板应被拒绝")
    void rejectsBlankTemplate() {
        assertThrows(IllegalArgumentException.class,
                () -> VersionResolver.renderFileName("", "a.exe", "1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> VersionResolver.renderFileName(null, "a.exe", "1.0"));
    }
}
