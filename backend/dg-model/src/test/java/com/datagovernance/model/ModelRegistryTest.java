package com.datagovernance.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 模型注册表测试：加载、校验、兼容性检查（与 Python 参考实现行为对齐）。 */
class ModelRegistryTest {

    /**
     * 仓库根下的 model 目录。
     *
     * <p>Surefire 以模块目录为工作目录（{@code backend/dg-model}），因此是 {@code ../../model}；
     * 同时支持用环境变量覆盖（CI 或从仓库根运行时使用）。
     */
    private static Path modelDir() {
        String configured = System.getenv("DG_MODEL_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path fromModule = Path.of("..", "..", "model");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        return Path.of("..", "model");
    }

    @Test
    void loadsCoreModelFromRepository() {
        ModelRegistry registry = ModelRegistry.load(modelDir());

        assertThat(registry.entityTypeNames()).contains("Dataset", "Column", "Dashboard", "Domain");
        assertThat(registry.aspectTypeNames()).contains("descriptions", "datasetSchema", "classification");
        assertThat(registry.relationshipTypeNames()).contains("contains", "derivesFrom");
        // 血缘边必须遵循 from=上游、to=下游。consumedBy（数据集 → 报表）是血缘边，
        // 而 readsFrom（报表 → 数据集）只是关联语义边：方向相反的"血缘边"会让影响分析给出相反结论。
        assertThat(registry.lineageRelationshipNames())
                .containsExactlyInAnyOrder("derivesFrom", "consumedBy");
        assertThat(registry.relationshipType("readsFrom").lineage()).isFalse();
        assertThat(registry.sourceFiles()).isNotEmpty();
    }

    @Test
    void datasetAllowsOnlyDeclaredAspects() {
        ModelRegistry registry = ModelRegistry.load(modelDir());

        assertThat(registry.isAspectAllowed("Dataset", "descriptions")).isTrue();
        assertThat(registry.isAspectAllowed("Dataset", "datasetSchema")).isTrue();
        assertThat(registry.isAspectAllowed("User", "datasetSchema")).isFalse();
    }

    @Test
    void rejectsUnknownEntityType() {
        ModelRegistry registry = ModelRegistry.load(modelDir());
        assertThatThrownBy(() -> registry.entityType("NoSuchType"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("未定义的实体类型");
    }

    @Test
    void validatesAspectData() {
        ModelRegistry registry = ModelRegistry.load(modelDir());

        assertThat(registry.validateAspectData("classification", Map.of("level", "L3"))).isEmpty();
        assertThat(registry.validateAspectData("classification", Map.of("level", "L9")))
                .anyMatch(error -> error.contains("enum"));
        assertThat(registry.validateAspectData("descriptions", Map.of("language", "zh")))
                .anyMatch(error -> error.contains("必填"));
    }

    @Test
    void rejectsUnnamespacedExtensionFields() {
        ModelRegistry registry = ModelRegistry.load(modelDir());

        // 未建模字段必须带命名空间前缀，否则各团队键名会互相冲突（docs/08 §8 反模式 6）
        assertThat(registry.validateAspectData("classification", Map.of("random", 1)))
                .anyMatch(error -> error.contains("命名空间前缀"));
        assertThat(registry.validateAspectData("classification", Map.of("x_team_note", "ok"))).isEmpty();
    }

    @Test
    void identicalModelsAreCompatible() {
        ModelRegistry registry = ModelRegistry.load(modelDir());
        assertThat(ModelRegistry.compatibilityErrors(registry, registry)).isEmpty();
    }

    @Test
    void detectBreakingChangeWhenPropertyRemoved(@TempDir Path tempDir) throws Exception {
        // 复制模型目录并删掉 descriptions.text 属性（属破坏性变更）
        Path source = modelDir().toAbsolutePath().normalize();
        Path copy = tempDir.resolve("model");
        copyDirectory(source, copy);

        Path aspects = copy.resolve("core").resolve("aspects.yaml");
        String content = Files.readString(aspects);
        Files.writeString(aspects, content.replace(
                "      - {name: text,     type: string, required: true}", ""));

        ModelRegistry oldModel = ModelRegistry.load(source);
        ModelRegistry newModel = ModelRegistry.load(copy);

        assertThat(ModelRegistry.compatibilityErrors(oldModel, newModel))
                .anyMatch(error -> error.contains("删除属性") && error.contains("text"));
    }

    @Test
    void rejectsDuplicateDefinitionAndBadApiVersion(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("model");
        Files.createDirectories(dir);

        Files.writeString(dir.resolve("bad-version.yaml"), """
                apiVersion: dg.model/v2
                kind: AspectTypes
                aspectTypes: []
                """);
        assertThatThrownBy(() -> ModelRegistry.load(dir))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("apiVersion");
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        try (var stream = Files.walk(source)) {
            List<Path> paths = stream.toList();
            for (Path path : paths) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }
}
