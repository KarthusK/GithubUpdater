package com.githubupdater.infra.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content-Range 解析。
 *
 * <p>这两个解析函数是续传正确性的关键：起点解析错了会导致偏移错位，
 * 总长度解析错了会导致"下载字节数与预期不符"的误判。
 */
class ContentRangeTest {

    @ParameterizedTest
    @CsvSource({
            "'bytes 0-0/12345', 12345",
            "'bytes 100-199/200', 200",
            "'bytes 0-1023/1035264', 1035264",
            "'bytes 0-0/*', -1",
    })
    @DisplayName("解析总长度")
    void parseTotal(String header, long expected) {
        assertEquals(expected, HttpFetcher.parseTotalFromContentRange(header));
    }

    @Test
    @DisplayName("非法或缺失的 Content-Range 应返回 -1")
    void parseTotalInvalid() {
        assertEquals(-1, HttpFetcher.parseTotalFromContentRange(null));
        assertEquals(-1, HttpFetcher.parseTotalFromContentRange(""));
        assertEquals(-1, HttpFetcher.parseTotalFromContentRange("bytes 0-0"));
        assertEquals(-1, HttpFetcher.parseTotalFromContentRange("bytes 0-0/"));
        assertEquals(-1, HttpFetcher.parseTotalFromContentRange("bytes 0-0/abc"));
    }

    @ParameterizedTest
    @CsvSource({
            "'bytes 0-0/12345', 0",
            "'bytes 524288-1048575/1035264', 524288",
            "'bytes 100-199/200', 100",
    })
    @DisplayName("解析起始偏移")
    void parseStart(String header, long expected) {
        assertEquals(expected, HttpFetcher.parseStartFromContentRange(header));
    }

    @Test
    @DisplayName("非法起始偏移应返回 -1（调用方会判定为不符而重下）")
    void parseStartInvalid() {
        assertEquals(-1, HttpFetcher.parseStartFromContentRange(null));
        assertEquals(-1, HttpFetcher.parseStartFromContentRange(""));
        assertEquals(-1, HttpFetcher.parseStartFromContentRange("bytes"));
        assertEquals(-1, HttpFetcher.parseStartFromContentRange("bytes /100"));
        assertEquals(-1, HttpFetcher.parseStartFromContentRange("bytes abc-199/200"));
    }

    @Test
    @DisplayName("起点与本地偏移不符时，解析结果应能暴露差异")
    void startOffsetMismatchDetectable() {
        // 本地已有 100 字节，服务器却从 0 开始给数据 —— 必须能被发现
        long localOffset = 100;
        long serverStart = HttpFetcher.parseStartFromContentRange("bytes 0-199/200");
        assertEquals(0, serverStart);
        assertTrue(serverStart != localOffset, "起点不符必须可被检测到");
    }
}
