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
        assertTrue(text.contains("core metric"), text);
        // 描述里的中文同样按 bigram 切（写入侧与查询侧必须一致）
        assertTrue(text.contains("订单"), text);
        assertTrue(text.contains("单明"), text);
        assertTrue(text.contains("明细"), text);
    }

    /**
     * 中文检索能否命中的**充要条件**：查询串切出来的每个 token 都在索引文本里。
     *
     * <p>这条断言是"中文检索真的能用"的证明，而不是"看起来切了词"：
     * 只要写入侧与查询侧共用 {@link IdentifierTokenizer#tokenize}，它就恒成立；
     * 任何一侧走了旁路（例如描述原样拼进去），这里立刻失败。
     */
    @Test
    void chineseQueryTokensAreAllPresentInIndexedText() {
        String indexed = IdentifierTokenizer.buildSearchText(
                "客户订单明细", "urn:dg:Dataset:prod.pg.public.customer_order_detail",
                "含客户与订单的明细宽表", List.of("客户", "订单"));
        for (String query : List.of("客户订单", "订单明细", "明细宽表", "客户")) {
            for (String token : IdentifierTokenizer.tokenizeQuery(query).split(" ")) {
                assertTrue(indexed.contains(token), query + " → token「" + token + "」未出现在索引文本中：" + indexed);
            }
        }
    }

    /** 标点必须当分隔符：否则会切出「，明细」这类永远匹配不上的 token。 */
    @Test
    void punctuationActsAsSeparator() {
        assertEquals("订单 明细", IdentifierTokenizer.tokenize("订单，明细"));
        assertEquals("orders amount", IdentifierTokenizer.tokenize("orders,amount"));
    }

    @Test
    void searchTextToleratesMissingParts() {
        String text = IdentifierTokenizer.buildSearchText("orders", null, null, null);
        assertEquals("orders", text);
    }
}
