"""元数据核心服务：实体 / Aspect / 边 的读写与事件发布。

实现的架构约束：
  ADR-001  PostgreSQL 是唯一真相源
  ADR-002  业务写入与 event_log(outbox) 在**同一事务**内提交；派生视图由消费者构建，可重放重建
  ADR-005  aspect 字段级来源优先级：MANUAL > IMPORTED > AI_GENERATED > AUTO_COLLECTED
           —— 采集不得覆盖人工内容；每次采集(run)可整体回滚
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from typing import Any, Iterable, Literal

from sqlalchemy import text
from sqlalchemy.orm import Session

from dg.core.urn import parse_urn
from dg.model import ModelError, ModelRegistry

# ---------------------------------------------------------------------------
# 字段级来源优先级（ADR-005）。数值越大越"权威"，低优先级不得覆盖高优先级。
# ---------------------------------------------------------------------------
SOURCE_PRIORITY: dict[str, int] = {
    "MANUAL": 40,
    "IMPORTED": 30,
    "AI_GENERATED": 20,
    "AUTO_COLLECTED": 10,
}
DEFAULT_SOURCE = "AUTO_COLLECTED"

Direction = Literal["upstream", "downstream"]


# ---------------------------------------------------------------------------
# 异常
# ---------------------------------------------------------------------------
class MetadataError(Exception):
    """元数据操作错误基类。"""


class NotFound(MetadataError):
    """对象不存在。"""


class Conflict(MetadataError):
    """乐观锁冲突 / 状态冲突。"""


class ValidationFailed(MetadataError):
    """模型校验失败。"""

    def __init__(self, errors: list[str]):
        self.errors = errors
        super().__init__("; ".join(errors))


# ---------------------------------------------------------------------------
# 结果对象
# ---------------------------------------------------------------------------
@dataclass
class UpsertResult:
    urn: str
    aspect_type: str
    version: int
    event_seq: int
    changed_fields: list[str] = field(default_factory=list)
    # 因来源优先级保护而被拒绝覆盖的字段：{field: {"kept":..,"rejected":..}}
    protected_fields: dict[str, Any] = field(default_factory=dict)
    created: bool = False

    @property
    def noop(self) -> bool:
        """数据与来源均未变化（采集重复提交时可据此避免产生无意义事件）。"""
        return not self.changed_fields and not self.protected_fields and not self.created


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)


def _merge_with_source_protection(
    existing_data: dict[str, Any],
    existing_sources: dict[str, str],
    new_data: dict[str, Any],
    new_source: str,
) -> tuple[dict[str, Any], dict[str, str], dict[str, Any], list[str]]:
    """按来源优先级合并 aspect 数据。

    返回 (merged_data, merged_sources, protected, changed_fields)。
    protected 中的字段保持旧值 —— 这是"采集绝不覆盖人工内容"的实现点。
    """
    merged = dict(existing_data)
    sources = dict(existing_sources)
    protected: dict[str, Any] = {}
    changed: list[str] = []

    new_priority = SOURCE_PRIORITY.get(new_source, 0)

    for key, value in new_data.items():
        old_source = sources.get(key)
        old_priority = SOURCE_PRIORITY.get(old_source or "", -1)

        if old_source is not None and old_priority > new_priority:
            if merged.get(key) != value:
                protected[key] = {
                    "kept": merged.get(key),
                    "keptSource": old_source,
                    "rejected": value,
                    "rejectedSource": new_source,
                    "reason": "字段来源优先级更高，拒绝覆盖",
                }
            continue

        if merged.get(key) != value or sources.get(key) != new_source:
            changed.append(key)
        merged[key] = value
        sources[key] = new_source

    return merged, sources, protected, changed


class MetadataService:
    """元数据读写服务。调用方负责事务边界（db.session_scope）。"""

    def __init__(
        self,
        session: Session,
        registry: ModelRegistry,
        *,
        actor: str = "system",
        tenant: str = "default",
        namespace: str = "prod",
    ) -> None:
        self.s = session
        self.registry = registry
        self.actor = actor
        self.tenant = tenant
        self.namespace = namespace

    # ---------------------------------------------------------------- entity

    def ensure_entity(
        self,
        urn: str,
        entity_type: str,
        *,
        display_name: str | None = None,
        properties: dict | None = None,
        run_id: str | None = None,
    ) -> tuple[dict, bool]:
        """确保实体存在（幂等）。返回 (实体行, 是否新建)。

        复活语义（docs/08 §5、docs/09 §9.1）：
        墓碑（TOMBSTONE / DELETED_AT_SOURCE）实体再次被采集到时**自动复活**——
        人工成果（aspect）从未被物理删除，因此复活后即刻恢复。
        此前实现用 `ON CONFLICT ... DO UPDATE ... WHERE deleted_at IS NULL`，
        墓碑实体会走到"既不插入也不更新"的分支并导致查询无行（隐蔽崩溃），现已修正。
        """
        self.registry.entity_type(entity_type)  # 未定义类型直接拒绝
        parsed = parse_urn(urn)
        if parsed.entity_type != entity_type:
            raise ValidationFailed(
                [f"URN 中的类型 {parsed.entity_type!r} 与参数 entity_type={entity_type!r} 不一致"]
            )

        existing = self.s.execute(
            text("SELECT deleted_at, lifecycle FROM entity WHERE urn = :urn"), {"urn": urn}
        ).mappings().first()
        created = existing is None
        resurrected = existing is not None and existing["deleted_at"] is not None

        row = self.s.execute(
            text(
                """
                INSERT INTO entity (urn, entity_type, namespace, tenant, display_name, properties, run_id)
                VALUES (:urn, :et, :ns, :tenant, :dn, CAST(:props AS jsonb), :run_id)
                ON CONFLICT (urn) DO UPDATE
                    SET deleted_at = NULL,
                        lifecycle = CASE
                            WHEN entity.lifecycle IN ('TOMBSTONE', 'DELETED_AT_SOURCE') THEN 'ACTIVE'
                            ELSE entity.lifecycle END,
                        updated_at = now(),
                        display_name = COALESCE(entity.display_name, EXCLUDED.display_name),
                        run_id = COALESCE(EXCLUDED.run_id, entity.run_id)
                RETURNING urn, entity_type, namespace, tenant, display_name, lifecycle,
                          properties, created_at, updated_at
                """
            ),
            {
                "urn": urn,
                "et": entity_type,
                "ns": self.namespace,
                "tenant": self.tenant,
                "dn": display_name,
                "props": _json(properties or {}),
                "run_id": run_id,
            },
        ).mappings().one()

        if created:
            self._emit_event(
                event_type="ENTITY_CREATED",
                urn=urn,
                aspect_type=None,
                version=None,
                payload={"entityType": entity_type, "displayName": display_name},
                run_id=run_id,
            )
        elif resurrected:
            # 复活需要通知消费者重建索引文档，否则它会以为该实体已删除
            self._emit_event(
                event_type="ENTITY_RESURRECTED",
                urn=urn,
                aspect_type=None,
                version=None,
                payload={"entityType": entity_type, "previousLifecycle": existing["lifecycle"]},
                run_id=run_id,
            )
        return dict(row), created

    def mark_deleted_at_source(
        self, urns: list[str], *, run_id: str | None = None, reason: str = ""
    ) -> list[str]:
        """采集发现源端已消失的实体 → 进墓碑（08 §5）。

        注意这是**软删**：`aspect` 与其版本历史都保留，因此再次采集到时会自动复活
        （见 ensure_entity 的复活语义）。物理行不会被删除，审计与血缘证据完整。
        """
        if not urns:
            return []
        rows = self.s.execute(
            text(
                """
                UPDATE entity
                   SET deleted_at = now(),
                       lifecycle = 'DELETED_AT_SOURCE',
                       updated_at = now()
                 WHERE urn = ANY(:urns) AND deleted_at IS NULL
                RETURNING urn
                """
            ),
            {"urns": list(urns)},
        ).mappings().all()

        for row in rows:
            self._emit_event(
                event_type="ENTITY_DELETED",
                urn=row["urn"],
                aspect_type=None,
                version=None,
                payload={"cause": "DELETED_AT_SOURCE", "reason": reason},
                run_id=run_id,
            )
        return [r["urn"] for r in rows]

    def get_entity(self, urn: str) -> dict:
        row = self.s.execute(
            text(
                """
                SELECT urn, entity_type, namespace, tenant, display_name, lifecycle,
                       properties, created_at, updated_at, deleted_at
                  FROM entity
                 WHERE urn = :urn AND deleted_at IS NULL
                """
            ),
            {"urn": urn},
        ).mappings().first()
        if row is None:
            raise NotFound(f"实体不存在：{urn}")
        return dict(row)

    def delete_entity(self, urn: str, *, reason: str = "", run_id: str | None = None) -> dict:
        """软删除 + 墓碑（08 §5）。

        COMPOSITION 关系的子实体级联软删（08 §4.6）；血缘历史保留。
        """
        parsed = parse_urn(urn)
        # 找出所有 COMPOSITION 关系下指向该实体的子实体
        composition_rels = [
            r.name for r in self.registry.relationship_types.values() if r.category == "COMPOSITION"
        ]
        cascaded: list[str] = []

        rows = self.s.execute(
            text(
                """
                WITH RECURSIVE children(urn) AS (
                    SELECT CAST(:urn AS text)
                    UNION
                    SELECT e.to_urn
                      FROM edge e
                      JOIN children c ON e.from_urn = c.urn
                     WHERE e.edge_type = ANY(:rels) AND e.state = 'ACTIVE'
                )
                SELECT urn FROM children
                """
            ),
            {"urn": urn, "rels": composition_rels},
        ).mappings().all()

        for row in rows:
            child = row["urn"]
            updated = self.s.execute(
                text(
                    """
                    UPDATE entity SET deleted_at = now(), lifecycle = 'TOMBSTONE', updated_at = now()
                     WHERE urn = :urn AND deleted_at IS NULL
                    RETURNING urn
                    """
                ),
                {"urn": child},
            ).mappings().first()
            if updated:
                cascaded.append(child)
                self._emit_event(
                    event_type="ENTITY_DELETED",
                    urn=child,
                    aspect_type=None,
                    version=None,
                    payload={"reason": reason, "rootUrn": urn, "viaComposition": child != urn},
                    run_id=run_id,
                )

        if not cascaded:
            raise NotFound(f"实体不存在或已删除：{urn}")
        return {"urn": urn, "deleted": cascaded, "entityType": parsed.entity_type}

    # ---------------------------------------------------------------- aspect

    def upsert_aspect(
        self,
        urn: str,
        aspect_type: str,
        data: dict,
        *,
        source: str = DEFAULT_SOURCE,
        expected_version: int | None = None,
        run_id: str | None = None,
    ) -> UpsertResult:
        """写入/更新一个 aspect（变更的最小单位）。

        - 模型校验（含未建模字段的命名空间约束）
        - 乐观锁：expected_version 不匹配则 Conflict
        - 来源保护：低优先级来源不覆盖高优先级字段（ADR-005）
        - 版本历史 + 审计 + outbox 事件（同一事务）
        """
        if source not in SOURCE_PRIORITY:
            raise ValidationFailed(
                [f"未知来源 {source!r}，允许值：{sorted(SOURCE_PRIORITY)}"]
            )

        entity = self.get_entity(urn)
        if not self.registry.is_aspect_allowed(entity["entity_type"], aspect_type):
            raise ValidationFailed(
                [
                    f"实体类型 {entity['entity_type']} 不允许 aspect {aspect_type!r}"
                    f"（允许：{list(self.registry.entity_type(entity['entity_type']).aspects)}）"
                ]
            )

        errors = self.registry.validate_aspect_data(aspect_type, data)
        if errors:
            raise ValidationFailed(errors)

        current = self.s.execute(
            text(
                """
                SELECT version, data, field_sources
                  FROM aspect
                 WHERE urn = :urn AND aspect_type = :at
                 FOR UPDATE
                """
            ),
            {"urn": urn, "at": aspect_type},
        ).mappings().first()

        if current is None:
            if expected_version not in (None, 0):
                raise Conflict(f"aspect 不存在，但期望版本为 {expected_version}")
            merged, sources, protected, changed = _merge_with_source_protection(
                {}, {}, data, source
            )
            self.s.execute(
                text(
                    """
                    INSERT INTO aspect (urn, aspect_type, version, data, field_sources, updated_by, run_id)
                    VALUES (:urn, :at, 1, CAST(:data AS jsonb), CAST(:fs AS jsonb), :actor, :run_id)
                    """
                ),
                {
                    "urn": urn,
                    "at": aspect_type,
                    "data": _json(merged),
                    "fs": _json(sources),
                    "actor": self.actor,
                    "run_id": run_id,
                },
            )
            version = 1
            created = True
        else:
            if expected_version is not None and expected_version != current["version"]:
                raise Conflict(
                    f"乐观锁冲突：{urn}#{aspect_type} 当前版本 {current['version']}，"
                    f"期望 {expected_version}"
                )
            old_data = current["data"] or {}
            old_sources = current["field_sources"] or {}
            merged, sources, protected, changed = _merge_with_source_protection(
                old_data, old_sources, data, source
            )

            if not changed:
                # 幂等：无实际变化则不产生新版本与事件（内容指纹增量的具体体现）
                return UpsertResult(
                    urn=urn,
                    aspect_type=aspect_type,
                    version=current["version"],
                    event_seq=-1,
                    changed_fields=[],
                    protected_fields=protected,
                    created=False,
                )

            self.s.execute(
                text(
                    """
                    INSERT INTO aspect_history
                        (urn, aspect_type, version, data, field_sources, updated_by, updated_at, run_id)
                    SELECT urn, aspect_type, version, data, field_sources, updated_by, updated_at, run_id
                      FROM aspect WHERE urn = :urn AND aspect_type = :at
                    """
                ),
                {"urn": urn, "at": aspect_type},
            )
            self.s.execute(
                text(
                    """
                    UPDATE aspect
                       SET data = CAST(:data AS jsonb),
                           field_sources = CAST(:fs AS jsonb),
                           version = version + 1,
                           updated_by = :actor,
                           updated_at = now(),
                           run_id = :run_id
                     WHERE urn = :urn AND aspect_type = :at
                    """
                ),
                {
                    "urn": urn,
                    "at": aspect_type,
                    "data": _json(merged),
                    "fs": _json(sources),
                    "actor": self.actor,
                    "run_id": run_id,
                },
            )
            version = current["version"] + 1
            created = False

        event_seq = self._emit_event(
            event_type="ASPECT_UPSERTED",
            urn=urn,
            aspect_type=aspect_type,
            version=version,
            payload={"data": merged, "fieldSources": sources, "source": source},
            run_id=run_id,
        )
        self._audit(
            action="ASPECT_UPSERTED",
            urn=urn,
            aspect_type=aspect_type,
            before=(current["data"] if current else None),
            after=merged,
        )
        return UpsertResult(
            urn=urn,
            aspect_type=aspect_type,
            version=version,
            event_seq=event_seq,
            changed_fields=changed,
            protected_fields=protected,
            created=created,
        )

    def get_aspect(self, urn: str, aspect_type: str) -> dict | None:
        row = self.s.execute(
            text("SELECT data FROM aspect WHERE urn = :urn AND aspect_type = :at"),
            {"urn": urn, "at": aspect_type},
        ).mappings().first()
        return dict(row["data"]) if row else None

    def list_aspects(self, urn: str) -> dict[str, dict]:
        rows = self.s.execute(
            text("SELECT aspect_type, data FROM aspect WHERE urn = :urn"),
            {"urn": urn},
        ).mappings().all()
        return {r["aspect_type"]: dict(r["data"]) for r in rows}

    def aspect_history(self, urn: str, aspect_type: str) -> list[dict]:
        rows = self.s.execute(
            text(
                """
                SELECT version, data, updated_by, updated_at, run_id
                  FROM aspect_history
                 WHERE urn = :urn AND aspect_type = :at
                 ORDER BY version DESC
                """
            ),
            {"urn": urn, "at": aspect_type},
        ).mappings().all()
        return [dict(r) for r in rows]

    # ------------------------------------------------------------------ edge

    def upsert_edge(
        self,
        from_urn: str,
        to_urn: str,
        edge_type: str,
        *,
        source: str,
        confidence: float = 1.0,
        transform: str | None = None,
        transform_expression: str | None = None,
        cardinality: str | None = None,
        dependency_kind: str = "VALUE",
        parse_level: str | None = None,
        via_job: str | None = None,
        run_id: str | None = None,
    ) -> int:
        """写入/更新一条关系边。

        方向约定：**from_urn = 上游（数据来源），to_urn = 下游（数据去向）**，
        与 docs/08 §4.4 示例一致。注意 OpenLineage 的 columnLineage facet 方向相反
        （输出列→输入列），接入时必须显式反转（08 §7.1）。
        """
        self.registry.relationship_type(edge_type)
        if not 0.0 <= confidence <= 1.0:
            raise ValidationFailed([f"confidence 必须在 [0,1]：{confidence}"])

        row = self.s.execute(
            text(
                """
                INSERT INTO edge (from_urn, to_urn, edge_type, source, confidence, transform,
                                  transform_expression, cardinality, dependency_kind, parse_level,
                                  via_job, first_seen, last_seen, observed_count)
                VALUES (:f, :t, :et, :src, :conf, :xf, :xfe, :card, :dk, :pl, :job,
                        now(), now(), 1)
                ON CONFLICT (from_urn, to_urn, edge_type, source, dependency_kind) DO UPDATE
                    SET last_seen = now(),
                        observed_count = edge.observed_count + 1,
                        confidence = GREATEST(edge.confidence, EXCLUDED.confidence),
                        transform = COALESCE(EXCLUDED.transform, edge.transform),
                        transform_expression = COALESCE(EXCLUDED.transform_expression, edge.transform_expression),
                        cardinality = COALESCE(EXCLUDED.cardinality, edge.cardinality),
                        parse_level = COALESCE(EXCLUDED.parse_level, edge.parse_level),
                        state = 'ACTIVE'
                RETURNING id
                """
            ),
            {
                "f": from_urn,
                "t": to_urn,
                "et": edge_type,
                "src": source,
                "conf": confidence,
                "xf": transform,
                "xfe": transform_expression,
                "card": cardinality,
                "dk": dependency_kind,
                "pl": parse_level,
                "job": via_job,
            },
        ).mappings().one()

        edge_id = int(row["id"])
        # 血缘关系才发事件（派生视图只需重建血缘相关变更）
        if self.registry.relationship_type(edge_type).lineage:
            self._emit_event(
                event_type="EDGE_UPSERTED",
                urn=from_urn,
                aspect_type=None,
                version=None,
                payload={
                    "edgeId": edge_id,
                    "fromUrn": from_urn,
                    "toUrn": to_urn,
                    "edgeType": edge_type,
                    "source": source,
                    "confidence": confidence,
                    "dependencyKind": dependency_kind,
                },
                run_id=run_id,
            )
        return edge_id

    def lineage(
        self,
        urn: str,
        direction: Direction = "downstream",
        *,
        max_depth: int = 3,
        min_confidence: float = 0.0,
    ) -> dict:
        """血缘遍历。

        实现约束（docs/09 §9.2）：
          - **用 UNION 去重而非 UNION ALL**：真实血缘图有环，UNION ALL 会路径数指数膨胀
          - 深度有界（默认 3 跳）
          - 只走 state='ACTIVE' 且置信度达阈值的边
          - 排除 CONTROL 依赖（窗口函数 PARTITION BY 等不参与值级血缘）
        """
        if direction not in ("upstream", "downstream"):
            raise ValidationFailed([f"direction 必须是 upstream/downstream，收到 {direction!r}"])
        if not 1 <= max_depth <= 10:
            raise ValidationFailed([f"max_depth 必须在 [1,10]，收到 {max_depth}"])

        if direction == "downstream":
            join_clause = "e.from_urn = w.urn"
            next_col = "e.to_urn"
        else:
            join_clause = "e.to_urn = w.urn"
            next_col = "e.from_urn"

        rows = self.s.execute(
            text(
                f"""
                WITH RECURSIVE walk(urn, depth) AS (
                    SELECT CAST(:start AS text), 0
                    UNION
                    SELECT {next_col}, w.depth + 1
                      FROM edge e
                      JOIN walk w ON {join_clause}
                     WHERE e.state = 'ACTIVE'
                       AND e.dependency_kind = 'VALUE'
                       AND e.confidence >= :min_conf
                       AND w.depth < :max_depth
                )
                SELECT w.urn, MIN(w.depth) AS depth
                  FROM walk w
                 WHERE w.urn <> :start
                 GROUP BY w.urn
                 ORDER BY depth, urn
                """
            ),
            {"start": urn, "max_depth": max_depth, "min_conf": min_confidence},
        ).mappings().all()

        edges = self.s.execute(
            text(
                """
                SELECT from_urn, to_urn, edge_type, source, confidence, transform, state
                  FROM edge
                 WHERE state = 'ACTIVE'
                   AND (from_urn = ANY(:urns) OR to_urn = ANY(:urns))
                """
            ),
            {"urns": [r["urn"] for r in rows] + [urn]},
        ).mappings().all()

        return {
            "urn": urn,
            "direction": direction,
            "maxDepth": max_depth,
            "nodes": [{"urn": r["urn"], "depth": int(r["depth"])} for r in rows],
            "edges": [dict(e) for e in edges],
        }

    # ------------------------------------------------------- 采集变更集回滚

    def rollback_run(self, run_id: str, *, limit: int = 10000) -> dict:
        """按采集批次整体回滚（ADR-005）。

        "这次采集误删了 300 张表必须能一键回退" —— 开源平台的常见缺口。
        做法：把该 run 写入的 aspect 恢复到其前一版本；该 run 新建的实体进墓碑。
        """
        restored: list[str] = []
        dropped: list[str] = []
        tombstoned: list[str] = []

        # 1) 该 run **更新过**的 aspect（存在历史版本）：回滚到前一版本
        rows = self.s.execute(
            text(
                """
                UPDATE aspect a
                   SET data = h.data,
                       field_sources = h.field_sources,
                       version = a.version + 1,
                       updated_by = :actor,
                       updated_at = now(),
                       run_id = NULL
                  FROM aspect_history h
                 WHERE a.run_id = :run_id
                   AND h.urn = a.urn
                   AND h.aspect_type = a.aspect_type
                   AND h.version = a.version - 1
                RETURNING a.urn, a.aspect_type
                """
            ),
            {"run_id": run_id, "actor": self.actor},
        ).mappings().all()
        restored = [f"{r['urn']}#{r['aspect_type']}" for r in rows]

        # 2) 该 run **首次创建**的 aspect（version=1 且无历史）：整体删除
        #    （此前遗漏此分支，导致"错误采集新建的 aspect 无法回退"）
        dropped_rows = self.s.execute(
            text(
                """
                DELETE FROM aspect a
                 WHERE a.run_id = :run_id
                   AND a.version = 1
                   AND NOT EXISTS (
                       SELECT 1 FROM aspect_history h
                        WHERE h.urn = a.urn AND h.aspect_type = a.aspect_type
                   )
                RETURNING a.urn, a.aspect_type
                """
            ),
            {"run_id": run_id},
        ).mappings().all()
        dropped = [f"{r['urn']}#{r['aspect_type']}" for r in dropped_rows]

        # 3) 该 run 创建、且回滚后已无任何 aspect 的实体：进墓碑
        #    （有 aspect 的实体保留 —— 其 aspect 已在步骤 1/2 处理）
        created_rows = self.s.execute(
            text(
                """
                UPDATE entity e
                   SET deleted_at = now(), lifecycle = 'TOMBSTONE', updated_at = now()
                 WHERE e.run_id = :run_id
                   AND e.deleted_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM aspect a WHERE a.urn = e.urn)
                RETURNING e.urn
                """
            ),
            {"run_id": run_id},
        ).mappings().all()
        tombstoned = [r["urn"] for r in created_rows]

        # 4) 该 run 产生的边：通过 event_log 追溯并删除
        #    （边本身不带 run_id，其归属由 EDGE_UPSERTED 事件的 run_id + payload.edgeId 记录）
        edge_rows = self.s.execute(
            text(
                """
                DELETE FROM edge
                 WHERE id IN (
                       SELECT (payload ->> 'edgeId')::bigint
                         FROM event_log
                        WHERE event_type = 'EDGE_UPSERTED'
                          AND run_id = :run_id
                          AND payload ? 'edgeId'
                 )
                RETURNING id
                """
            ),
            {"run_id": run_id},
        ).mappings().all()

        self._emit_event(
            event_type="RUN_ROLLED_BACK",
            urn="urn:dg:Platform:internal",
            aspect_type=None,
            version=None,
            payload={
                "runId": run_id,
                "restoredAspects": restored,
                "droppedAspects": dropped,
                "tombstonedEntities": tombstoned,
            },
            run_id=run_id,
        )
        return {
            "runId": run_id,
            "restoredAspects": restored,
            "droppedAspects": dropped,
            "tombstonedEntities": tombstoned,
            "deletedEdges": len(edge_rows),
        }

    # ------------------------------------------------------------- 内部工具

    def _emit_event(
        self,
        *,
        event_type: str,
        urn: str,
        aspect_type: str | None,
        version: int | None,
        payload: dict,
        run_id: str | None,
    ) -> int:
        row = self.s.execute(
            text(
                """
                INSERT INTO event_log (event_type, urn, aspect_type, version, payload, actor, run_id)
                VALUES (:et, :urn, :at, :v, CAST(:payload AS jsonb), :actor, :run_id)
                RETURNING seq
                """
            ),
            {
                "et": event_type,
                "urn": urn,
                "at": aspect_type,
                "v": version,
                "payload": _json(payload),
                "actor": self.actor,
                "run_id": run_id,
            },
        ).mappings().one()
        return int(row["seq"])

    def _audit(
        self,
        *,
        action: str,
        urn: str | None,
        aspect_type: str | None,
        before: Any,
        after: Any,
    ) -> None:
        """审计日志（append-only + 哈希链，防篡改）。"""
        prev = self.s.execute(
            text("SELECT hash FROM audit_log ORDER BY seq DESC LIMIT 1")
        ).scalar()
        material = _json(
            {
                "prev": prev or "",
                "action": action,
                "urn": urn,
                "aspect": aspect_type,
                "before": before,
                "after": after,
                "actor": self.actor,
            }
        )
        digest = hashlib.sha256(material.encode("utf-8")).hexdigest()
        self.s.execute(
            text(
                """
                INSERT INTO audit_log (actor, action, urn, aspect_type, before, after, prev_hash, hash)
                VALUES (:actor, :action, :urn, :at, CAST(:before AS jsonb), CAST(:after AS jsonb), :prev, :hash)
                """
            ),
            {
                "actor": self.actor,
                "action": action,
                "urn": urn,
                "at": aspect_type,
                "before": _json(before) if before is not None else None,
                "after": _json(after) if after is not None else None,
                "prev": prev,
                "hash": digest,
            },
        )


def load_registry_or_fail(model_dir: str | None = None) -> ModelRegistry:
    from dg.config import settings

    try:
        return ModelRegistry.load(model_dir or settings.model_dir)
    except ModelError as exc:  # pragma: no cover - 启动期失败
        raise SystemExit(f"模型加载失败：{exc}") from exc


__all__ = [
    "Conflict",
    "MetadataError",
    "MetadataService",
    "NotFound",
    "SOURCE_PRIORITY",
    "UpsertResult",
    "ValidationFailed",
    "load_registry_or_fail",
]
