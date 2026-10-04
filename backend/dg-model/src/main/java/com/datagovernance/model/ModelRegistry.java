package com.datagovernance.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.yaml.snakeyaml.Yaml;

/**
 * 模型注册表：加载 model/**.yaml，提供校验、查询与**兼容性检查**（docs/08 §6）。
 *
 * <p>它承担两个职责：
 * <ol>
 *   <li>运行期：加载模型定义、校验 aspect 数据合法性；</li>
 *   <li>构建期（CI）：新旧模型对比，<b>阻断不兼容变更</b> —— 防止「新增扩展把老客户端打爆」。</li>
 * </ol>
 *
 * <p>纪律：模型定义是唯一事实源，Java / TypeScript / JSON Schema 的生成物都从它派生
 * （docs/06 §2.1 「Schema-First 单一事实源」，OpenMetadata 最值得借鉴的工程不变量）。
 */
public final class ModelRegistry {

    private static final Set<String> SUPPORTED_API_VERSIONS = Set.of("dg.model/v1");

    private final Map<String, EntityTypeDef> entityTypes;
    private final Map<String, AspectTypeDef> aspectTypes;
    private final Map<String, RelationshipTypeDef> relationshipTypes;
    private final List<String> sourceFiles;

    private ModelRegistry(
            Map<String, EntityTypeDef> entityTypes,
            Map<String, AspectTypeDef> aspectTypes,
            Map<String, RelationshipTypeDef> relationshipTypes,
            List<String> sourceFiles) {
        this.entityTypes = Map.copyOf(entityTypes);
        this.aspectTypes = Map.copyOf(aspectTypes);
        this.relationshipTypes = Map.copyOf(relationshipTypes);
        this.sourceFiles = List.copyOf(sourceFiles);
    }

    // ------------------------------------------------------------------ 加载

    public static ModelRegistry load(Path modelDir) {
        if (!Files.isDirectory(modelDir)) {
            throw ModelException.of("模型目录不存在：%s", modelDir);
        }

        Map<String, EntityTypeDef> entities = new LinkedHashMap<>();
        Map<String, AspectTypeDef> aspects = new LinkedHashMap<>();
        Map<String, RelationshipTypeDef> relationships = new LinkedHashMap<>();
        List<String> files = new ArrayList<>();

        List<Path> yamlFiles;
        try (Stream<Path> stream = Files.walk(modelDir)) {
            yamlFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")
                            || p.getFileName().toString().endsWith(".yml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Yaml yaml = new Yaml();
        for (Path file : yamlFiles) {
            Map<String, Object> doc;
            try {
                doc = yaml.load(Files.readString(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (doc == null || doc.isEmpty()) {
                continue;
            }
            mergeDocument(doc, modelDir.relativize(file).toString(), entities, aspects, relationships);
            files.add(modelDir.relativize(file).toString());
        }

        ModelRegistry registry = new ModelRegistry(entities, aspects, relationships, files);
        registry.validateConsistency();
        return registry;
    }

    @SuppressWarnings("unchecked")
    private static void mergeDocument(
            Map<String, Object> doc,
            String source,
            Map<String, EntityTypeDef> entities,
            Map<String, AspectTypeDef> aspects,
            Map<String, RelationshipTypeDef> relationships) {

        Object apiVersion = doc.get("apiVersion");
        if (apiVersion == null || !SUPPORTED_API_VERSIONS.contains(String.valueOf(apiVersion))) {
            throw ModelException.of("%s: apiVersion=%s 不受支持，支持 %s",
                    source, apiVersion, SUPPORTED_API_VERSIONS);
        }

        Object kind = doc.get("kind");
        switch (String.valueOf(kind)) {
            case "EntityTypes" -> {
                for (Object item : listOrEmpty(doc.get("entityTypes"))) {
                    EntityTypeDef def = EntityTypeDef.fromMap((Map<String, Object>) item);
                    register(entities, def.name(), def, source);
                }
                for (Object item : listOrEmpty(doc.get("relationshipTypes"))) {
                    RelationshipTypeDef def = RelationshipTypeDef.fromMap((Map<String, Object>) item);
                    register(relationships, def.name(), def, source);
                }
            }
            case "AspectTypes" -> {
                for (Object item : listOrEmpty(doc.get("aspectTypes"))) {
                    AspectTypeDef def = AspectTypeDef.fromMap((Map<String, Object>) item);
                    register(aspects, def.name(), def, source);
                }
            }
            default -> throw ModelException.of("%s: 未知 kind=%s", source, kind);
        }
    }

    private static List<?> listOrEmpty(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static <T> void register(Map<String, T> store, String name, T def, String source) {
        if (store.containsKey(name)) {
            throw ModelException.of("%s: 重复定义 %s（已存在于其它模型文件）", source, name);
        }
        store.put(name, def);
    }

    private void validateConsistency() {
        List<String> problems = new ArrayList<>();
        entityTypes.values().forEach(ent -> {
            ent.aspects().forEach(asp -> {
                if (!aspectTypes.containsKey(asp)) {
                    problems.add("实体 %s 引用了未定义的 aspect %s".formatted(ent.name(), asp));
                }
            });
            ent.parentTypes().forEach(parent -> {
                if (!entityTypes.containsKey(parent)) {
                    problems.add("实体 %s 的 parentType %s 未定义".formatted(ent.name(), parent));
                }
            });
        });
        relationshipTypes.values().forEach(rel -> {
            rel.fromTypes().forEach(t -> {
                if (!entityTypes.containsKey(t)) {
                    problems.add("关系 %s 的 from 端 %s 未定义".formatted(rel.name(), t));
                }
            });
            rel.toTypes().forEach(t -> {
                if (!entityTypes.containsKey(t)) {
                    problems.add("关系 %s 的 to 端 %s 未定义".formatted(rel.name(), t));
                }
            });
        });
        if (!problems.isEmpty()) {
            throw new ModelException("模型定义不一致：\n  - " + String.join("\n  - ", problems));
        }
    }

    // ------------------------------------------------------------------ 查询

    public EntityTypeDef entityType(String name) {
        EntityTypeDef def = entityTypes.get(name);
        if (def == null) {
            throw ModelException.of("未定义的实体类型：%s", name);
        }
        return def;
    }

    public AspectTypeDef aspectType(String name) {
        AspectTypeDef def = aspectTypes.get(name);
        if (def == null) {
            throw ModelException.of("未定义的 aspect 类型：%s", name);
        }
        return def;
    }

    public RelationshipTypeDef relationshipType(String name) {
        RelationshipTypeDef def = relationshipTypes.get(name);
        if (def == null) {
            throw ModelException.of("未定义的关系类型：%s", name);
        }
        return def;
    }

    public boolean isAspectAllowed(String entityType, String aspectType) {
        return entityType(entityType).aspects().contains(aspectType);
    }

    public Set<String> entityTypeNames() {
        return new TreeSet<>(entityTypes.keySet());
    }

    public Set<String> aspectTypeNames() {
        return new TreeSet<>(aspectTypes.keySet());
    }

    public Set<String> relationshipTypeNames() {
        return new TreeSet<>(relationshipTypes.keySet());
    }

    public Set<String> lineageRelationshipNames() {
        return relationshipTypes.values().stream()
                .filter(RelationshipTypeDef::lineage)
                .map(RelationshipTypeDef::name)
                .collect(TreeSet::new, Set::add, Set::addAll);
    }

    public List<String> sourceFiles() {
        return sourceFiles;
    }

    public Map<String, Object> summary() {
        return Map.of(
                "entityTypes", entityTypes.size(),
                "aspectTypes", aspectTypes.size(),
                "relationshipTypes", relationshipTypes.size(),
                "sourceFiles", sourceFiles);
    }

    // ------------------------------------------------------ 校验 aspect 数据

    /**
     * 按定义校验 aspect 数据。返回错误列表（空 = 合法）。
     *
     * <p>未建模字段必须带命名空间前缀（{@code x_} / {@code team:key}），
     * 否则各团队的键名会互相冲突（docs/08 §8 反模式 6）。
     */
    public List<String> validateAspectData(String aspectType, Map<String, Object> data) {
        AspectTypeDef spec = aspectType(aspectType);
        List<String> errors = new ArrayList<>();

        for (String key : data.keySet()) {
            boolean known = spec.properties().stream().anyMatch(p -> p.name().equals(key));
            boolean namespaced = key.startsWith("x_") || key.contains(":") || key.contains(".");
            if (!known && !namespaced) {
                errors.add("未知属性 %s：若确需扩展请使用命名空间前缀（如 x_team 或 team:key）".formatted(key));
            }
        }

        for (PropertyDef prop : spec.properties()) {
            Object value = data.get(prop.name());
            if (value == null) {
                if (prop.required() && prop.defaultValue() == null) {
                    errors.add("缺少必填属性 %s".formatted(prop.name()));
                }
                continue;
            }
            errors.addAll(checkType(aspectType, prop, value));
        }
        return errors;
    }

    private static List<String> checkType(String aspectType, PropertyDef prop, Object value) {
        String where = "%s.%s".formatted(aspectType, prop.name());
        return switch (prop.type()) {
            case "string" -> value instanceof String
                    ? List.of() : List.of("%s 期望 string，收到 %s".formatted(where, value));
            case "integer" -> value instanceof Integer || value instanceof Long
                    ? List.of() : List.of("%s 期望 integer，收到 %s".formatted(where, value));
            case "number" -> value instanceof Number
                    ? List.of() : List.of("%s 期望 number，收到 %s".formatted(where, value));
            case "boolean" -> value instanceof Boolean
                    ? List.of() : List.of("%s 期望 boolean，收到 %s".formatted(where, value));
            case "object" -> value instanceof Map
                    ? List.of() : List.of("%s 期望 object，收到 %s".formatted(where, value));
            case "array" -> {
                if (!(value instanceof List<?> list)) {
                    yield List.of("%s 期望 array，收到 %s".formatted(where, value));
                }
                if ("string".equals(prop.itemsType())
                        && list.stream().anyMatch(item -> !(item instanceof String))) {
                    yield List.of("%s 的数组元素期望 string".formatted(where));
                }
                yield List.of();
            }
            case "enum" -> prop.enumValues().contains(String.valueOf(value))
                    ? List.of()
                    : List.of("%s 期望 enum %s，收到 %s".formatted(where, prop.enumValues(), value));
            default -> List.of();
        };
    }

    // -------------------------------------------------- 兼容性校验（CI 强制）

    /**
     * 返回**不兼容变更**清单。CI 中出现非空即应阻断合并。
     *
     * <p>禁止：删除实体/aspect/属性；改属性类型；可选改必填；移除枚举值；时序退化；关系 category 变更。
     */
    public static List<String> compatibilityErrors(ModelRegistry oldModel, ModelRegistry newModel) {
        List<String> errors = new ArrayList<>();

        for (String name : oldModel.entityTypes.keySet()) {
            EntityTypeDef newDef = newModel.entityTypes.get(name);
            if (newDef == null) {
                errors.add("[BREAKING] 删除实体类型 " + name);
                continue;
            }
            EntityTypeDef oldDef = oldModel.entityTypes.get(name);
            List<String> removed = oldDef.aspects().stream()
                    .filter(a -> !newDef.aspects().contains(a))
                    .toList();
            if (!removed.isEmpty()) {
                errors.add("[BREAKING] 实体 %s 移除 aspect: %s".formatted(name, removed));
            }
        }

        for (Map.Entry<String, AspectTypeDef> entry : oldModel.aspectTypes.entrySet()) {
            String name = entry.getKey();
            AspectTypeDef oldDef = entry.getValue();
            AspectTypeDef newDef = newModel.aspectTypes.get(name);
            if (newDef == null) {
                errors.add("[BREAKING] 删除 aspect 类型 " + name);
                continue;
            }
            if (oldDef.sourceTracked() != newDef.sourceTracked()) {
                errors.add("[BREAKING] aspect %s 的 sourceTracked 变更（影响「采集不覆盖人工内容」语义）".formatted(name));
            }
            if (oldDef.timeSeries() && !newDef.timeSeries()) {
                errors.add("[BREAKING] aspect %s 由时序退化为非时序".formatted(name));
            }
            errors.addAll(compareProperties(name, oldDef, newDef));
        }

        for (Map.Entry<String, RelationshipTypeDef> entry : oldModel.relationshipTypes.entrySet()) {
            String name = entry.getKey();
            RelationshipTypeDef oldDef = entry.getValue();
            RelationshipTypeDef newDef = newModel.relationshipTypes.get(name);
            if (newDef == null) {
                errors.add("[BREAKING] 删除关系类型 " + name);
                continue;
            }
            if (!oldDef.category().equals(newDef.category())) {
                errors.add("[BREAKING] 关系 %s 的 category 由 %s 变为 %s（改变删除级联语义）"
                        .formatted(name, oldDef.category(), newDef.category()));
            }
        }
        return errors;
    }

    private static List<String> compareProperties(String aspectName, AspectTypeDef oldDef, AspectTypeDef newDef) {
        List<String> errors = new ArrayList<>();
        for (PropertyDef op : oldDef.properties()) {
            Optional<PropertyDef> maybeNew = newDef.property(op.name());
            if (maybeNew.isEmpty()) {
                errors.add("[BREAKING] aspect %s 删除属性 %s".formatted(aspectName, op.name()));
                continue;
            }
            PropertyDef np = maybeNew.get();
            if (!op.type().equals(np.type())) {
                errors.add("[BREAKING] aspect %s.%s 类型由 %s 变为 %s"
                        .formatted(aspectName, op.name(), op.type(), np.type()));
            }
            if (!op.required() && np.required()) {
                errors.add("[BREAKING] aspect %s.%s 由可选改为必填".formatted(aspectName, op.name()));
            }
            List<String> dropped = op.enumValues().stream()
                    .filter(v -> !np.enumValues().contains(v))
                    .toList();
            if (!dropped.isEmpty()) {
                errors.add("[BREAKING] aspect %s.%s 移除枚举值 %s"
                        .formatted(aspectName, op.name(), dropped));
            }
        }
        return errors;
    }
}
