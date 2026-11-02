package com.githubupdater.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 资产 glob 匹配。这是规则的核心，必须覆盖边界情形。 */
class AssetMatcherTest {

    private static final List<String> ASSETS = List.of(
            "jq-windows-amd64.exe",
            "jq-windows-arm64.exe",
            "jq-linux-amd64",
            "jq-macos-arm64",
            "Source code (zip)",
            "Source code (tar.gz)"
    );

    @Test
    @DisplayName("精确名称应匹配到单个")
    void exactMatch() {
        var r = AssetMatcher.match(ASSETS, "jq-windows-amd64.exe", null);
        assertTrue(r.isSingle());
        assertEquals("jq-windows-amd64.exe", r.singleAsset());
    }

    @Test
    @DisplayName("通配符匹配到多个应返回 AMBIGUOUS，不自动挑")
    void wildcardMultipleIsAmbiguous() {
        var r = AssetMatcher.match(ASSETS, "jq-windows-*", null);
        assertEquals(AssetMatcher.Result.Kind.AMBIGUOUS, r.kind());
        assertEquals(2, r.matched().size());
        assertThrows(IllegalStateException.class, r::singleAsset);
    }

    @Test
    @DisplayName("排除规则应生效，排除优先于包含")
    void excludeWins() {
        var r = AssetMatcher.match(ASSETS, "jq-*", "*arm64*");
        assertEquals(AssetMatcher.Result.Kind.AMBIGUOUS, r.kind());
        assertFalse(r.matched().stream().anyMatch(s -> s.contains("arm64")),
                "arm64 应被排除，实际: " + r.matched());
        assertEquals(2, r.matched().size());
    }

    @Test
    @DisplayName("排除后只剩一个应返回 SINGLE")
    void excludeNarrowsToSingle() {
        var r = AssetMatcher.match(ASSETS, "jq-windows-*", "*arm64*");
        assertTrue(r.isSingle());
        assertEquals("jq-windows-amd64.exe", r.singleAsset());
    }

    @Test
    @DisplayName("未匹配到应返回 NONE")
    void noMatch() {
        var r = AssetMatcher.match(ASSETS, "*.msi", null);
        assertEquals(AssetMatcher.Result.Kind.NONE, r.kind());
        assertEquals(0, r.matched().size());
    }

    @Test
    @DisplayName("匹配应不区分大小写")
    void caseInsensitive() {
        var r = AssetMatcher.match(ASSETS, "JQ-WINDOWS-AMD64.EXE", null);
        assertTrue(r.isSingle());
    }

    @Test
    @DisplayName("问号应匹配单个字符")
    void questionMark() {
        var r = AssetMatcher.match(List.of("a1.exe", "a12.exe"), "a?.exe", null);
        assertTrue(r.isSingle());
        assertEquals("a1.exe", r.singleAsset());
    }

    @Test
    @DisplayName("正则元字符应按字面量处理")
    void regexMetaIsLiteral() {
        // 若 "." 未转义，"a.zip" 会匹配 "axzip"
        var r = AssetMatcher.match(List.of("axzip", "a.zip"), "a.zip", null);
        assertTrue(r.isSingle());
        assertEquals("a.zip", r.singleAsset());
    }

    @Test
    @DisplayName("空资产列表应返回 NONE 而非抛异常")
    void emptyAssets() {
        assertEquals(AssetMatcher.Result.Kind.NONE,
                AssetMatcher.match(List.of(), "*", null).kind());
        assertEquals(AssetMatcher.Result.Kind.NONE,
                AssetMatcher.match(null, "*", null).kind());
    }

    @Test
    @DisplayName("空包含规则应被拒绝")
    void blankIncludeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AssetMatcher.match(ASSETS, "", null));
        assertThrows(IllegalArgumentException.class,
                () -> AssetMatcher.match(ASSETS, null, null));
    }

    @Test
    @DisplayName("匹配源码自动生成的 zip 应可行（用于 SOURCE 归档场景）")
    void matchSourceZip() {
        var r = AssetMatcher.match(ASSETS, "*Source code (zip)*", null);
        assertTrue(r.isSingle());
        assertEquals("Source code (zip)", r.singleAsset());
    }
}
