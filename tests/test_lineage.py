"""列级血缘解析测试（docs/09 §9.2）。

三层覆盖：
  1. **语料回归集**（tests/corpus/lineage_corpus.yaml）—— 长期资产，
     解析器升级或 sqlglot 版本变更时跑它并 diff，防止静默退化
  2. 单元行为：L0 预筛、多语句、失败降级、边界情况
  3. OpenLineage 互操作：**columnLineage 的方向反转**（最容易搞错的一步）
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from dg.config import REPO_ROOT
from dg.lineage.parser import (
    SUPPORTED_DIALECTS,
    is_interesting,
    map_openlineage_column_lineage,
    parse_sql,
    parse_statement,
    split_statements,
    sqlglot_version,
)

CORPUS = REPO_ROOT / "tests" / "corpus" / "lineage_corpus.yaml"


def _edge_label(edge) -> str:
    return (
        f"{edge.from_table}.{edge.from_column} -> {edge.to_table}.{edge.to_column}"
        f":{edge.transform}/{edge.dependency_kind}"
    )


def _load_corpus() -> list[dict]:
    return (yaml.safe_load(CORPUS.read_text(encoding="utf-8")) or {}).get("cases", [])


# ---------------------------------------------------------------------------
# 1. 语料回归集
# ---------------------------------------------------------------------------
class TestLineageCorpus:
    @pytest.mark.parametrize("case", _load_corpus(), ids=lambda c: c["name"])
    def test_corpus_case(self, case):
        result = parse_statement(case["sql"], dialect=case["dialect"])

        assert result.parse_level == case["expect_level"], (
            f"{case['name']}: 期望 {case['expect_level']}，实际 {result.parse_level}"
            f"（error={result.error}, warnings={result.warnings}）"
        )

        labels = {_edge_label(e) for e in result.column_edges}
        for expected in case.get("expect_edges") or []:
            assert expected in labels, (
                f"{case['name']}: 缺少期望边 {expected}\n实际：\n  " + "\n  ".join(sorted(labels))
            )
        for forbidden in case.get("must_not_have") or []:
            assert forbidden not in labels, f"{case['name']}: 出现禁止的边 {forbidden}"

    def test_corpus_is_not_empty_and_covers_levels(self):
        cases = _load_corpus()
        assert len(cases) >= 10
        levels = {c["expect_level"] for c in cases}
        assert {"exact", "table_level_only", "failed"} <= levels

    def test_sqlglot_version_is_recorded(self):
        """版本必须可追溯：sqlglot 迭代快，升级需重跑语料（09 §9.2）。"""
        assert sqlglot_version() != "unknown"


# ---------------------------------------------------------------------------
# 2. 单元行为
# ---------------------------------------------------------------------------
class TestL0PreFilter:
    def test_split_multi_statement(self):
        parts = split_statements("SELECT 1; SELECT 2;\n-- comment\nSELECT 3")
        assert len(parts) == 3

    @pytest.mark.parametrize(
        "sql,interesting",
        [
            ("SET hive.exec.dynamic.partition=true", False),
            ("USE db", False),
            ("BEGIN", False),
            ("COMMIT", False),
            ("GRANT SELECT ON t TO u", False),
            ("EXPLAIN SELECT 1", False),
            ("SELECT * FROM t", True),
            ("INSERT INTO t SELECT 1", True),
            ("CREATE TABLE t AS SELECT 1", True),
        ],
    )
    def test_is_interesting(self, sql, interesting):
        assert is_interesting(sql) is interesting

    def test_parse_sql_skips_uninteresting(self):
        results = parse_sql("SET x=1; INSERT INTO dw.t SELECT a FROM s", dialect="hive")
        assert len(results) == 1
        assert results[0].target_table == "dw.t"

    def test_parse_sql_multiple_statements(self):
        results = parse_sql(
            "INSERT INTO dw.a SELECT x AS c FROM s1; INSERT INTO dw.b SELECT y AS c FROM s2",
            dialect="hive",
        )
        assert [r.target_table for r in results] == ["dw.a", "dw.b"]


class TestDowngradeBehaviour:
    def test_star_downgrades_with_reason(self):
        r = parse_statement("INSERT INTO dw.t SELECT * FROM ods.s", dialect="hive")
        assert r.parse_level == "table_level_only"
        assert any("SELECT *" in w for w in r.warnings)
        assert r.source_tables == ["ods.s"]     # 仍给出表级价值

    def test_syntax_error_has_message(self):
        r = parse_statement("INSERT INTO dw.t SELECT FROM WHERE", dialect="hive")
        assert r.parse_level == "failed"
        assert r.error and "ParseError" in r.error

    def test_unknown_dialect_lists_supported(self):
        r = parse_statement("SELECT 1", dialect="klingon")
        assert r.parse_level == "failed"
        assert "不支持的方言" in (r.error or "")
        assert "hive" in (r.error or "")

    def test_unsupported_dialect_registry_is_documented(self):
        assert {"hive", "spark", "trino", "postgres", "bigquery", "doris"} <= SUPPORTED_DIALECTS

    def test_pure_select_is_table_level_only(self):
        r = parse_statement("SELECT a, b FROM ods.s", dialect="hive")
        assert r.parse_level == "table_level_only"
        assert r.target_table is None
        assert r.source_tables == ["ods.s"]

    def test_unqualified_column_with_multiple_sources_is_flagged(self):
        r = parse_statement(
            "INSERT INTO dw.t SELECT id AS out_id FROM s1 JOIN s2 ON s1.k = s2.k",
            dialect="hive",
        )
        # 无法唯一确定来源表 → 显式告警并降级，而不是猜一个
        assert r.parse_level in ("derived", "table_level_only")
        assert any("无法定位到表" in w for w in r.warnings) or not r.column_edges


class TestEdgeAttributes:
    def test_cardinality_marked_on_expanding_functions(self):
        r = parse_statement(
            "INSERT INTO dw.t SELECT id, item FROM src LATERAL VIEW explode(items) x AS item",
            dialect="hive",
        )
        assert all(e.cardinality == "ONE_TO_MANY" for e in r.column_edges)

    def test_lowercase_identifiers_resolved_to_table(self):
        r = parse_statement(
            "INSERT INTO DW.T SELECT A.X AS AX FROM ODS.S A", dialect="hive"
        )
        # 大小写折叠是列级血缘错配的第一大来源（09 §9.2），至少不能报错
        assert r.parse_level in ("exact", "derived", "table_level_only")

    def test_result_serialisable(self):
        r = parse_statement("INSERT INTO dw.t SELECT a.x AS c FROM s a", dialect="hive")
        payload = r.as_dict()
        assert payload["targetTable"] == "dw.t"
        assert payload["columnEdges"]
        assert set(payload["columnEdges"][0]) >= {
            "fromTable", "fromColumn", "toTable", "toColumn",
            "transform", "dependencyKind", "parseLevel",
        }


# ---------------------------------------------------------------------------
# 3. OpenLineage 互操作（方向反转）
# ---------------------------------------------------------------------------
class TestOpenLineageMapping:
    def test_direction_is_reversed(self):
        """OL 的 fields 是「输出列 → 输入列」，平台约定 from=上游、to=下游。"""
        facet = {
            "fields": {
                "cust_key": {
                    "inputFields": [
                        {"namespace": "postgres", "name": "ods.orders", "field": "customer_id",
                         "transformations": [{"type": "DIRECT"}]}
                    ]
                }
            }
        }
        edges = map_openlineage_column_lineage(facet, downstream_dataset="dw.dim_customer")
        assert len(edges) == 1
        e = edges[0]
        assert e.from_table == "ods.orders" and e.from_column == "customer_id"   # 上游
        assert e.to_table == "dw.dim_customer" and e.to_column == "cust_key"     # 下游
        assert e.transform == "DIRECT"
        assert e.confidence == 0.95          # 运行时上报置信度最高
        assert e.parse_level == "exact"

    def test_masking_subtype_maps_to_masked(self):
        facet = {
            "fields": {
                "phone": {
                    "inputFields": [
                        {"name": "ods.s", "field": "phone",
                         "transformations": [{"type": "INDIRECT", "subtype": "masking_sha256"}]}
                    ]
                }
            }
        }
        edges = map_openlineage_column_lineage(facet, downstream_dataset="dw.t")
        assert edges[0].transform == "MASKED"   # 决定分级标签能否阻断传播（09 §9.6）

    def test_indirect_transform(self):
        facet = {
            "fields": {
                "gross": {
                    "inputFields": [
                        {"name": "ods.s", "field": "amount",
                         "transformations": [{"type": "INDIRECT"}]}
                    ]
                }
            }
        }
        assert map_openlineage_column_lineage(facet, downstream_dataset="dw.t")[0].transform == "INDIRECT"

    def test_incomplete_entries_are_skipped_not_crashing(self):
        facet = {"fields": {"a": {"inputFields": [{"name": "", "field": ""}, {}]}}}
        assert map_openlineage_column_lineage(facet, downstream_dataset="dw.t") == []

    def test_empty_facet(self):
        assert map_openlineage_column_lineage({}, downstream_dataset="dw.t") == []
        assert map_openlineage_column_lineage(None, downstream_dataset="dw.t") == []
