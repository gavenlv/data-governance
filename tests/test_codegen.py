"""代码生成测试。

固化的不变量（docs/08 §6、docs/06 §2.1）：
  **模型 YAML 是单一事实源** —— 生成物必须始终与它一致；
  一旦有人改了 YAML 却忘了重新生成，CI 必须失败。
"""

from __future__ import annotations

import json

import pytest

from dg.codegen import GENERATORS, check_generated, generate_all
from dg.config import REPO_ROOT


class TestGeneratedArtifactsAreCurrent:
    def test_repository_generated_files_match_model(self, registry):
        """仓库里的生成物必须是最新的（等价于 CI 的 codegen --check）。"""
        stale = check_generated(registry, REPO_ROOT)
        assert stale == [], "生成物与模型定义不一致：\n" + "\n".join(stale)

    def test_all_targets_are_declared(self):
        assert set(GENERATORS) == {"python", "typescript", "jsonschema"}


class TestPythonOutput:
    def test_module_importable_and_faithful(self, registry):
        from dg.generated import model_gen as m

        assert set(m.ENTITY_TYPES) == set(registry.entity_types)
        assert set(m.ASPECT_TYPES) == set(registry.aspect_types)
        assert m.ENTITY_ASPECTS["Dataset"] == registry.entity_type("Dataset").aspects
        assert m.LINEAGE_RELATIONSHIPS == tuple(sorted(registry.lineage_relationship_names()))
        assert m.RELATIONSHIP_CATEGORIES["contains"] == "COMPOSITION"

    def test_dataclasses_are_usable(self):
        from dg.generated import model_gen as m

        desc = m.Descriptions(text="订单明细表")
        assert desc.text == "订单明细表"
        assert desc.language == "zh"          # 默认值来自 YAML
        assert desc.source == "MANUAL"

        cls = m.Classification(level="L3")
        assert cls.level == "L3"

        schema = m.DatasetSchema(fields=[])
        assert schema.fields == []
        assert schema.primary_key == []       # snake_case 化 + 默认工厂

    def test_source_tracked_flag_exported(self):
        from dg.generated import model_gen as m

        # 参与"采集不覆盖人工内容"判定的 aspect
        assert m.ASPECT_SOURCE_TRACKED["descriptions"] is True
        assert m.ASPECT_SOURCE_TRACKED["datasetSchema"] is False


class TestTypeScriptOutput:
    def test_interfaces_and_unions_present(self, registry):
        text = (REPO_ROOT / "web" / "generated" / "model_gen.ts").read_text(encoding="utf-8")
        assert "export type EntityType" in text
        assert "export interface DatasetSchema" in text
        assert "export const LINEAGE_RELATIONSHIPS" in text
        # 枚举被生成为字面量联合类型
        assert "'L1' | 'L2' | 'L3' | 'L4'" in text
        for name in ("Dataset", "Column", "Dashboard"):
            assert f"'{name}'" in text


class TestJsonSchemaOutput:
    def test_schema_is_valid_json_with_defs(self, registry):
        payload = json.loads(
            (REPO_ROOT / "schema" / "generated" / "model_gen.schema.json").read_text(encoding="utf-8")
        )
        assert payload["$schema"].startswith("https://json-schema.org/")
        assert "aspect.classification" in payload["$defs"]
        classification = payload["$defs"]["aspect.classification"]
        assert classification["properties"]["level"]["enum"] == ["L1", "L2", "L3", "L4"]
        assert classification["additionalProperties"] is False


class TestDriftDetection:
    def test_detects_missing_and_tampered_files(self, registry, tmp_path):
        # 空目录 → 全部缺失
        assert len(check_generated(registry, tmp_path)) == len(GENERATORS)

        generate_all(registry, tmp_path)
        assert check_generated(registry, tmp_path) == []

        # 篡改 Python 生成物 → 必须被检出
        py = tmp_path / "src" / "dg" / "generated" / "model_gen.py"
        py.write_text(py.read_text(encoding="utf-8") + "\n# tampered\n", encoding="utf-8")
        stale = check_generated(registry, tmp_path)
        assert any("python" in s for s in stale)

    def test_timestamp_line_does_not_cause_false_positive(self, registry, tmp_path):
        generate_all(registry, tmp_path)
        # 连续两次生成（时间戳不同）不应判定为不一致
        generate_all(registry, tmp_path)
        assert check_generated(registry, tmp_path) == []
