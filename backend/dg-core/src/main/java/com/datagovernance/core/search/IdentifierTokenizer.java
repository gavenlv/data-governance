package com.datagovernance.core.search;

import java.util.regex.Pattern;

/**
 * 标识符切分（docs/09 §9.3 的实现要点）。
 *
 * <p>为什么必须有它：纯按空白切词会让 {@code ods_orders} 变成一个 token，
 * 搜 {@code orders} 就搜不到 —— 而"按表名片段找表"是数据目录里最高频的检索动作。
 * 因此必须叠加 {@code _ / - / . / : / \} 与驼峰切分。
 *
 * <p><b>覆盖边界（诚实标注）</b>：本实现不做中文**词典**分词，用 bigram 替代
 * （见 {@link #flushCjk}）。真分词需要 {@code pg_jieba/zhparser} 或迁到 OpenSearch + IK
 * （docs/21 §6 已固化为已知限制）。
 */
public final class IdentifierTokenizer {

    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final char[] SEPARATORS = {'_', '-', '.', ':', '/', '\\', '#', '@', '+'};

    private IdentifierTokenizer() {
    }

    /** 把标识符切分成可检索的词；同时为中文生成**二元切分（bigram）**。 */
    public static String tokenize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String spaced = CAMEL_BOUNDARY.matcher(value).replaceAll("$1 $2");
        StringBuilder builder = new StringBuilder(spaced.length() + 32);
        StringBuilder cjkRun = new StringBuilder();
        for (char ch : spaced.toCharArray()) {
            if (isSeparator(ch) || (!isCjk(ch) && !Character.isLetterOrDigit(ch))) {
                // 标点（含中文标点「，。；」）一律当分隔符：否则「订单，明细」会切出
                // 「，明细」这种带标点的 token，写进索引后永远匹配不上
                flushCjk(cjkRun, builder);
                builder.append(' ');
                continue;
            }
            if (isCjk(ch)) {
                cjkRun.append(ch);
                continue;
            }
            flushCjk(cjkRun, builder);
            builder.append(ch);
        }
        flushCjk(cjkRun, builder);
        return collapseSpaces(builder.toString());
    }
    /**
     * 中文按**二元切分（bigram）**展开：把"订单明细"变成"订单 单明 明细"。
     *
     * <p>为什么用 bigram 而不是不切分也不上词典分词器：
     * <ul>
     *   <li>整段中文不切分时，PG 的 {@code simple} 配置会按空白切词，
     *       于是"订单明细"是一个 token，搜"订单"搜不到；</li>
     *   <li>词典分词（pg_jieba / zhparser）需要装扩展或换 OpenSearch，成本与不可控性都更高；</li>
     *   <li>bigram 只需要在**写入与查询两侧做同一件事**，就能命中任意连续两字子串 ——
     *       对"找表名/找标签"这类场景足够，且完全可控、可测试。</li>
     * </ul>
     *
     * <p><b>诚实边界</b>：bigram 不是词典分词，因此
     * ① 单字查询命中不了（"表" 太短，见 {@link #MIN_QUERY_LENGTH}）；
     * ② 不做词形还原与同义词（同义词靠术语表扩展，见 {@code HybridSearchService}）；
     * ③ 召回率高于精确率（可能多命中），排序质量依赖后续 RRF 融合。
     */
    private static void flushCjk(StringBuilder run, StringBuilder out) {
        if (run.length() == 0) {
            return;
        }
        if (run.length() == 1) {
            out.append(run).append(' ');
        } else {
            for (int i = 0; i + 1 < run.length(); i++) {
                out.append(run, i, i + 2).append(' ');
            }
        }
        run.setLength(0);
    }

    /** 查询串也要做同样的切分，否则写入侧切了、查询侧没切，中文检索依然是坏的。 */
    public static String tokenizeQuery(String query) {
        return tokenize(query);
    }

    /** 中文检索的最小查询长度（bigram 决定单字无意义）。 */
    public static final int MIN_QUERY_LENGTH = 2;

    private static boolean isCjk(char ch) {
        return Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
    }

    private static boolean isSeparator(char ch) {
        for (char separator : SEPARATORS) {
            if (ch == separator) {
                return true;
            }
        }
        return false;
    }

    private static String collapseSpaces(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean previousSpace = false;
        for (char ch : value.toCharArray()) {
            boolean space = Character.isWhitespace(ch);
            if (space) {
                if (!previousSpace) {
                    out.append(' ');
                }
            } else {
                out.append(ch);
            }
            previousSpace = space;
        }
        return out.toString().trim();
    }

    /**
     * 构建可检索文本：展示名 + URN + 路径段 + 描述 + 标签（全部经**同一套**切分）。
     *
     * <p>描述也必须切分（含中文 bigram）：早期版本把描述原样拼进去，
     * 结果是「描述里的中文只有整段一个 token」——查询侧按 bigram 切，永远匹配不上，
     * 于是"按描述里的中文找资产"静默失效。写入侧与查询侧必须做同一件事，这是硬约束。
     */
    public static String buildSearchText(String displayName, String urn, String description,
                                         java.util.List<String> tags) {
        StringBuilder text = new StringBuilder();
        appendTokenized(text, displayName);
        if (urn != null) {
            appendTokenized(text, urn.replace(':', ' '));
            String path = urn.contains(":") ? urn.substring(urn.lastIndexOf(':') + 1) : urn;
            appendTokenized(text, path);
        }
        appendTokenized(text, description);
        if (tags != null) {
            for (String tag : tags) {
                appendTokenized(text, tag);
            }
        }
        return text.toString().trim();
    }

    private static void appendTokenized(StringBuilder text, String value) {
        String tokenized = tokenize(value);
        if (!tokenized.isEmpty()) {
            text.append(tokenized).append(' ');
        }
    }
}
