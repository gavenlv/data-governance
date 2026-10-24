package com.datagovernance.core.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中文词典分词的测试。
 *
 * <p>重点验证三件事：词典真的加载了（不是空表）、歧义串能被双向匹配切好、
 * 未登录串仍然有 bigram 兜底（召回不倒退）。
 */
class ChineseSegmenterTest {

    @Test
    void lexiconIsLoaded() {
        assertTrue(ChineseSegmenter.lexiconSize() > 100,
                "词典必须真的从资源加载（空词表会静默退化成纯 bigram）");
        assertTrue(ChineseSegmenter.isKnownWord("客户"));
        assertTrue(ChineseSegmenter.isKnownWord("订单"));
        assertTrue(ChineseSegmenter.isKnownWord("血缘"));
    }

    @Test
    void segmentsDomainPhraseIntoWords() {
        List<String> tokens = ChineseSegmenter.segment("客户订单明细");

        assertTrue(tokens.contains("客户"), "应切出「客户」：" + tokens);
        assertTrue(tokens.contains("订单"), "应切出「订单」：" + tokens);
        assertTrue(tokens.contains("明细"), "应切出「明细」：" + tokens);
    }

    @Test
    void bidirectionalMatchingAvoidsSingleCharacterFragments() {
        // 单向前缀匹配容易切出「研究生 / 命 / 科学」，双向取优应避免单字碎片
        List<String> tokens = ChineseSegmenter.segment("客户订单");

        assertEquals(2, tokens.size(), "两个词就能覆盖，不应产生单字碎片：" + tokens);
        assertEquals(List.of("客户", "订单"), tokens);
    }

    @Test
    void indexTokensIncludeDictionaryWordsAndBigrams() {
        List<String> tokens = ChineseSegmenter.tokensForIndex("客户订单明细");

        assertTrue(tokens.contains("客户"));
        assertTrue(tokens.contains("订单"));
        // bigram 兜底：查询侧切成「单明」这种未登录串时仍能命中
        assertTrue(tokens.contains("单明"), "必须保留全量 bigram 兜底：" + tokens);
        assertTrue(tokens.contains("户订"), "bigram 兜底用于未登录词的召回：" + tokens);
    }

    @Test
    void unknownRunStillFallsBackToBigrams() {
        List<String> tokens = ChineseSegmenter.tokensForIndex("单明");

        assertEquals(List.of("单明"), tokens,
                "未登录的多字串只保留 bigram：单字 token 会因 AND 语义让查询失配：" + tokens);
    }

    @Test
    void tokenizerAndQueryTokenizerAreSymmetric() {
        // 写入侧与查询侧必须同构：否则"索引切了、查询没切"，中文检索依然失效
        assertEquals(IdentifierTokenizer.tokenize("客户订单明细"),
                IdentifierTokenizer.tokenizeQuery("客户订单明细"));
        assertFalse(IdentifierTokenizer.tokenize("客户订单明细").isBlank());
        assertTrue(IdentifierTokenizer.tokenize("客户订单明细").contains("客户"));
    }

    @Test
    void preservesIdentifierSplitting() {
        String tokens = IdentifierTokenizer.tokenize("ods_customer_orders");

        assertTrue(tokens.contains("ods"));
        assertTrue(tokens.contains("customer"));
        assertTrue(tokens.contains("orders"));
    }
}
