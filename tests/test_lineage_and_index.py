"""血缘遍历与派生视图（搜索索引）重建测试。

对应架构主张：
  - ADR-002 派生视图可丢弃、可从事件流重放重建
  - docs/09 §9.2 血缘遍历实现约束（UNION 去环、深度有界、排除 CONTROL 依赖、置信度过滤）
"""

from __future__ import annotations

import pytest
from sqlalchemy import text

from dg.consumers.search_index import (
    CONSUMER_NAME,
    consume_search_index,
    index_lag,
    rebuild_search_index,
    truncate_search_index,
)

ODS = "urn:dg:Dataset:prod.mysql.sales.public.ods_orders"
STG = "urn:dg:Dataset:prod.hive.dw.stg_orders"
DWD = "urn:dg:Dataset:prod.hive.dw.dwd_orders"
RPT = "urn:dg:Dashboard:prod.superset.gmv_board"


# ---------------------------------------------------------------------------
# 血缘
# ---------------------------------------------------------------------------
class TestLineage:
    def _chain(self, svc):
        for urn, name in ((ODS, "ods_orders"), (STG, "stg_orders"), (DWD, "dwd_orders")):
            svc.ensure_entity(urn, "Dataset", display_name=name)
        svc.upsert_edge(ODS, STG, "derivesFrom", source="sql_parse", confidence=0.8)
        svc.upsert_edge(STG, DWD, "derivesFrom", source="sql_parse", confidence=0.8)
        svc.upsert_edge(DWD, RPT, "derivesFrom", source="openlineage", confidence=0.95)

    def test_downstream_walks_forward(self, svc):
        self._chain(svc)
        result = svc.lineage(ODS, "downstream", max_depth=3)
        found = {n["urn"]: n["depth"] for n in result["nodes"]}
        assert found[STG] == 1
        assert found[DWD] == 2
        assert found[RPT] == 3

    def test_upstream_walks_backward(self, svc):
        self._chain(svc)
        result = svc.lineage(RPT, "upstream", max_depth=3)
        found = {n["urn"]: n["depth"] for n in result["nodes"]}
        assert found[DWD] == 1
        assert found[STG] == 2
        assert found[ODS] == 3

    def test_depth_is_bounded(self, svc):
        self._chain(svc)
        result = svc.lineage(ODS, "downstream", max_depth=1)
        found = {n["urn"] for n in result["nodes"]}
        assert found == {STG}

    def test_cycle_does_not_hang(self, svc):
        """真实血缘图有环；实现必须用 UNION 去重而非 UNION ALL。"""
        self._chain(svc)
        svc.upsert_edge(DWD, ODS, "derivesFrom", source="manual", confidence=1.0)
        result = svc.lineage(ODS, "downstream", max_depth=6)
        urns = [n["urn"] for n in result["nodes"]]
        assert len(urns) == len(set(urns))          # 无重复节点
        assert DWD in urns

    def test_confidence_filter(self, svc):
        self._chain(svc)
        svc.upsert_edge(DWD, "urn:dg:Dataset:prod.hive.dw.low_conf", "derivesFrom",
                        source="inferred_ai", confidence=0.35)
        filtered = svc.lineage(DWD, "downstream", max_depth=2, min_confidence=0.7)
        assert "urn:dg:Dataset:prod.hive.dw.low_conf" not in {n["urn"] for n in filtered["nodes"]}

    def test_control_dependency_excluded_from_value_lineage(self, svc):
        """窗口函数的 PARTITION BY/ORDER BY 属控制依赖，不参与值级血缘（09 §9.2）。"""
        self._chain(svc)
        ctrl_target = "urn:dg:Dataset:prod.hive.dw.ctrl_only"
        svc.upsert_edge(DWD, ctrl_target, "derivesFrom", source="sql_parse",
                        confidence=0.9, dependency_kind="CONTROL")
        result = svc.lineage(DWD, "downstream", max_depth=2)
        assert ctrl_target not in {n["urn"] for n in result["nodes"]}

    def test_confidence_is_monotonic_on_reobservation(self, svc):
        svc.ensure_entity(ODS, "Dataset")
        svc.ensure_entity(STG, "Dataset")
        svc.upsert_edge(ODS, STG, "derivesFrom", source="sql_parse", confidence=0.6)
        svc.upsert_edge(ODS, STG, "derivesFrom", source="sql_parse", confidence=0.9)
        row = svc.s.execute(
            text(
                "SELECT confidence, observed_count FROM edge "
                "WHERE from_urn=:f AND to_urn=:t AND source='sql_parse'"
            ),
            {"f": ODS, "t": STG},
        ).mappings().one()
        assert float(row["confidence"]) == pytest.approx(0.9)
        assert row["observed_count"] == 2

    def test_unknown_relationship_rejected(self, svc):
        from dg.model import ModelError

        with pytest.raises(ModelError):
            svc.upsert_edge(ODS, STG, "notARelationship", source="manual")


# ---------------------------------------------------------------------------
# 派生视图：搜索索引
# ---------------------------------------------------------------------------
class TestSearchIndexDerivation:
    def _seed(self, svc):
        svc.ensure_entity(ODS, "Dataset", display_name="ods_orders")
        svc.upsert_aspect(ODS, "descriptions", {"text": "订单原始表"}, source="MANUAL")
        svc.upsert_aspect(ODS, "ownership", {"owners": [{"type": "TEAM", "urn": "urn:dg:Team:data"}]},
                          source="MANUAL")
        svc.upsert_aspect(ODS, "classification", {"level": "L2"}, source="MANUAL")

        svc.ensure_entity(STG, "Dataset", display_name="stg_orders")
        svc.upsert_aspect(STG, "descriptions", {"text": "订单清洗表"}, source="MANUAL")
        svc.ensure_entity("urn:dg:User:alice", "User", display_name="alice")  # 不入索引

    def test_consumer_builds_index_from_events(self, svc, session):
        self._seed(svc)
        session.commit()

        stats = consume_search_index(session)
        assert stats.indexed >= 2

        rows = session.execute(
            text("SELECT urn, description FROM search_doc ORDER BY urn")
        ).mappings().all()
        urns = {r["urn"] for r in rows}
        assert ODS in urns and STG in urns
        # 组织类实体不进搜索索引
        assert "urn:dg:User:alice" not in urns

        desc = {r["urn"]: r["description"] for r in rows}
        assert desc[ODS] == "订单原始表"

    def test_index_is_droppable_and_rebuildable(self, svc, session):
        """ADR-002 的核心主张：派生视图可以整体丢弃并从事件流重建。"""
        self._seed(svc)
        session.commit()
        consume_search_index(session)

        before = session.execute(
            text("SELECT urn, description, owners, classification FROM search_doc ORDER BY urn")
        ).mappings().all()
        assert len(before) >= 2

        stats = rebuild_search_index(session)
        after = session.execute(
            text("SELECT urn, description, owners, classification FROM search_doc ORDER BY urn")
        ).mappings().all()

        assert stats.indexed >= 2
        assert [dict(r) for r in before] == [dict(r) for r in after]

    def test_consumer_is_idempotent(self, svc, session):
        self._seed(svc)
        session.commit()
        consume_search_index(session)
        first = session.execute(text("SELECT count(*) FROM search_doc")).scalar()
        consume_search_index(session)  # 再跑一次不应产生重复
        second = session.execute(text("SELECT count(*) FROM search_doc")).scalar()
        assert first == second

    def test_index_lag_reports_sync_state(self, svc, session):
        self._seed(svc)
        session.commit()
        lag_before = index_lag(session)
        assert lag_before["lag"] > 0  # 尚未消费

        consume_search_index(session)
        lag_after = index_lag(session)
        assert lag_after["lag"] == 0
        assert lag_after["indexedDocs"] >= 2
        assert lag_after["consumer"] == CONSUMER_NAME

    def test_entity_deletion_removes_doc(self, svc, session):
        self._seed(svc)
        session.commit()
        consume_search_index(session)
        assert session.execute(
            text("SELECT count(*) FROM search_doc WHERE urn = :u"), {"u": STG}
        ).scalar() == 1

        svc.delete_entity(STG, reason="source dropped")
        session.commit()
        consume_search_index(session)
        assert session.execute(
            text("SELECT count(*) FROM search_doc WHERE urn = :u"), {"u": STG}
        ).scalar() == 0

    def test_full_text_search_matches_identifier_parts(self, svc, session):
        """标识符切分后，'orders' 能命中 ods_orders / stg_orders（09 §9.3 的实现要点）。"""
        self._seed(svc)
        session.commit()
        consume_search_index(session)
        rows = session.execute(
            text("SELECT urn FROM search_doc WHERE tsv @@ plainto_tsquery('simple', :q)"),
            {"q": "orders"},
        ).mappings().all()
        assert {r["urn"] for r in rows} == {ODS, STG}

    def test_chinese_search_is_a_known_limitation_here(self, svc, session):
        """把已知限制固化成测试：PG 的 `simple` 配置不做中文分词。

        中文检索需要 pg_jieba/zhparser 扩展，或按 docs/10 §1 迁移到 OpenSearch + IK 分词器。
        本测试的作用是：当环境补上分词器时，它会失败并提醒我们更新实现与文档。
        """
        self._seed(svc)
        session.commit()
        consume_search_index(session)
        rows = session.execute(
            text("SELECT urn FROM search_doc WHERE tsv @@ plainto_tsquery('simple', :q)"),
            {"q": "订单"},
        ).mappings().all()
        assert rows == [], "出现结果说明环境已具备中文分词能力，请更新 09 §9.3 与检索实现"

    def test_tokenize_identifier_splits_snake_and_camel(self):
        from dg.consumers.search_index import tokenize_identifier

        assert tokenize_identifier("ods_orders") == "ods orders"
        assert tokenize_identifier("dwdOrderDetail") == "dwd Order Detail"
        assert tokenize_identifier("prod.mysql.sales") == "prod mysql sales"

    def test_watermark_visible_after_indexing(self, svc, session):
        self._seed(svc)
        session.commit()
        consume_search_index(session)
        row = session.execute(
            text("SELECT indexed_watermark FROM search_doc WHERE urn = :u"), {"u": ODS}
        ).scalar()
        max_seq = session.execute(text("SELECT MAX(seq) FROM event_log")).scalar()
        assert row is not None and row <= max_seq and row > 0
