package com.datagovernance.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 契约 Schema 兼容性 diff 引擎（docs/09 §9.5）。
 *
 * <p>设计立场：<b>契约是可执行的接口</b>，所以"改了列"必须能自动判定是否为破坏性变更，
 * 而不是靠人开会讨论。因此这里是一个纯函数引擎：输入两份 schema 描述，输出结构化变更清单，
 * 每条变更带严重级别与理由。
 *
 * <p>破坏性判定（BACKWARD 兼容语义）：
 * <ul>
 *   <li><b>删列</b> —— 破坏性（消费者会读不到）</li>
 *   <li><b>类型收窄</b>（bigint → int）—— 破坏性；拓宽（int → bigint）兼容</li>
 *   <li><b>可选改必填</b> —— 破坏性；必填改可选兼容</li>
 *   <li><b>新增必填列</b> —— 破坏性；新增可选列兼容</li>
 *   <li><b>改语义 / 改主键</b> —— 破坏性（口径变了，值看起来还合法）</li>
 *   <li><b>移除质量约束</b> —— 不破坏接口，但必须显性记录（否则"悄悄降低承诺"没人知道）</li>
 * </ul>
 */
public final class ContractDiff {

    private ContractDiff() {
    }

    /** 一条变更。 */
    public record Change(String kind, String column, String severity, String message,
                         Object before, Object after) {

        public boolean breaking() {
            return "MAJOR".equals(severity);
        }
    }

    /** diff 结果。 */
    public record Result(String verdict, List<Change> changes, boolean versionBumpSufficient,
                         String requiredVersionBump, List<String> notes) {

        public Result {
            changes = List.copyOf(changes);
            notes = List.copyOf(notes);
        }

        public boolean breaking() {
            return changes.stream().anyMatch(Change::breaking);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("verdict", verdict);
            payload.put("breaking", breaking());
            payload.put("requiredVersionBump", requiredVersionBump);
            payload.put("versionBumpSufficient", versionBumpSufficient);
            payload.put("notes", notes);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Change change : changes) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kind", change.kind());
                item.put("column", change.column());
                item.put("severity", change.severity());
                item.put("message", change.message());
                item.put("before", change.before());
                item.put("after", change.after());
                items.add(item);
            }
            payload.put("changes", items);
            return payload;
        }
    }

    /**
     * 比较两份契约 schema。
     *
     * @param oldSpec      已发布契约的 contractSpec 数据（null 表示首次注册）
     * @param newSpec      待发布契约的 contractSpec 数据
     * @param oldVersion   已发布版本（语义化）
     * @param newVersion   待发布版本（语义化）
     */
    @SuppressWarnings("unchecked")
    public static Result compare(Map<String, Object> oldSpec, Map<String, Object> newSpec,
                                String oldVersion, String newVersion) {
        List<Change> changes = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        Map<String, Map<String, Object>> oldFields = fieldsOf(oldSpec);
        Map<String, Map<String, Object>> newFields = fieldsOf(newSpec);

        if (oldSpec == null) {
            notes.add("首次注册：没有已发布版本，不构成兼容性变更");
            return new Result("INITIAL", List.of(), true, "none", notes);
        }

        for (Map.Entry<String, Map<String, Object>> entry : oldFields.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> oldField = entry.getValue();
            Map<String, Object> newField = newFields.get(name);
            if (newField == null) {
                changes.add(new Change("column_removed", name, "MAJOR",
                        "删除了列 " + name + "：消费者的读取会直接失败", oldField.get("type"), null));
                continue;
            }
            String oldType = str(oldField.get("type"));
            String newType = str(newField.get("type"));
            TypeAssessment assessment = assessTypeChange(oldType, newType);
            if (!assessment.compatible()) {
                changes.add(new Change("type_changed", name, "MAJOR",
                        "列 " + name + " 的类型由 " + oldType + " 变为 " + newType + "：" + assessment.reason(),
                        oldType, newType));
            } else if (!Objects.equals(normalize(oldType), normalize(newType))) {
                changes.add(new Change("type_widened", name, "MINOR",
                        "列 " + name + " 的类型由 " + oldType + " 拓宽为 " + newType + "：" + assessment.reason(),
                        oldType, newType));
            }

            boolean oldRequired = required(oldField);
            boolean newRequired = required(newField);
            if (!oldRequired && newRequired) {
                changes.add(new Change("nullability_tightened", name, "MAJOR",
                        "列 " + name + " 由可选改为必填：已有数据可能违反新约束", false, true));
            } else if (oldRequired && !newRequired) {
                changes.add(new Change("nullability_relaxed", name, "MINOR",
                        "列 " + name + " 由必填改为可选（放宽约束）", true, false));
            }

            String oldSemantic = str(oldField.get("semantic"));
            String newSemantic = str(newField.get("semantic"));
            if (!Objects.equals(oldSemantic, newSemantic) && oldSemantic != null) {
                changes.add(new Change("semantic_changed", name, "MAJOR",
                        "列 " + name + " 的语义由 " + oldSemantic + " 变为 " + newSemantic
                                + "：值看起来仍然合法，但口径已经变了", oldSemantic, newSemantic));
            }
        }

        for (Map.Entry<String, Map<String, Object>> entry : newFields.entrySet()) {
            if (oldFields.containsKey(entry.getKey())) {
                continue;
            }
            boolean isRequired = required(entry.getValue());
            changes.add(new Change("column_added", entry.getKey(), isRequired ? "MAJOR" : "MINOR",
                    "新增" + (isRequired ? "必填" : "可选") + "列 " + entry.getKey(),
                    null, entry.getValue().get("type")));
        }

        List<String> oldPk = stringList(oldSpec.get("primaryKey"));
        List<String> newPk = stringList(newSpec.get("primaryKey"));
        if (!oldPk.equals(newPk) && !oldPk.isEmpty()) {
            changes.add(new Change("primary_key_changed", null, "MAJOR",
                    "主键由 " + oldPk + " 变为 " + newPk + "：下游去重与关联逻辑会失效",
                    oldPk, newPk));
        }

        int oldChecks = checkCount(oldSpec);
        int newChecks = checkCount(newSpec);
        if (newChecks < oldChecks) {
            changes.add(new Change("quality_weakened", null, "MINOR",
                    "质量约束由 " + oldChecks + " 条减少到 " + newChecks + " 条：接口没坏，但承诺被悄悄降低了",
                    oldChecks, newChecks));
        }
        String oldSla = str(slaOf(oldSpec));
        String newSla = str(slaOf(newSpec));
        if (oldSla != null && newSla != null && !oldSla.equals(newSla)) {
            changes.add(new Change("sla_changed", null, "MINOR",
                    "SLA 由 " + oldSla + " 变为 " + newSla, oldSla, newSla));
        }

        String required = requiredBump(changes);
        boolean sufficient = versionBumpSufficient(oldVersion, newVersion, required);
        if (!sufficient) {
            changes.add(new Change("version_bump_insufficient", null, "MAJOR",
                    "版本号 " + oldVersion + " → " + newVersion + " 与变更性质不符：本次应为 " + required + " 级变更",
                    oldVersion, newVersion));
        }

        String verdict = changes.stream().anyMatch(Change::breaking) ? "BLOCK"
                : changes.isEmpty() ? "PASS" : "WARN";
        return new Result(verdict, changes, sufficient, required, notes);
    }

    // -------------------------------------------------------------- 类型兼容

    public record TypeAssessment(boolean compatible, String reason) {
    }

    private static final List<String> INTEGRAL_RANK = List.of(
            "tinyint", "smallint", "int", "integer", "bigint", "numeric", "decimal", "real", "double");
    private static final Pattern PRECISION = Pattern.compile("\\(.*\\)");

    /** 类型变更是否兼容（拓宽兼容、收窄破坏、跨族破坏）。 */
    public static TypeAssessment assessTypeChange(String from, String to) {
        if (from == null || to == null) {
            return new TypeAssessment(true, "类型未声明，不做判定");
        }
        String left = normalize(from);
        String right = normalize(to);
        if (left.equals(right)) {
            return new TypeAssessment(true, "类型未变");
        }
        if (family(left).equals(family(right))) {
            if ("string".equals(family(left)) || "boolean".equals(family(left))) {
                return new TypeAssessment(true, "同族类型（" + family(left) + "），精度差异不影响消费者");
            }
            if ("temporal".equals(family(left))) {
                boolean widening = left.equals("date") && right.contains("timestamp");
                return widening
                        ? new TypeAssessment(true, "日期拓宽为时间戳，消费者仍可解析")
                        : new TypeAssessment(false, "时间类型收窄会让已有值失去时间部分");
            }
            int fromRank = INTEGRAL_RANK.indexOf(left);
            int toRank = INTEGRAL_RANK.indexOf(right);
            if (fromRank >= 0 && toRank >= 0) {
                return toRank >= fromRank
                        ? new TypeAssessment(true, "数值类型拓宽（" + left + " → " + right + "）")
                        : new TypeAssessment(false, "数值类型收窄（" + left + " → " + right + "）会截断或溢出");
            }
        }
        return new TypeAssessment(false, "跨类型族变更（" + family(left) + " → " + family(right) + "）");
    }

    private static String family(String type) {
        if (INTEGRAL_RANK.contains(type) || type.contains("float") || type.contains("double")
                || type.contains("decimal") || type.contains("numeric")) {
            return "numeric";
        }
        if (type.contains("char") || type.contains("text") || type.contains("string")
                || type.contains("varchar") || type.contains("clob")) {
            return "string";
        }
        if (type.contains("date") || type.contains("time") || type.contains("timestamp")) {
            return "temporal";
        }
        if (type.contains("bool")) {
            return "boolean";
        }
        if (type.contains("json") || type.contains("array") || type.contains("struct")
                || type.contains("map")) {
            return "nested";
        }
        if (type.contains("binary") || type.contains("blob") || type.contains("bytea")) {
            return "binary";
        }
        return "other:" + type;
    }

    private static String normalize(String type) {
        return type == null ? "" : PRECISION.matcher(type.trim().toLowerCase(Locale.ROOT))
                .replaceAll("").trim();
    }

    // ---------------------------------------------------------------- 版本号

    /** 变更性质 → 需要的版本级别：MAJOR / MINOR / PATCH / none。 */
    public static String requiredBump(List<Change> changes) {
        if (changes.stream().anyMatch(c -> "MAJOR".equals(c.severity()))) {
            return "MAJOR";
        }
        if (changes.stream().anyMatch(c -> "MINOR".equals(c.severity()))) {
            return "MINOR";
        }
        return changes.isEmpty() ? "none" : "PATCH";
    }

    /** 版本号提升是否满足变更性质（不满足就是"版本号在骗人"）。 */
    public static boolean versionBumpSufficient(String oldVersion, String newVersion, String required) {
        if ("none".equals(required)) {
            return true;
        }
        int[] before = parseVersion(oldVersion);
        int[] after = parseVersion(newVersion);
        if (before == null || after == null) {
            return false;
        }
        return switch (required) {
            case "MAJOR" -> after[0] > before[0];
            case "MINOR" -> after[0] > before[0] || after[1] > before[1];
            default -> after[0] > before[0] || after[1] > before[1] || after[2] > before[2];
        };
    }

    public static int[] parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
            return new int[] {major, minor, patch};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 工具

    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, Object>> fieldsOf(Map<String, Object> spec) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (spec == null) {
            return out;
        }
        Object schema = spec.get("schema");
        Object raw = schema;
        if (schema instanceof Map<?, ?> map && map.get("fields") != null) {
            raw = map.get("fields");
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> field) {
                    Object name = field.get("name");
                    if (name == null) {
                        continue;
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    field.forEach((key, value) -> entry.put(String.valueOf(key), value));
                    out.put(String.valueOf(name), entry);
                }
            }
        }
        return out;
    }

    private static boolean required(Map<String, Object> field) {
        Object value = field.get("required");
        if (value == null) {
            // ODCS/多数契约写法里 nullable=false 等价于必填
            Object nullable = field.get("nullable");
            return nullable != null && !Boolean.parseBoolean(String.valueOf(nullable));
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int checkCount(Map<String, Object> spec) {
        Object quality = spec == null ? null : spec.get("quality");
        return quality instanceof List<?> list ? list.size() : 0;
    }

    private static Object slaOf(Map<String, Object> spec) {
        return spec == null ? null : spec.get("sla");
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
