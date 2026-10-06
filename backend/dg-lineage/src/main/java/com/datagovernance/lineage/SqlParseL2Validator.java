package com.datagovernance.lineage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 血缘 L2 校验层（docs/10 §2、docs/09 §9.2）。
 *
 * <p>为什么需要它：SQL 静态解析有两类失败，含义完全不同 ——
 * <ol>
 *   <li><b>解析不出来</b>（方言、语法太复杂）：这是解析器的能力边界；</li>
 *   <li><b>信息不足</b>（{@code SELECT *} 缺 schema；无表限定的列在多个源表里都存在）：
 *       这是**输入不足**，而输入恰恰是平台自己有的 —— 平台存着 {@code datasetSchema}。</li>
 * </ol>
 * 因此 L2 的职责是：**用平台已有的 schema 把"解析不出来"变成"推得出来"**，
 * 并把真正无法判定的部分显式登记（而不是让它消失在 warnings 字符串里）。
 *
 * <p>三条纪律：
 * <ul>
 *   <li><b>推出来的边必须低置信度、可区分来源</b>：{@code parseLevel=derived}，
 *       使用者有权知道"这不是 SQL 直接告诉我们的"；</li>
 *   <li><b>推不出来就记账，不猜</b>：多义列（两个源表都有该列）绝不随便挑一个；</li>
 *   <li><b>"缺 schema" 与 "解析失败" 分开记</b>：前者能通过采集补上，后者不能 ——
 *       混在一起会让"血缘覆盖率为什么低"永远答不清。</li>
 * </ul>
 *
 * <p>本类是**纯逻辑 + 一次 schema 查询**：schema 查询通过 {@link SchemaLookup} 注入，
 * 因此可以用假实现做单元测试，不需要数据库。
 */
public class SqlParseL2Validator {

    /** 数据集 schema 查询（返回列名，小写化；查不到返回空集合）。 */
    public interface SchemaLookup {
        Set<String> columnsOf(String datasetUrn);
    }

    /** L2 补出来的一条列级边。 */
    public record ResolvedEdge(String fromUrn, String fromColumn, String toUrn, String toColumn,
                               String transform, String expression, double confidence, String parseLevel) {
    }

    /** 一条检查发现。 */
    public record Finding(String checkType, String severity, String resource, String message,
                          Map<String, Object> evidence) {
    }

    /** 一次 L2 校验的结果。 */
    public record Result(List<ResolvedEdge> edges, List<Finding> findings) {

        public static Result empty() {
            return new Result(List.of(), List.of());
        }
    }

    /** L2 补出的边使用的来源标识：与 sql_parse 区分开，便于"这条边是推出来的"一眼可辨。 */
    public static final String EDGE_SOURCE = "sql_parse_l2";

    private static final double STAR_CONFIDENCE = 0.65;
    private static final double DISAMBIGUATED_CONFIDENCE = 0.6;

    private final SchemaLookup schemaLookup;

    public SqlParseL2Validator(SchemaLookup schemaLookup) {
        this.schemaLookup = schemaLookup;
    }

    /**
     * 对一条语句执行 L2 校验。
     *
     * @param targetUrn       下游数据集 URN（解析不到则只能记账，不能建边）
     * @param sourceUrns      上游数据集 URN（已解析的）
     * @param warnings        解析器给出的警告（L2 的输入线索就来自这里）
     * @param parseLevel      解析器给出的级别（exact 的语句一般不需要 L2）
     * @param parseFailed     是否解析失败（失败时只记 parse_failed，不做推断）
     */
    public Result validate(String targetUrn, List<String> sourceUrns, List<String> warnings,
                           String parseLevel, boolean parseFailed) {
        if (parseFailed) {
            return new Result(List.of(), List.of(new Finding("parse_failed", "INFO", targetUrn,
                    "SQL 解析失败：该语句没有产出任何血缘（这是解析器能力边界，与 schema 缺失不同）",
                    Map.of("parseLevel", "failed"))));
        }
        if (targetUrn == null) {
            return new Result(List.of(), List.of(new Finding("target_unresolved", "WARN", null,
                    "目标表未能解析到平台资产：无法建立列级血缘（先采集目标表再解析）",
                    Map.of("sources", sourceUrns))));
        }

        List<Finding> findings = new ArrayList<>();
        List<ResolvedEdge> edges = new ArrayList<>();
        boolean starMentioned = warnings.stream().anyMatch(item -> item.contains("SELECT *"));
        boolean ambiguityMentioned = warnings.stream().anyMatch(item -> item.contains("无法定位到表"));

        if (starMentioned) {
            resolveSelectStar(targetUrn, sourceUrns, edges, findings);
        }
        if (ambiguityMentioned) {
            resolveAmbiguousColumns(targetUrn, sourceUrns, warnings, edges, findings);
        }
        return new Result(edges, findings);
    }

    /**
     * {@code SELECT *} 展开：{@code SELECT *} 的语义是**按位置透传同名同序的列**，
     * 因此只要知道上游有哪些列，就能确定地补出列级血缘。
     */
    private void resolveSelectStar(String targetUrn, List<String> sourceUrns,
                                   List<ResolvedEdge> edges, List<Finding> findings) {
        if (sourceUrns.isEmpty()) {
            findings.add(new Finding("select_star_unresolved", "WARN", targetUrn,
                    "存在 SELECT * 但上游表未解析到平台资产：无法展开列级血缘", Map.of()));
            return;
        }
        for (String sourceUrn : sourceUrns) {
            Set<String> columns = schemaLookup.columnsOf(sourceUrn);
            if (columns.isEmpty()) {
                findings.add(new Finding("select_star_unresolved", "WARN", targetUrn,
                        "存在 SELECT *，但上游 " + shortName(sourceUrn)
                                + " 的 schema 尚未采集：无法展开列级血缘（**先采集这张表**即可自动补上）",
                        Map.of("source", sourceUrn, "why", "missing_schema")));
                continue;
            }
            for (String column : columns) {
                edges.add(new ResolvedEdge(sourceUrn, column, targetUrn, column,
                        "DIRECT", "SELECT * 展开（依据平台已采集的 schema）",
                        STAR_CONFIDENCE, "derived"));
            }
            findings.add(new Finding("select_star_expanded", "INFO", targetUrn,
                    "SELECT * 已按平台 schema 展开 " + columns.size() + " 列（上游 "
                            + shortName(sourceUrn) + "）",
                    Map.of("source", sourceUrn, "columnCount", columns.size(),
                            "confidence", STAR_CONFIDENCE,
                            "note", "该边的置信度低于 exact：它来自平台 schema 而非 SQL 文本本身")));
        }
    }

    /**
     * 无表限定列的消歧：**只有当该列名恰好只存在于一个源表时**才能确定来源。
     *
     * <p>这是"用平台 schema 补输入"的第二个用例。很多 SQL 写得随意（{@code SELECT id, name FROM a JOIN b}），
     * 解析器无法定位；但如果 {@code name} 只在 {@code a} 里存在，来源就是确定的 ——
     * 这种确定性来自平台自己的元数据，比让解析器去猜可靠得多。
     */
    private void resolveAmbiguousColumns(String targetUrn, List<String> sourceUrns, List<String> warnings,
                                         List<ResolvedEdge> edges, List<Finding> findings) {
        Map<String, Set<String>> columnsBySource = new LinkedHashMap<>();
        for (String sourceUrn : sourceUrns) {
            columnsBySource.put(sourceUrn, schemaLookup.columnsOf(sourceUrn));
        }
        boolean anySchema = columnsBySource.values().stream().anyMatch(set -> !set.isEmpty());
        if (!anySchema) {
            findings.add(new Finding("ambiguous_column_unresolved", "WARN", targetUrn,
                    "存在无表限定的列，且所有上游表的 schema 都未采集：无法消歧（先采集上游表）",
                    Map.of("warningCount", warnings.size())));
            return;
        }

        for (String column : columnsMentionedInWarnings(warnings)) {
            List<String> matches = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : columnsBySource.entrySet()) {
                if (entry.getValue().contains(column)) {
                    matches.add(entry.getKey());
                }
            }
            if (matches.size() == 1) {
                edges.add(new ResolvedEdge(matches.get(0), column, targetUrn, column,
                        "DIRECT", "按平台 schema 消歧（该列只存在于一个源表）",
                        DISAMBIGUATED_CONFIDENCE, "derived"));
                findings.add(new Finding("ambiguous_column_resolved", "INFO", targetUrn,
                        "列 " + column + " 已按平台 schema 消歧到 " + shortName(matches.get(0)),
                        Map.of("column", column, "source", matches.get(0),
                                "confidence", DISAMBIGUATED_CONFIDENCE)));
            } else {
                // 多个源表都有该列：**不猜**。挑一个会让血缘看起来完整，但错的那条更难被发现。
                findings.add(new Finding("ambiguous_column_unresolved", "WARN", targetUrn,
                        "列 " + column + " 在多个上游表里都存在" + (matches.isEmpty() ? "（或都不存在）" : "")
                                + "：无法确定来源，**没有建边**"
                                + (matches.isEmpty() ? "（相关上游表 schema 可能未采集）" : ""),
                        Map.of("column", column, "candidates", matches)));
            }
        }
    }

    /** 从解析器警告里抽出"哪一列无法定位"。 */
    static Set<String> columnsMentionedInWarnings(List<String> warnings) {
        Set<String> columns = new LinkedHashSet<>();
        for (String warning : warnings) {
            if (!warning.contains("无法定位到表")) {
                continue;
            }
            // 解析器措辞："列 id 的来源 'id' 无法定位到表（无表限定且存在多个源表）"
            int start = warning.indexOf("来源 '");
            if (start >= 0) {
                int end = warning.indexOf('\'', start + 4);
                if (end > start) {
                    columns.add(warning.substring(start + 4, end).trim().toLowerCase(Locale.ROOT));
                    continue;
                }
            }
            if (warning.startsWith("列 ")) {
                int space = warning.indexOf(' ', 2);
                if (space > 2) {
                    columns.add(warning.substring(2, space).trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    private static String shortName(String urn) {
        int index = urn.lastIndexOf('.');
        return index > 0 ? urn.substring(index + 1) : urn;
    }
}
