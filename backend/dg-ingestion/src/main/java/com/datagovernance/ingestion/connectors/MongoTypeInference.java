package com.datagovernance.ingestion.connectors;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MongoDB 的类型推断规则（从 {@link MongoSource} 抽出来单独成类）。
 *
 * <p>为什么值得单独一个类并单独测试：MongoDB 没有 schema，"表结构"完全是推断出来的，
 * 推断规则一旦悄悄变化，用户在目录里看到的结构就会跟着变，而**没有任何报错**。
 * 这类"沉默的错误结果"只能靠把规则写成可断言的纯函数来守住。
 *
 * <p>规则（与文档一致，不随实现漂移）：
 * <ol>
 *   <li>数值族（int/long/double/decimal）统一记为 {@code number}：
 *       MongoDB 里这几种混用是常态，判成 mixed 只会产生噪音；</li>
 *   <li>跨族不一致（string vs number vs object）记为 {@code mixed(...)}：
 *       "字段类型不稳定"本身就是最该被看到的治理信号；</li>
 *   <li>空数组不携带元素类型 → {@code array<unknown>}，且在合并时作为通配；</li>
 *   <li>数组元素类型不一致 → {@code array<mixed>}；</li>
 *   <li>字段可空的两种来源都要算：部分文档缺该字段，或见过 null 值。</li>
 * </ol>
 */
public final class MongoTypeInference {

    private MongoTypeInference() {
    }

    /** 标量类型名（数值族收敛为 number）。 */
    public static String scalarType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Double
                || value instanceof java.math.BigDecimal || value instanceof org.bson.types.Decimal128) {
            return "number";
        }
        if (value instanceof java.util.Date || value instanceof java.time.Instant) {
            return "date";
        }
        if (value instanceof org.bson.types.ObjectId) {
            return "objectId";
        }
        if (value instanceof byte[]) {
            return "binary";
        }
        return value.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    /** 数组类型名：{@code array<元素类型>} / {@code array<mixed>} / {@code array<unknown>}。 */
    public static String arrayType(java.util.List<?> list) {
        if (list == null || list.isEmpty()) {
            return "array<unknown>";
        }
        Set<String> types = new LinkedHashSet<>();
        for (Object element : list) {
            if (element instanceof org.bson.Document) {
                types.add("object");
            } else if (element instanceof java.util.List) {
                types.add("array<unknown>");
            } else {
                types.add(scalarType(element));
            }
            if (types.size() > 2) {
                break;
            }
        }
        if (types.size() == 1) {
            String only = types.iterator().next();
            return only.startsWith("array<") ? "array<mixed>" : "array<" + only + ">";
        }
        // 元素跨族 → mixed；同族（都是 array<...>）按具体类型处理
        boolean allArrays = types.stream().allMatch(type -> type.startsWith("array<"));
        return allArrays ? "array<mixed>" : "array<mixed>";
    }

    /**
     * 合并观察到的类型集合，得到对外的类型名。
     *
     * @param types    已去掉 null 的类型集合（顺序无关）
     * @param sawNull  是否见过 null 值
     */
    public static String summarize(Set<String> types, boolean sawNull) {
        if (types.isEmpty()) {
            return sawNull ? "null" : "unknown";
        }
        if (types.size() == 1) {
            String only = types.iterator().next();
            return "array<unknown>".equals(only) ? "array<unknown>" : only;
        }
        // 无类型数组是通配：有具体数组类型时以具体类型为准
        Set<String> concrete = new LinkedHashSet<>(types);
        concrete.remove("array<unknown>");
        if (concrete.isEmpty()) {
            return "array<unknown>";
        }
        Set<String> families = new LinkedHashSet<>();
        for (String type : concrete) {
            families.add(type.startsWith("array<") ? "array" : type);
        }
        if (families.size() == 1) {
            String only = concrete.iterator().next();
            return only.startsWith("array<") ? only : only;
        }
        return "mixed(" + String.join("|", families) + ")";
    }

    /** 字段是否可空：部分文档缺该字段，或见过 null 值。 */
    public static boolean nullable(int observedInDocs, int sampledDocs, boolean sawNull) {
        return observedInDocs < sampledDocs || sawNull;
    }

    /** 覆盖度备注（稀疏字段是重要信号，必须写出来）。 */
    public static String coverageNote(int observedInDocs, int sampledDocs, boolean sawNull) {
        if (sampledDocs == 0) {
            return "采样为空";
        }
        int percent = (int) Math.round(observedInDocs * 100.0 / sampledDocs);
        StringBuilder note = new StringBuilder();
        if (percent >= 100) {
            note.append("采样中每条文档都有该字段");
        } else {
            note.append("采样中 ").append(percent).append("% 的文档含该字段（稀疏字段）");
        }
        if (sawNull) {
            note.append("；出现过 null 值");
        }
        return note.toString();
    }
}
