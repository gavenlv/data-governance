package com.datagovernance.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 策略编译器（docs/09 §9.7、ADR-008）。
 *
 * <p>设计文档对这块的要求只有一条是硬性的：<b>编译器必须可测试</b> ——
 * "给定策略 → 期望产物"的快照测试，否则错误策略直接造成数据泄露或大面积不可用。
 * 因此这里是一个<b>纯函数</b>：输入策略定义与资产上下文，输出目标引擎的产物，
 * 不碰数据库、不碰时间以外的任何外部状态。
 *
 * <p>编译目标（执行层全部是现成件，本平台不重造）：
 * <ul>
 *   <li>{@code trino} —— 行过滤谓词 + 列掩码表达式（SystemAccessControl 的输入）</li>
 *   <li>{@code warehouse} —— GRANT/REVOKE 语句 + 行访问策略骨架（Snowflake RAP / BigQuery policy tags）</li>
 *   <li>{@code bi} —— 数据集可见性与字段隐藏配置（Superset/Tableau；**只作消费端，不是安全边界**）</li>
 *   <li>{@code sdk} —— 令牌声明里的可见范围（仅作辅助，不可作为唯一防线）</li>
 * </ul>
 *
 * <p>产物里刻意保留注释头（策略名/版本/资源范围），因为下发的产物最终要被人审阅 ——
 * 一段没有出处的谓词是没法审计的。
 */
public final class PolicyCompiler {

    /** 标识符白名单：不匹配就拒绝编译（不是转义 —— 转义会给人"支持任意写法"的错觉）。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private static final Pattern ROLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_$-]*");

    private PolicyCompiler() {
    }

    /**
     * 一条可编译的策略（内部 IR）。
     *
     * @param name           策略名（唯一）
     * @param version        版本（编译产物按版本存档，便于回答"当时下发的是什么"）
     * @param target         目标引擎：trino / warehouse / bi / sdk
     * @param effect         效果：ROW_FILTER / COLUMN_MASK / GRANT / DENY
     * @param resourceScope  资源范围：{prefix: "urn:dg:Dataset:prod...", classification: ["L3","L4"], domains: [...]}
     * @param subjectScope   主体范围：{roles: [...], users: [...], teams: [...], mode: ALLOW|EXCEPT}
     * @param condition      条件：{column: "region", operator: "=", value: "CN"} 或
     *                       {mask: "hash"} / {mask: "null"} / {mask: "constant", value: "***"}
     * @param priority       优先级（数字小的先匹配）
     */
    public record PolicySpec(
            String name,
            int version,
            String target,
            String effect,
            Map<String, Object> resourceScope,
            Map<String, Object> subjectScope,
            Map<String, Object> condition,
            int priority) {

        public PolicySpec {
            resourceScope = resourceScope == null ? Map.of() : Map.copyOf(resourceScope);
            subjectScope = subjectScope == null ? Map.of() : Map.copyOf(subjectScope);
            condition = condition == null ? Map.of() : Map.copyOf(condition);
            target = target == null ? "trino" : target.toLowerCase(Locale.ROOT);
            effect = effect == null ? "ROW_FILTER" : effect.toUpperCase(Locale.ROOT);
        }

        public List<String> validate() {
            List<String> errors = new ArrayList<>();
            if (name == null || name.isBlank()) {
                errors.add("缺少策略名");
            }
            if (!List.of("trino", "warehouse", "bi", "sdk").contains(target)) {
                errors.add("不支持的目标引擎：" + target + "（支持 trino / warehouse / bi / sdk）");
            }
            if (!List.of("ROW_FILTER", "COLUMN_MASK", "GRANT", "DENY").contains(effect)) {
                errors.add("不支持的效果：" + effect + "（支持 ROW_FILTER / COLUMN_MASK / GRANT / DENY）");
            }
            if (resourceScope.isEmpty()) {
                errors.add("资源范围不能为空：策略必须说清楚它管哪些资产（否则就是全库生效，风险太大）");
            }
            if (subjectScope.isEmpty()) {
                errors.add("主体范围不能为空：策略必须说清楚它作用于谁");
            }
            if ("ROW_FILTER".equals(effect) && condition.get("column") == null) {
                errors.add("行过滤策略必须提供 condition.column");
            }
            // 掩码要求的严格程度按目标区分：Trino/数仓要生成**掩码表达式**，
            // 而 BI 只是"隐藏该列"，不需要（也不应该编造）一个掩码表达式
            if ("COLUMN_MASK".equals(effect) && condition.get("column") == null) {
                errors.add("列掩码策略必须提供 condition.column");
            }
            if ("COLUMN_MASK".equals(effect) && !"bi".equals(target) && condition.get("mask") == null) {
                errors.add("目标 " + target + " 的列掩码策略必须提供 condition.mask"
                        + "（null / hash / constant / partial / year）");
            }
            if (List.of("GRANT", "DENY").contains(effect) && permissions().isEmpty()) {
                errors.add(effect + " 策略必须提供 subjectScope.permissions（如 [SELECT]）");
            }
            return errors;
        }

        public List<String> permissions() {
            Object raw = subjectScope.get("permissions");
            if (raw instanceof List<?> list) {
                return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
            }
            return List.of();
        }
    }

    /**
     * 编译产物。
     *
     * @param target     目标引擎
     * @param effect     效果
     * @param artifact   产物文本（人类可读、可审计；带注释头说明来源）
     * @param metadata   附带的机器可读信息（谓词、掩码表达式、资源匹配条件等）
     */
    public record CompiledPolicy(String target, String effect, String artifact,
                                 Map<String, Object> metadata) {

        public Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("target", target);
            out.put("effect", effect);
            out.put("artifact", artifact);
            out.put("metadata", metadata);
            return out;
        }
    }

    /** 编译一条策略。非法策略直接拒绝（不给"半条策略"）。 */
    public static CompiledPolicy compile(PolicySpec spec) {
        List<String> errors = spec.validate();
        if (!errors.isEmpty()) {
            throw new AccessPolicyException("策略 " + spec.name() + " 无法编译：" + String.join("；", errors));
        }
        return switch (spec.target()) {
            case "trino" -> compileTrino(spec);
            case "warehouse" -> compileWarehouse(spec);
            case "bi" -> compileBi(spec);
            case "sdk" -> compileSdk(spec);
            default -> throw new AccessPolicyException("不支持的目标引擎：" + spec.target());
        };
    }

    // ------------------------------------------------------------------ Trino

    /**
     * Trino：行过滤谓词 + 列掩码表达式。
     *
     * <p>产物形状对应 {@code ConnectorAccessControl.getRowFilter()} / {@code getColumnMask()}
     * 的返回值（字符串谓词 / SQL 表达式）。我们只生成**产物**，不部署插件 ——
     * 插件属于执行层（现成件），docs/20 §8 已明确不自研执行引擎。
     */
    private static CompiledPolicy compileTrino(PolicySpec spec) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resourceScope", spec.resourceScope());
        metadata.put("subjectScope", spec.subjectScope());
        metadata.put("priority", spec.priority());

        String header = """
                -- 策略：%s（v%d）
                -- 目标：Trino SystemAccessControl / ConnectorAccessControl
                -- 资源：%s
                -- 主体：%s
                """.formatted(spec.name(), spec.version(), describe(spec.resourceScope()),
                describe(spec.subjectScope()));

        if ("ROW_FILTER".equals(spec.effect())) {
            String predicate = rowPredicate(spec.condition());
            metadata.put("rowFilter", predicate);
            metadata.put("applyToColumns", List.of());
            return new CompiledPolicy("trino", "ROW_FILTER",
                    header + "-- 行过滤谓词（注入查询 WHERE）：\n" + predicate + "\n", metadata);
        }
        if ("COLUMN_MASK".equals(spec.effect())) {
            String column = identifier(str(spec.condition().get("column")), "condition.column");
            String maskExpression = maskExpression(spec.condition());
            metadata.put("column", column);
            metadata.put("columnMask", maskExpression);
            return new CompiledPolicy("trino", "COLUMN_MASK",
                    header + "-- 列掩码（列 " + column + "）：\n" + maskExpression + "\n", metadata);
        }
        // DENY：Trino 侧表现为恒假谓词（拒绝所有行）
        if ("DENY".equals(spec.effect())) {
            metadata.put("rowFilter", "false");
            metadata.put("denyAll", true);
            return new CompiledPolicy("trino", "DENY",
                    header + "-- 拒绝访问（恒假谓词）：\nfalse\n", metadata);
        }
        throw new AccessPolicyException("Trino 目标不支持的效果：" + spec.effect()
                + "（GRANT 属数仓目标，BI 可见性属 bi 目标）");
    }

    /** 行过滤谓词：仅支持"列 运算符 字面量"这种可审计的最小形式。 */
    public static String rowPredicate(Map<String, Object> condition) {
        String column = identifier(str(condition.get("column")), "condition.column");
        String operator = str(condition.get("operator"));
        Object value = condition.get("value");
        if (operator == null || operator.isBlank()) {
            throw new AccessPolicyException("行过滤条件缺少 operator");
        }
        String op = operator.trim().toUpperCase(Locale.ROOT);
        return switch (op) {
            case "=", "!=", ">", ">=", "<", "<=" -> column + " " + op + " " + literal(value);
            case "IN" -> column + " IN (" + literalList(value) + ")";
            case "IS_NULL" -> column + " IS NULL";
            case "IS_NOT_NULL" -> column + " IS NOT NULL";
            case "CURRENT_USER" -> column + " = CURRENT_USER";
            default -> throw new AccessPolicyException("不支持的行过滤运算符：" + operator
                    + "（支持 =、!=、>、>=、<、<=、IN、IS_NULL、IS_NOT_NULL、CURRENT_USER）");
        };
    }

    /** 列掩码表达式：只提供几种**有明确语义**的掩码，避免"自定义表达式"变成代码执行入口。 */
    public static String maskExpression(Map<String, Object> condition) {
        String column = identifier(str(condition.get("column")), "condition.column");
        String mask = str(condition.get("mask"));
        if (mask == null) {
            throw new AccessPolicyException("列掩码条件缺少 mask");
        }
        return switch (mask.toLowerCase(Locale.ROOT)) {
            case "null" -> "CAST(NULL AS VARCHAR)";
            case "hash" -> "to_hex(sha256(to_utf8(CAST(" + column + " AS VARCHAR))))";
            case "constant" -> literal(condition.get("value"));
            case "partial" -> "concat(substr(CAST(" + column + " AS VARCHAR), 1, 2), '****')";
            case "year" -> "date_trunc('year', " + column + ")";
            default -> throw new AccessPolicyException("不支持的掩码方式：" + mask
                    + "（支持 null / hash / constant / partial / year）");
        };
    }

    // -------------------------------------------------------------- 数仓目标

    /**
     * 数仓目标：GRANT / REVOKE 语句 + 行访问策略骨架。
     *
     * <p><b>产物里必须带 REVOKE 与到期条件</b>：只发 GRANT 不发回收路径的策略，
     * 落地后就是"永久权限"，这正是治理要解决的问题（docs/09 §9.7 的到期回收）。
     */
    private static CompiledPolicy compileWarehouse(PolicySpec spec) {
        String role = roleName(spec);
        String table = tablePattern(spec.resourceScope());
        List<String> permissions = spec.permissions().isEmpty() ? List.of("SELECT") : spec.permissions();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("role", role);
        metadata.put("resourcePattern", table);
        metadata.put("permissions", permissions);
        metadata.put("resourceScope", spec.resourceScope());

        StringBuilder artifact = new StringBuilder();
        artifact.append("-- 策略：").append(spec.name()).append("（v").append(spec.version()).append("）\n");
        artifact.append("-- 目标：数仓原生权限（Snowflake RAP / BigQuery policy tags / Hive Ranger）\n");
        artifact.append("-- 资源：").append(describe(spec.resourceScope())).append('\n');
        artifact.append("-- 注意：本产物由治理平台生成，**到期回收由平台驱动**（见 access_grant.expires_at）\n\n");

        switch (spec.effect()) {
            case "GRANT" -> {
                artifact.append("GRANT ").append(String.join(", ", permissions))
                        .append(" ON TABLE ").append(table)
                        .append(" TO ROLE ").append(role).append(";\n");
                artifact.append("-- 回收路径（到期或复核未通过时执行）：\n");
                artifact.append("REVOKE ").append(String.join(", ", permissions))
                        .append(" ON TABLE ").append(table)
                        .append(" FROM ROLE ").append(role).append(";\n");
            }
            case "DENY" -> {
                artifact.append("REVOKE ").append(String.join(", ", permissions))
                        .append(" ON TABLE ").append(table)
                        .append(" FROM ROLE ").append(role).append(";\n");
            }
            case "ROW_FILTER" -> {
                String predicate = rowPredicate(spec.condition());
                metadata.put("rowFilter", predicate);
                artifact.append("-- 行访问策略（Row Access Policy）\n");
                artifact.append("CREATE OR REPLACE ROW ACCESS POLICY ").append(identifier(spec.name(), "name"))
                        .append(" AS (") .append(identifier(str(spec.condition().get("column")), "column"))
                        .append(" VARCHAR) RETURNS BOOLEAN ->\n    ").append(predicate.replace(
                                identifier(str(spec.condition().get("column")), "column"), "("
                                        + identifier(str(spec.condition().get("column")), "column") + ")"))
                        .append(";\n");
                artifact.append("ALTER TABLE ").append(table).append(" ADD ROW ACCESS POLICY ")
                        .append(identifier(spec.name(), "name")).append(";\n");
            }
            case "COLUMN_MASK" -> {
                String column = identifier(str(spec.condition().get("column")), "column");
                String expression = maskExpression(spec.condition());
                metadata.put("column", column);
                metadata.put("columnMask", expression);
                artifact.append("-- 列掩码（policy tag / masking policy）\n");
                artifact.append("CREATE OR REPLACE MASKING POLICY ").append(identifier(spec.name(), "name"))
                        .append(" AS (val VARCHAR) RETURNS VARCHAR ->\n    ")
                        .append(expression.replace(column, "val")).append(";\n");
                artifact.append("ALTER TABLE ").append(table).append(" MODIFY COLUMN ").append(column)
                        .append(" SET MASKING POLICY ").append(identifier(spec.name(), "name")).append(";\n");
            }
            default -> throw new AccessPolicyException("数仓目标不支持的效果：" + spec.effect());
        }
        return new CompiledPolicy("warehouse", spec.effect(), artifact.toString(), metadata);
    }

    // ------------------------------------------------------------------- BI

    /**
     * BI 目标：数据集可见性与字段隐藏。
     *
     * <p>产物里必须写清楚<b>这不是安全边界</b>：BI 可被绕过（用户可以直接连库），
     * 把它当边界是真实发生过的安全事故成因（docs/09 §9.7）。
     */
    private static CompiledPolicy compileBi(PolicySpec spec) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resourceScope", spec.resourceScope());
        metadata.put("subjectScope", spec.subjectScope());
        metadata.put("isSecurityBoundary", false);

        List<String> datasetPatterns = resourcePatterns(spec.resourceScope());
        String roles = String.join(", ", roles(spec.subjectScope()));
        String visibility = "COLUMN_MASK".equals(spec.effect()) || "DENY".equals(spec.effect())
                ? "HIDDEN" : "VISIBLE";

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("datasets", datasetPatterns);
        config.put("roles", roles);
        config.put("visibility", visibility);
        if ("COLUMN_MASK".equals(spec.effect())) {
            config.put("hiddenColumns", List.of(str(spec.condition().get("column"))));
        }
        metadata.put("config", config);

        String artifact = """
                # 策略：%s（v%d）
                # 目标：BI 层可见性（Superset / Tableau API）
                # ⚠️ BI 层只作消费端，**不是安全边界**：用户可绕过 BI 直连数仓。
                #    真正的边界在 Trino/数仓/网络层（见 coverage 的直连缺口说明）。
                datasets: %s
                roles: [%s]
                visibility: %s
                %s""".formatted(spec.name(), spec.version(), datasetPatterns, roles, visibility,
                "COLUMN_MASK".equals(spec.effect())
                        ? "hidden_columns: [" + str(spec.condition().get("column")) + "]\n" : "");
        return new CompiledPolicy("bi", spec.effect(), artifact, metadata);
    }

    // ------------------------------------------------------------------ SDK

    /** 应用层 SDK：令牌声明中的可见范围。同样只是辅助手段。 */
    private static CompiledPolicy compileSdk(PolicySpec spec) {
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("policy", spec.name());
        claim.put("version", spec.version());
        claim.put("resourcePrefixes", resourcePatterns(spec.resourceScope()));
        claim.put("permissions", spec.permissions().isEmpty() ? List.of("READ") : spec.permissions());
        if (spec.resourceScope().get("classification") != null) {
            claim.put("maxClassification", maxClassification(spec.resourceScope()));
        }
        if ("COLUMN_MASK".equals(spec.effect())) {
            claim.put("hiddenColumns", List.of(str(spec.condition().get("column"))));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("claim", claim);
        metadata.put("claimName", "dg_access");
        metadata.put("isSecurityBoundary", false);

        String artifact = """
                // 策略：%s（v%d）
                // 目标：应用层令牌声明（JWT claim `dg_access`）
                // ⚠️ 仅作辅助可见范围提示，**不可作为唯一防线**（客户端可伪造/忽略）。
                {
                  "dg_access": %s
                }
                """.formatted(spec.name(), spec.version(), toJson(claim));
        return new CompiledPolicy("sdk", spec.effect(), artifact, metadata);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 计算"这条策略是否覆盖某个数据集 URN"。编译与覆盖率度量共用同一判定。
     *
     * <p><b>没有资源选择器的策略不能声称覆盖任何数据集。</b>
     * 早期实现里，只有 {@code table} 的策略（数仓目标）会跳过所有前缀判定并返回 true ——
     * 结果覆盖率被算成 100%，而实际上一张表都没被覆盖。这正是 docs/09 §9.7 警告的
     * "宣称策略已下发而不度量覆盖率"的变体：数字好看，但它是假的。
     */
    public static boolean coversDataset(PolicyCompiler.PolicySpec spec, String datasetUrn,
                                        String classification, String domain) {
        Map<String, Object> scope = spec.resourceScope();
        boolean hasSelector = false;

        Object prefixes = scope.get("prefixes");
        if (prefixes instanceof List<?> list && !list.isEmpty()) {
            hasSelector = true;
            boolean matched = list.stream().anyMatch(prefix ->
                    datasetUrn != null && datasetUrn.startsWith(String.valueOf(prefix)));
            if (!matched) {
                return false;
            }
        } else if (scope.get("prefix") != null) {
            hasSelector = true;
            if (datasetUrn == null || !datasetUrn.startsWith(String.valueOf(scope.get("prefix")))) {
                return false;
            }
        }

        Object table = scope.get("table");
        if (table != null) {
            hasSelector = true;
            // 表级策略只能覆盖"同名表"：按 URN 后缀匹配（schema.table 或 table）
            String ref = String.valueOf(table);
            String suffix = "." + ref;
            String tableName = ref.contains(".") ? ref.substring(ref.lastIndexOf('.') + 1) : ref;
            boolean matched = datasetUrn != null
                    && (datasetUrn.endsWith(suffix) || datasetUrn.endsWith("." + tableName));
            if (!matched) {
                return false;
            }
        }

        Object levels = scope.get("classification");
        if (levels instanceof List<?> list && !list.isEmpty()) {
            hasSelector = true;
            String level = classification == null ? "L2" : classification.toUpperCase(Locale.ROOT);
            if (list.stream().noneMatch(item -> String.valueOf(item).equalsIgnoreCase(level))) {
                return false;
            }
        }

        Object domains = scope.get("domains");
        if (domains instanceof List<?> list && !list.isEmpty()) {
            hasSelector = true;
            if (domain == null || list.stream().noneMatch(item -> String.valueOf(item).equalsIgnoreCase(domain))) {
                return false;
            }
        }

        // 仅有 table 的策略**不参与目录覆盖率**：一张物理表可能在不同命名空间/平台下同名，
        // 只按表名匹配会把覆盖率算高（实测曾因此得到假的 100%）。
        // 要声明"覆盖这些数据集"，必须给出 prefix 或 classification/domains 选择器。
        boolean datasetSelectorPresent = (prefixes instanceof List<?> list && !list.isEmpty())
                || scope.get("prefix") != null
                || (levels instanceof List<?> list && !list.isEmpty())
                || (domains instanceof List<?> list && !list.isEmpty());
        return hasSelector && datasetSelectorPresent;
    }

    private static String roleName(PolicySpec spec) {
        List<String> roles = roles(spec.subjectScope());
        if (roles.isEmpty()) {
            throw new AccessPolicyException("GRANT/DENY 策略必须提供 subjectScope.roles");
        }
        return role(roles.get(0));
    }

    private static List<String> roles(Map<String, Object> subjectScope) {
        List<String> out = new ArrayList<>();
        for (String key : List.of("roles", "users", "teams")) {
            Object raw = subjectScope.get(key);
            if (raw instanceof List<?> list) {
                list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).forEach(out::add);
            }
        }
        return out;
    }

    private static String tablePattern(Map<String, Object> resourceScope) {
        Object table = resourceScope.get("table");
        if (table != null) {
            return tableRef(String.valueOf(table));
        }
        // URN 前缀无法直接变成表名：要求显式给出 table，避免"猜一个表名"这种危险动作
        throw new AccessPolicyException("数仓目标必须显式给出 resourceScope.table"
                + "（例如 public.orders）——不允许从 URN 前缀猜表名");
    }

    private static List<String> resourcePatterns(Map<String, Object> resourceScope) {
        Object prefixes = resourceScope.get("prefixes");
        if (prefixes instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        Object single = resourceScope.get("prefix");
        return single == null ? List.of() : List.of(String.valueOf(single));
    }

    private static String describe(Map<String, Object> scope) {
        return toJson(scope);
    }

    private static String tableRef(String table) {
        String[] parts = table.split("\\.");
        if (parts.length == 1) {
            return identifier(parts[0], "table");
        }
        if (parts.length == 2) {
            return identifier(parts[0], "schema") + "." + identifier(parts[1], "table");
        }
        throw new AccessPolicyException("表名最多支持 schema.table：" + table);
    }

    private static String maxClassification(Map<String, Object> resourceScope) {
        Object levels = resourceScope.get("classification");
        if (levels instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(list.size() - 1));
        }
        return "L2";
    }

    private static String identifier(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new AccessPolicyException("非法的 " + what + " 标识符：" + value
                    + "（只允许 [A-Za-z_][A-Za-z0-9_$]*）");
        }
        return value;
    }

    private static String role(String value) {
        if (value == null || !ROLE.matcher(value).matches()) {
            throw new AccessPolicyException("非法的角色名：" + value);
        }
        return value;
    }

    private static String literal(Object value) {
        if (value == null) {
            throw new AccessPolicyException("条件缺少 value");
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private static String literalList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new AccessPolicyException("IN 条件需要一个非空列表");
        }
        List<String> parts = new ArrayList<>();
        for (Object item : list) {
            parts.add(literal(item));
        }
        return String.join(", ", parts);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
