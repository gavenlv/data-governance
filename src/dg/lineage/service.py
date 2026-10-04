"""血缘服务：把 SQL 解析结果写入平台血缘图（docs/09 §9.2）。

职责边界：
  - 解析（parser.py）只负责"从 SQL 得到列级关系"
  - **本模块负责"把名字解析成平台 URN，再写成边"**
  - 名字解析是真实难点：SQL 里写 `ods.orders`，平台里是
    `urn:dg:Dataset:prod.postgresql.dg.public.orders` —— 二者不是同一个东西

未解析的表**不写边**（宁可缺边，也不要用猜的边污染血缘图，见 docs/08 §4.4），
但会被计入 unresolved 并写入样本库 —— 解析率因此成为可运营指标。
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field

from sqlalchemy import text
from sqlalchemy.orm import Session

from dg.core.service import MetadataService
from dg.core.urn import build_urn, parse_urn
from dg.lineage.parser import ParseResult, parse_sql
from dg.model import ModelRegistry

LINEAGE_SOURCE = "sql_parse"


@dataclass
class LineageIngestResult:
    statements: int = 0
    table_edges: int = 0
    column_edges: int = 0
    failed: int = 0
    downgraded: int = 0
    unresolved_tables: list[str] = field(default_factory=list)
    samples_recorded: int = 0

    def as_dict(self) -> dict:
        return {
            "statements": self.statements,
            "tableEdges": self.table_edges,
            "columnEdges": self.column_edges,
            "failed": self.failed,
            "downgraded": self.downgraded,
            "unresolvedTables": sorted(set(self.unresolved_tables)),
            "samplesRecorded": self.samples_recorded,
        }


class TableResolver:
    """把 SQL 中的表名解析为平台内的 Dataset URN。

    解析顺序（命中即返回）：
      1. 已经是平台 URN → 直接用
      2. 带 namespace 前缀的完整路径（ns.platform.db.schema.table）→ 直接构造
      3. 按 URN 后缀匹配（`%.<db>.<schema>.<table>`）
      4. 按 display_name 精确匹配
    解析不到就返回 None —— **不猜**。
    """

    def __init__(self, session: Session, namespace: str = "prod", *, cache: bool = True) -> None:
        self.session = session
        self.namespace = namespace
        self._cache: dict[str, str | None] = {} if cache else {}
        self._use_cache = cache

    def resolve(self, raw: str) -> str | None:
        if not raw:
            return None
        key = raw.strip().lower()
        if self._use_cache and key in self._cache:
            return self._cache[key]
        urn = self._resolve_uncached(raw.strip())
        if self._use_cache:
            self._cache[key] = urn
        return urn

    def _resolve_uncached(self, raw: str) -> str | None:
        if raw.startswith("urn:dg:"):
            try:
                parse_urn(raw)
                return raw
            except Exception:
                return None

        parts = [p.strip("`").strip('"') for p in raw.split(".") if p.strip()]
        if not parts:
            return None

        # 2) 完整路径：ns.platform.db.schema.table
        if len(parts) == 5:
            return build_urn("Dataset", *parts)

        # 3) URN 后缀匹配（SQL 里的 schema 名通常与平台一致）
        suffix = ".".join(parts[-min(len(parts), 5):])
        urn = self._unique(
            """
            SELECT urn FROM entity
             WHERE entity_type = 'Dataset' AND deleted_at IS NULL
               AND namespace = :ns AND urn LIKE :pattern
            """,
            {"ns": self.namespace, "pattern": f"%{suffix}"},
        )
        if urn:
            return urn

        # 4) 退一步：只用表名匹配（SQL 里的 schema 前缀与平台不一致时的常见情形，
        #    例如 SQL 写 `ods.orders` 而平台里是 `<db>.main.orders`）。
        #    **仍然要求唯一** —— 跨 schema 同名表存在歧义时返回 None，不猜。
        return self._unique(
            """
            SELECT urn FROM entity
             WHERE entity_type = 'Dataset' AND deleted_at IS NULL
               AND namespace = :ns AND display_name = :name
            """,
            {"ns": self.namespace, "name": parts[-1]},
        )

    def _unique(self, sql: str, params: dict) -> str | None:
        """只在结果唯一时返回；0 条或多条都返回 None（宁可解析不出，也不猜）。"""
        rows = self.session.execute(text(sql), params).scalars().all()
        return rows[0] if len(rows) == 1 else None


def _column_urn(dataset_urn: str, column: str) -> str:
    parsed = parse_urn(dataset_urn)
    return build_urn("Column", *parsed.parts, column)


def ingest_sql(
    session: Session,
    registry: ModelRegistry,
    sql: str,
    *,
    dialect: str = "hive",
    namespace: str = "prod",
    run_id: str | None = None,
    actor: str = "lineage",
    resolver: TableResolver | None = None,
    record_samples: bool = True,
) -> LineageIngestResult:
    """解析 SQL 并把血缘写入图。"""
    result = LineageIngestResult()
    resolver = resolver or TableResolver(session, namespace)
    svc = MetadataService(session, registry, actor=actor, namespace=namespace)

    for parsed in parse_sql(sql, dialect=dialect):
        result.statements += 1
        if parsed.parse_level == "failed":
            result.failed += 1
        elif parsed.parse_level != "exact":
            result.downgraded += 1

        if record_samples and parsed.parse_level != "exact":
            result.samples_recorded += _record_sample(session, parsed, run_id)

        target_urn = resolver.resolve(parsed.target_table) if parsed.target_table else None
        if parsed.target_table and target_urn is None:
            result.unresolved_tables.append(parsed.target_table)

        # --- 表级边：源表 → 目标表（先建实体与表级边，保证列级边有落点）---
        resolved_sources: dict[str, str] = {}
        for raw_source in parsed.source_tables:
            urn = resolver.resolve(raw_source)
            if urn is None:
                result.unresolved_tables.append(raw_source)
                continue
            resolved_sources[raw_source.lower()] = urn

        if target_urn:
            svc.ensure_entity(target_urn, "Dataset", run_id=run_id)
            for urn in resolved_sources.values():
                svc.ensure_entity(urn, "Dataset", run_id=run_id)
                svc.upsert_edge(
                    urn,
                    target_urn,
                    "derivesFrom",
                    source=LINEAGE_SOURCE,
                    confidence=_confidence_for(parsed),
                    parse_level=parsed.parse_level,
                    via_job=None,
                    run_id=run_id,
                )
                result.table_edges += 1

        # --- 列级边 ---
        if not target_urn or not parsed.column_edges:
            continue
        for edge in parsed.column_edges:
            source_urn = resolved_sources.get(edge.from_table.lower()) or resolver.resolve(
                edge.from_table
            )
            if source_urn is None:
                result.unresolved_tables.append(edge.from_table)
                continue
            from_column_urn = _column_urn(source_urn, edge.from_column)
            to_column_urn = _column_urn(target_urn, edge.to_column)

            # 列实体按需创建（列级治理属性的载体，见 docs/08 §4.1）
            for column_urn in (from_column_urn, to_column_urn):
                svc.ensure_entity(column_urn, "Column", run_id=run_id)

            svc.upsert_edge(
                from_column_urn,
                to_column_urn,
                "derivesFrom",
                source=LINEAGE_SOURCE,
                confidence=edge.confidence,
                transform=edge.transform,
                transform_expression=edge.expression,
                cardinality=edge.cardinality,
                dependency_kind=edge.dependency_kind,
                parse_level=edge.parse_level,
                run_id=run_id,
            )
            result.column_edges += 1

    return result


def _confidence_for(parsed: ParseResult) -> float:
    return {
        "exact": 0.8,
        "derived": 0.65,
        "table_level_only": 0.5,
        "failed": 0.0,
    }.get(parsed.parse_level, 0.5)


def _record_sample(session: Session, parsed: ParseResult, run_id: str | None) -> int:
    """把"不够好"的解析结果写入样本库（按内容哈希聚合）。

    这是把方言覆盖率变成可运营指标的关键：能看出"哪类 SQL 解析不了、有多少"。
    """
    sql_hash = hashlib.sha256(parsed.sql.encode("utf-8")).hexdigest()[:32]
    session.execute(
        text(
            """
            INSERT INTO lineage_parse_sample
                (run_id, dialect, statement_type, parse_level, error, warnings,
                 sql_hash, sql_excerpt, sql_full, occurrences, first_seen, last_seen)
            VALUES (:run_id, :dialect, :stype, :level, :error, CAST(:warnings AS jsonb),
                    :hash, :excerpt, :full, 1, now(), now())
            ON CONFLICT (dialect, sql_hash, parse_level) DO UPDATE
                SET occurrences = lineage_parse_sample.occurrences + 1,
                    last_seen = now(),
                    run_id = EXCLUDED.run_id,
                    warnings = EXCLUDED.warnings
            """
        ),
        {
            "run_id": run_id,
            "dialect": parsed.dialect,
            "stype": parsed.statement_type,
            "level": parsed.parse_level if parsed.parse_level != "failed" else "failed",
            "error": parsed.error,
            "warnings": json.dumps(parsed.warnings),
            "hash": sql_hash,
            "excerpt": " ".join(parsed.sql.split())[:300],
            "full": parsed.sql[:20000],
        },
    )
    return 1


def parse_quality_report(session: Session, *, limit: int = 20) -> dict:
    """解析质量报告：样本库按严重度聚合（供 /api/v1/lineage/quality 与 CLI）。"""
    rows = session.execute(
        text(
            """
            SELECT dialect, parse_level, sum(occurrences) AS total, count(*) AS distinct_sql
              FROM lineage_parse_sample
             GROUP BY dialect, parse_level
             ORDER BY total DESC
             LIMIT :limit
            """
        ),
        {"limit": limit},
    ).mappings().all()

    top = session.execute(
        text(
            """
            SELECT dialect, parse_level, error, sql_excerpt, occurrences
              FROM lineage_parse_sample
             ORDER BY occurrences DESC
             LIMIT :limit
            """
        ),
        {"limit": limit},
    ).mappings().all()

    # 血缘边的来源分布（用于判断"解析出来的边占多少"）
    edge_rows = session.execute(
        text(
            """
            SELECT source, dependency_kind, count(*) AS edges
              FROM edge
             WHERE edge_type = 'derivesFrom'
             GROUP BY source, dependency_kind
             ORDER BY edges DESC
            """
        )
    ).mappings().all()

    return {
        "samplesByDialect": [dict(r) for r in rows],
        "topFailures": [dict(r) for r in top],
        "edgeSources": [dict(r) for r in edge_rows],
    }


__all__ = [
    "LINEAGE_SOURCE",
    "LineageIngestResult",
    "TableResolver",
    "ingest_sql",
    "parse_quality_report",
]
