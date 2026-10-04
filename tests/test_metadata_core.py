"""核心元数据服务测试：模型校验、URN、Aspect 生命周期、来源保护、乐观锁、批次回滚。

这些测试直接对应设计文档中的架构约束，是"设计是否真的落地"的证据：
  - ADR-001 真相源（PostgreSQL）
  - ADR-002 事件与派生物
  - ADR-005 采集不覆盖人工内容 / 变更集回滚
"""

from __future__ import annotations

import pytest
from sqlalchemy import text

from dg.core import urn as urn_mod
from dg.core.service import Conflict, MetadataService, NotFound, ValidationFailed
from dg.model import ModelError, ModelRegistry


# ---------------------------------------------------------------------------
# 1. Model Registry
# ---------------------------------------------------------------------------
class TestModelRegistry:
    def test_load_core_model(self, registry):
        summary = registry.summary()
        assert summary["entity_types"] >= 10
        assert summary["aspect_types"] >= 8
        assert summary["relationship_types"] >= 8
        assert "Dataset" in registry.entity_types
        assert registry.entity_type("Dataset").aspects  # 至少有一个 aspect

    def test_dataset_allows_declared_aspects_only(self, registry):
        assert registry.is_aspect_allowed("Dataset", "descriptions")
        assert registry.is_aspect_allowed("Dataset", "datasetSchema")
        assert not registry.is_aspect_allowed("User", "datasetSchema")

    def test_validate_aspect_rejects_bad_enum(self, registry):
        errors = registry.validate_aspect_data("classification", {"level": "L9"})
        assert errors and "enum" in errors[0]

    def test_validate_aspect_rejects_unmodeled_field(self, registry):
        errors = registry.validate_aspect_data("classification", {"randomField": 1})
        assert any("命名空间前缀" in e for e in errors)

    def test_validate_aspect_allows_namespaced_extension(self, registry):
        assert registry.validate_aspect_data("classification", {"x_team_note": "ok"}) == []

    def test_validate_aspect_requires_required_property(self, registry):
        errors = registry.validate_aspect_data("descriptions", {"language": "zh"})
        assert any("必填" in e for e in errors)

    def test_undeclared_entity_type_raises(self, registry):
        with pytest.raises(ModelError):
            registry.entity_type("NoSuchType")


class TestModelCompatibility:
    """模型兼容性校验：CI 必须阻断不兼容变更（08 §6）。"""

    def test_identical_models_are_compatible(self, registry):
        assert ModelRegistry.compatibility_errors(registry, registry) == []

    def test_dropping_aspect_is_breaking(self, registry, tmp_path):
        # 构造一个"删掉了 descriptions 属性"的模型副本
        new = ModelRegistry(
            entity_types=dict(registry.entity_types),
            aspect_types=dict(registry.aspect_types),
            relationship_types=dict(registry.relationship_types),
        )
        errors = ModelRegistry.compatibility_errors(registry, new)
        assert errors == []  # 完全相同的副本不应报错

        from dg.model.registry import AspectTypeDef, PropertyDef

        broken = dict(registry.aspect_types)
        broken["classification"] = AspectTypeDef(
            name="classification",
            display_name="分类分级",
            properties=(PropertyDef(name="level", type="enum", enum_values=("L1", "L2", "L3", "L4")),),
            source_tracked=True,
        )
        new2 = ModelRegistry(
            entity_types=dict(registry.entity_types),
            aspect_types=broken,
            relationship_types=dict(registry.relationship_types),
        )
        errors2 = ModelRegistry.compatibility_errors(registry, new2)
        assert any("删除属性" in e for e in errors2), errors2


# ---------------------------------------------------------------------------
# 2. URN
# ---------------------------------------------------------------------------
class TestUrn:
    def test_build_and_parse(self):
        u = urn_mod.build_urn("Dataset", "prod", "mysql", "sales", "public", "orders")
        assert u == "urn:dg:Dataset:prod.mysql.sales.public.orders"
        parsed = urn_mod.parse_urn(u)
        assert parsed.entity_type == "Dataset"
        assert parsed.parts == ("prod", "mysql", "sales", "public", "orders")

    def test_column_urn_is_subresource(self):
        ds = urn_mod.dataset_urn("prod", "mysql", "sales", "public", "orders")
        col = urn_mod.column_urn(ds, "customer_id")
        assert col == "urn:dg:Column:prod.mysql.sales.public.orders.customer_id"

    def test_reject_invalid_urn(self):
        with pytest.raises(urn_mod.UrnError):
            urn_mod.parse_urn("not-a-urn")
        with pytest.raises(urn_mod.UrnError):
            urn_mod.build_urn("Dataset", "has space")


# ---------------------------------------------------------------------------
# 3. Aspect 生命周期
# ---------------------------------------------------------------------------
DATASET = "urn:dg:Dataset:prod.mysql.sales.public.orders"


class TestAspectLifecycle:
    def test_create_entity_then_aspect(self, svc, session):
        entity, created = svc.ensure_entity(DATASET, "Dataset", display_name="orders")
        assert created is True
        assert entity["entity_type"] == "Dataset"

        result = svc.upsert_aspect(
            DATASET, "descriptions", {"text": "订单明细表", "language": "zh"}, source="MANUAL"
        )
        assert result.version == 1
        assert result.created is True
        assert "text" in result.changed_fields
        assert result.event_seq > 0

        assert svc.get_aspect(DATASET, "descriptions")["text"] == "订单明细表"

    def test_ensure_entity_is_idempotent(self, svc):
        _, first = svc.ensure_entity(DATASET, "Dataset")
        _, second = svc.ensure_entity(DATASET, "Dataset")
        assert first is True and second is False

    def test_entity_type_must_match_urn(self, svc):
        with pytest.raises(ValidationFailed):
            svc.ensure_entity(DATASET, "Dashboard")

    def test_undeclared_entity_type_rejected(self, svc):
        with pytest.raises(ModelError):
            svc.ensure_entity("urn:dg:Whatever:prod.a", "Whatever")

    def test_aspect_not_allowed_on_type(self, svc):
        svc.ensure_entity("urn:dg:User:alice", "User")
        with pytest.raises(ValidationFailed):
            svc.upsert_aspect("urn:dg:User:alice", "datasetSchema", {"fields": []})

    def test_version_increments_and_history_recorded(self, svc):
        svc.ensure_entity(DATASET, "Dataset")
        svc.upsert_aspect(DATASET, "descriptions", {"text": "v1"}, source="MANUAL")
        r2 = svc.upsert_aspect(DATASET, "descriptions", {"text": "v2"}, source="MANUAL")
        assert r2.version == 2

        history = svc.aspect_history(DATASET, "descriptions")
        assert len(history) == 1
        assert history[0]["data"]["text"] == "v1"  # 历史保存旧值，供时间旅行与回滚

    def test_noop_write_does_not_bump_version(self, svc):
        svc.ensure_entity(DATASET, "Dataset")
        svc.upsert_aspect(DATASET, "tags", {"tags": ["core"]}, source="AUTO_COLLECTED")
        again = svc.upsert_aspect(DATASET, "tags", {"tags": ["core"]}, source="AUTO_COLLECTED")
        assert again.noop
        assert again.version == 1
        assert again.event_seq == -1  # 未产生新事件

    def test_optimistic_lock_conflict(self, svc):
        svc.ensure_entity(DATASET, "Dataset")
        svc.upsert_aspect(DATASET, "descriptions", {"text": "a"}, source="MANUAL")
        with pytest.raises(Conflict):
            svc.upsert_aspect(
                DATASET, "descriptions", {"text": "b"}, source="MANUAL", expected_version=99
            )

    def test_write_emits_event_in_same_transaction(self, svc, session):
        svc.ensure_entity(DATASET, "Dataset")
        svc.upsert_aspect(DATASET, "descriptions", {"text": "x"}, source="MANUAL")
        session.commit()
        count = session.execute(
            text("SELECT count(*) FROM event_log WHERE urn = :u"), {"u": DATASET}
        ).scalar()
        assert count >= 2  # ENTITY_CREATED + ASPECT_UPSERTED

    def test_audit_log_written_with_hash_chain(self, svc, session):
        svc.ensure_entity(DATASET, "Dataset")
        svc.upsert_aspect(DATASET, "descriptions", {"text": "x"}, source="MANUAL")
        session.commit()
        rows = session.execute(
            text("SELECT action, hash FROM audit_log ORDER BY seq")
        ).mappings().all()
        assert rows
        assert all(r["hash"] for r in rows)


# ---------------------------------------------------------------------------
# 4. ADR-005：采集绝不覆盖人工内容
# ---------------------------------------------------------------------------
class TestSourceProtection:
    def test_manual_content_survives_collection(self, svc):
        svc.ensure_entity(DATASET, "Dataset")
        svc.upsert_aspect(DATASET, "descriptions", {"text": "人工写的描述"}, source="MANUAL")

        result = svc.upsert_aspect(
            DATASET, "descriptions", {"text": "采集器抓到的描述"}, source="AUTO_COLLECTED"
        )
        # 人工内容被保护
        assert svc.get_aspect(DATASET, "descriptions")["text"] == "人工写的描述"
        assert "text" in result.protected_fields
        assert result.protected_fields["text"]["keptSource"] == "MANUAL"

    def test_collection_can_update_its_own_fields(self, svc):
        svc.ensure_entity(DATASET, "Dataset")
        svc.upsert_aspect(DATASET, "tags", {"tags": ["a"]}, source="AUTO_COLLECTED")
        result = svc.upsert_aspect(DATASET, "tags", {"tags": ["a", "b"]}, source="AUTO_COLLECTED")
        assert not result.protected_fields
        assert svc.get_aspect(DATASET, "tags")["tags"] == ["a", "b"]

    def test_manual_can_override_collected(self, svc):
        svc.ensure_entity(DATASET, "Dataset")
        svc.upsert_aspect(DATASET, "descriptions", {"text": "机器"}, source="AUTO_COLLECTED")
        svc.upsert_aspect(DATASET, "descriptions", {"text": "人工订正"}, source="MANUAL")
        assert svc.get_aspect(DATASET, "descriptions")["text"] == "人工订正"

    def test_field_level_granularity(self, svc):
        """同一 aspect 内，人工字段被保护，而采集字段仍可更新。"""
        svc.ensure_entity(DATASET, "Dataset")
        svc.upsert_aspect(
            DATASET, "classification", {"level": "L3"}, source="MANUAL"
        )
        result = svc.upsert_aspect(
            DATASET,
            "classification",
            {"level": "L1", "categories": ["PERSONAL_INFO"]},
            source="AUTO_COLLECTED",
        )
        current = svc.get_aspect(DATASET, "classification")
        assert current["level"] == "L3"                      # 人工设定被保护
        assert current["categories"] == ["PERSONAL_INFO"]     # 采集字段正常写入
        assert "level" in result.protected_fields


# ---------------------------------------------------------------------------
# 5. 采集批次回滚（ADR-005）
# ---------------------------------------------------------------------------
class TestRunRollback:
    def test_rollback_drops_aspects_created_by_the_run(self, svc, session):
        """该批次"首次创建"的 aspect 必须被删除，而不是被保留。"""
        svc.ensure_entity(DATASET, "Dataset")
        svc.upsert_aspect(DATASET, "descriptions", {"text": "人工描述"}, source="MANUAL")
        session.commit()

        run = "run-2026-10-01-001"
        bad = "urn:dg:Dataset:prod.mysql.sales.public.tmp_bad"
        svc.ensure_entity(bad, "Dataset", run_id=run)
        svc.upsert_aspect(bad, "tags", {"tags": ["junk"]}, source="AUTO_COLLECTED", run_id=run)
        svc.upsert_aspect(DATASET, "tags", {"tags": ["collected"]}, source="AUTO_COLLECTED", run_id=run)
        session.commit()

        outcome = svc.rollback_run(run)
        session.commit()

        # 误建的实体进墓碑，且其 aspect 被清除
        assert bad in outcome["tombstonedEntities"]
        assert f"{bad}#tags" in outcome["droppedAspects"]
        with pytest.raises(NotFound):
            svc.get_entity(bad)

        # 该批次在既有实体上新建的 tags 也被删除（此前遗漏的分支）
        assert f"{DATASET}#tags" in outcome["droppedAspects"]
        assert svc.get_aspect(DATASET, "tags") is None
        # 人工维护的 descriptions 不受影响
        assert svc.get_aspect(DATASET, "descriptions")["text"] == "人工描述"

    def test_rollback_restores_updated_aspect_to_previous_version(self, svc, session):
        """该批次"更新"的 aspect 必须回滚到前一版本。"""
        svc.ensure_entity(DATASET, "Dataset")
        svc.upsert_aspect(DATASET, "tags", {"tags": ["original"]}, source="AUTO_COLLECTED")
        session.commit()

        run = "run-2026-10-01-002"
        svc.upsert_aspect(DATASET, "tags", {"tags": ["clobbered"]}, source="AUTO_COLLECTED", run_id=run)
        session.commit()
        assert svc.get_aspect(DATASET, "tags")["tags"] == ["clobbered"]

        outcome = svc.rollback_run(run)
        session.commit()

        assert f"{DATASET}#tags" in outcome["restoredAspects"]
        assert svc.get_aspect(DATASET, "tags")["tags"] == ["original"]
