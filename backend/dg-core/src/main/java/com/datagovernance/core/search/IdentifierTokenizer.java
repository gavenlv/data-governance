package com.datagovernance.core.search;

import java.util.regex.Pattern;

/**
 * 标识符切分（docs/09 §9.3 的实现要点）。
 *
 * <p>为什么必须有它：纯按空白切词会让 {@code ods_orders} 变成一个 token，
 * 搜 {@code orders} 就搜不到 —— 而"按表名片段找表"是数据目录里最高频的检索动作。
 * 因此必须叠加 {@code _ / - / . / : / \} 与驼峰切分。
 *
 * <p><b>覆盖边界（诚实标注）</b>：本实现不做中文分词。中文全文检索需要
 * {@code pg_jieba/zhparser} 或迁到 OpenSearch + IK（docs/21 §6 已固化为已知限制）。
 */
public final class IdentifierTokenizer {

    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final char[] SEPARATORS = {'_', '-', '.', ':', '/', '\\', '#', '@', '+'};

    private IdentifierTokenizer() {
    }

    /** 把标识符切成可检索的词；空输入返回空串。 */
    public static String tokenize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String spaced = CAMEL_BOUNDARY.matcher(value).replaceAll("$1 $2");
        StringBuilder builder = new StringBuilder(spaced.length() + 8);
        for (char ch : spaced.toCharArray()) {
            builder.append(isSeparator(ch) ? ' ' : ch);
        }
        return collapseSpaces(builder.toString());
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

    /** 构建可检索文本：展示名 + URN + 路径段 + 描述 + 标签（全部经切分）。 */
    public static String buildSearchText(String displayName, String urn, String description,
                                         java.util.List<String> tags) {
        StringBuilder text = new StringBuilder();
        appendTokenized(text, displayName);
        if (urn != null) {
            appendTokenized(text, urn.replace(':', ' '));
            String path = urn.contains(":") ? urn.substring(urn.lastIndexOf(':') + 1) : urn;
            appendTokenized(text, path);
        }
        if (description != null && !description.isBlank()) {
            text.append(description.trim()).append(' ');
        }
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
