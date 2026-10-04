package com.datagovernance.core.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标识符切分测试。
 *
 * <p>为什么这个纯函数值得单独测：不切分的话，"按表名片段找表"——数据目录里最高频的
 * 检索动作——会直接失效，而失败表现只是"搜不到"，非常容易被当成"确实没有这张表"。
 */
class IdentifierTokenizerTest {

    @Test
    void underscoresBecomeSpaces() {
        assertEquals("ods orders", IdentifierTokenizer.tokenize("ods_orders"));
    }

    @Test
    void camelCaseIsSplit() {
        assertEquals("order Detail", IdentifierTokenizer.tokenize("orderDetail"));
        assertEquals("HTTPServer port", IdentifierTokenizer.tokenize("HTTPServer_port"));
    }

    @Test
    void dotsColonsAndSlashesAreSeparators() {
        assertEquals("db public orders", IdentifierTokenizer.tokenize("db.public.orders"));
        assertEquals("urn dg Dataset a b", IdentifierTokenizer.tokenize("urn:dg:Dataset:a.b"));
    }

    @Test
    void emptyAndNullAreSafe() {
        assertEquals("", IdentifierTokenizer.tokenize(""));
        assertEquals("", IdentifierTokenizer.tokenize(null));
    }

    @Test
    void extraWhitespaceIsCollapsed() {
        assertEquals("a b", IdentifierTokenizer.tokenize("a   _   b"));
    }

    /** 检索文本必须同时包含展示名、URN 路径段、描述与标签。 */
    @Test
    void searchTextCombinesNameUrnDescriptionAndTags() {
        String text = IdentifierTokenizer.buildSearchText(
                "dwd_order_detail",
                "urn:dg:Dataset:prod.hive.dw.dwd_order_detail",
                "订单明细表",
                List.of("core-metric"));
        assertTrue(text.contains("dwd order detail"), text);
        assertTrue(text.contains("prod hive dw"), text);
        assertTrue(text.contains("订单明细表"), text);
        assertTrue(text.contains("core metric"), text);
    }

    @Test
    void searchTextToleratesMissingParts() {
        String text = IdentifierTokenizer.buildSearchText("orders", null, null, null);
        assertEquals("orders", text);
    }
}
