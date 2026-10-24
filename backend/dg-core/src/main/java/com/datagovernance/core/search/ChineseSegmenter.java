package com.datagovernance.core.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 中文分词（docs/09 §9.3 的"词典分词"升级路径）。
 *
 * <p><b>它是什么</b>：内置领域词典 + **双向最大匹配**。命中词典的词作为一个 token 输出，
 * 未命中的连续字按 bigram 兜底（见 {@link IdentifierTokenizer}）。
 *
 * <p><b>它不是什么</b>（必须写在类注释里，否则会被当成通用分词器）：
 * <ul>
 *   <li>不做未登录词识别（新词只会被 bigram 兜底，不会被识别成词）；</li>
 *   <li>不做词形还原、不做繁简归一、不做拼音；</li>
 *   <li>词典是**内置的领域词表**，不是 jieba/zhparser 那种通用词典 —— 覆盖范围有限。</li>
 * </ul>
 *
 * <p>为什么仍然值得做：bigram 的每个 token 都很短、区分度低，词典词是更具体的 lexeme，
 * 在 tsvector 里能显著提升排序质量（"客户订单"作为一个词比"户订"这种碎片更有信息量）。
 * 而 bigram 兜底保证**召回不低于改造前** —— 宁可保留旧的召回，也不要用一个不完整的词典
 * 把原本能搜到的结果弄丢。
 *
 * <p>升级路径：装 {@code pg_jieba/zhparser} 后把切分函数换成词典分词即可（写入与查询两侧同改）。
 */
public final class ChineseSegmenter {

    /** 词典中词的最大长度（用于最大匹配的上界）。 */
    private static final int MAX_WORD_LENGTH = 8;

    private static final Set<String> LEXICON = load();

    private ChineseSegmenter() {
    }

    /** 词典规模（供诊断/测试断言，避免"以为加载了词典其实文件没进包"）。 */
    public static int lexiconSize() {
        return LEXICON.size();
    }

    public static boolean isKnownWord(String word) {
        return word != null && LEXICON.contains(word);
    }

    /**
     * 双向最大匹配：正向与反向各切一次，取"词数更少、单字更少"的那个结果。
     *
     * <p>为什么两个方向都要：正向最大匹配在"研究生命科学"上会切出"研究生/命/科学"，
     * 反向则得到"研究/生命/科学"。取词数更少的结果能规避大部分这类歧义，
     * 而且规则简单、可解释（比隐马尔可夫模型更符合"可审查"的要求）。
     */
    public static List<String> segment(String run) {
        if (run == null || run.isEmpty()) {
            return List.of();
        }
        List<String> forward = maximumMatch(run, false);
        List<String> backward = maximumMatch(run, true);
        return score(forward) <= score(backward) ? forward : backward;
    }

    private static List<String> maximumMatch(String run, boolean reverse) {
        List<String> tokens = new ArrayList<>();
        int length = run.length();
        int index = reverse ? length : 0;
        while (reverse ? index > 0 : index < length) {
            int matched = 0;
            for (int size = Math.min(MAX_WORD_LENGTH, reverse ? index : length - index); size >= 2; size--) {
                int start = reverse ? index - size : index;
                String candidate = run.substring(start, start + size);
                if (LEXICON.contains(candidate)) {
                    if (reverse) {
                        tokens.add(0, candidate);
                    } else {
                        tokens.add(candidate);
                    }
                    matched = size;
                    break;
                }
            }
            if (matched == 0) {
                String single = String.valueOf(run.charAt(reverse ? index - 1 : index));
                if (reverse) {
                    tokens.add(0, single);
                } else {
                    tokens.add(single);
                }
                matched = 1;
            }
            index += reverse ? -matched : matched;
        }
        return tokens;
    }

    /** 词数越少越好；单字（碎片）越多越差。 */
    private static int score(List<String> tokens) {
        int singles = 0;
        for (String token : tokens) {
            if (token.length() == 1) {
                singles++;
            }
        }
        return tokens.size() + singles * 2;
    }

    /**
     * 一段中文的检索 token：词典词在前，随后是**全量 bigram**（保证召回不低于改造前）。
     * 去重但保持顺序，便于调试时看出切分来源。
     */
    public static List<String> tokensForIndex(String run) {
        if (run == null || run.isEmpty()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : segment(run)) {
            // 多字串里不保留单字：查询是 AND 语义，索引里没有的单字会让整个查询失配
            // （例如查「单明」时若产生单字 token，而索引里只有 bigram「单明」，就会一条都搜不到）。
            // 单字只在整段就是一个字时保留。
            if (token.length() > 1 || run.length() == 1) {
                tokens.add(token);
            }
        }
        for (int index = 0; index + 1 < run.length(); index++) {
            tokens.add(run.substring(index, index + 2));
        }
        return List.copyOf(tokens);
    }

    private static Set<String> load() {
        Set<String> words = new LinkedHashSet<>();
        try (InputStream stream = ChineseSegmenter.class.getResourceAsStream("/search/zh-lexicon.txt")) {
            if (stream == null) {
                // 词典缺失不阻断启动：退化为纯 bigram（与改造前一致），但不能静默——日志会由调用方看到
                return Set.of();
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String word = line.trim();
                    if (word.length() >= 2 && word.codePoints().allMatch(ChineseSegmenter::isHan)) {
                        words.add(word);
                    }
                }
            }
        } catch (IOException e) {
            return Set.of();
        }
        return Set.copyOf(words);
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }
}
