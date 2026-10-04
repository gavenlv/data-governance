"""事件消费者：从 event_log 构建派生视图。

这是 ADR-002 的落地：**派生视图可丢弃、可重放重建**。
本模块实现 search_doc（轻量模式检索索引；生产可替换为 OpenSearch 而核心不变）。

关键设计：
  - 消费者进度记在 consumer_offset，支持从任意 seq 重放
  - 处理是幂等的（同一事件重复消费结果一致）
  - 每次写入记录 indexed_watermark，用于"索引已同步到哪个事件"的可见性提示
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Callable, Iterable

from sqlalchemy import text
from sqlalchemy.orm import Session

CONSUMER_NAME = "search_index"

_CAMEL_RE = re.compile(r"([a-z0-9])([A-Z])")


def tokenize_identifier(value: str) -> str:
    """把标识符切成可检索的词（docs/09 §9.3 的实现要点）。

    纯按空白切词会让 `ods_orders` 变成一个 token，"orders" 就搜不到；
    因此必须叠加 `_` / `-` / `.` 与驼峰切分。
    中文分词则需要真正的分词器（pg_jieba/zhparser 或 OpenSearch+IK），见模块末尾说明。
    """
    if not value:
        return ""
    spaced = _CAMEL_RE.sub(r"\1 \2", value)
    for ch in ("_", "-", ".", ":", "/", "\\"):
        spaced = spaced.replace(ch, " ")
    return spaced


@dataclass
class ConsumeStats:
    processed: int = 0
    indexed: int = 0
    deleted: int = 0
    from_seq: int = 0
    to_seq: int = 0


def _get_offset(session: Session, consumer: str) -> int:
    return int(
        session.execute(
            text("SELECT last_seq FROM consumer_offset WHERE consumer = :c"),
            {"c": consumer},
        ).scalar()
        or 0
    )


def _set_offset(session: Session, consumer: str, seq: int, status: str = "IDLE") -> None:
    session.execute(
        text(
            """
            INSERT INTO consumer_offset (consumer, last_seq, status, updated_at)
            VALUES (:c, :seq, :status, now())
            ON CONFLICT (consumer) DO UPDATE
                SET last_seq = EXCLUDED.last_seq,
                    status = EXCLUDED.status,
                    updated_at = now()
            """
        ),
        {"c": consumer, "seq": seq, "status": status},
    )


def reset_consumer(session: Session, consumer: str = CONSUMER_NAME) -> None:
    """重置消费者进度（重放起点）。配合 truncate 索引即可实现"索引重建"。"""
    _set_offset(session, consumer, 0, status="RESET")


def truncate_search_index(session: Session) -> None:
    """丢弃派生视图（证明它可丢弃）。"""
    session.execute(text("TRUNCATE search_doc"))


def rebuild_search_index(session: Session, *, batch_size: int = 500) -> ConsumeStats:
    """索引重建：清空索引 → 从 seq=0 重放到最新。"""
    truncate_search_index(session)
    reset_consumer(session, CONSUMER_NAME)
    session.commit()
    return consume_search_index(session, batch_size=batch_size)


def consume_search_index(
    session: Session,
    *,
    batch_size: int = 500,
    max_batches: int | None = None,
) -> ConsumeStats:
    """增量消费事件流，维护 search_doc。"""
    stats = ConsumeStats()
    offset = _get_offset(session, CONSUMER_NAME)
    stats.from_seq = offset
    batches = 0

    while True:
        rows = session.execute(
            text(
                """
                SELECT seq, event_type, urn, aspect_type, version, payload
                  FROM event_log
                 WHERE seq > :offset
                 ORDER BY seq
                 LIMIT :limit
                """
            ),
            {"offset": offset, "limit": batch_size},
        ).mappings().all()
        if not rows:
            break

        for row in rows:
            _apply_event(session, row, stats)
            offset = int(row["seq"])
            stats.processed += 1

        _set_offset(session, CONSUMER_NAME, offset, status="RUNNING")
        session.commit()

        batches += 1
        if max_batches is not None and batches >= max_batches:
            break

    stats.to_seq = offset
    _set_offset(session, CONSUMER_NAME, offset, status="IDLE")
    session.commit()
    return stats


def _apply_event(session: Session, row, stats: ConsumeStats) -> None:
    event_type = row["event_type"]
    urn = row["urn"]
    payload = row["payload"] or {}
    watermark = int(row["seq"])

    if event_type == "ENTITY_DELETED":
        session.execute(text("DELETE FROM search_doc WHERE urn = :urn"), {"urn": urn})
        stats.deleted += 1
        return

    # 只对"进入索引的实体类型"建文档（Team/User 等组织实体不入搜索索引）
    if event_type not in ("ENTITY_CREATED", "ASPECT_UPSERTED", "ENTITY_RESURRECTED"):
        return

    indexed_types = {"Platform", "Container", "Dataset", "Column", "Pipeline", "Dashboard", "GlossaryTerm"}
    entity_type = urn.split(":")[2] if urn.count(":") >= 2 else ""
    if entity_type not in indexed_types:
        return

    _upsert_search_doc(session, urn, entity_type, watermark)
    stats.indexed += 1


def _upsert_search_doc(session: Session, urn: str, entity_type: str, watermark: int) -> None:
    """从真相源读取当前状态并重建该资产的可检索文档。

    注意：消费者**从真相源读取当前值**（而非从事件 payload 拼装），
    这样"乱序/重复消费"天然幂等。
    """
    entity = session.execute(
        text(
            """
            SELECT urn, entity_type, display_name, namespace
              FROM entity
             WHERE urn = :urn AND deleted_at IS NULL
            """
        ),
        {"urn": urn},
    ).mappings().first()
    if entity is None:
        session.execute(text("DELETE FROM search_doc WHERE urn = :urn"), {"urn": urn})
        return

    aspects = {
        r["aspect_type"]: r["data"]
        for r in session.execute(
            text("SELECT aspect_type, data FROM aspect WHERE urn = :urn"), {"urn": urn}
        ).mappings()
    }

    parts = urn.split(":")[-1].split(".")
    platform = parts[1] if len(parts) > 2 else None
    container = ".".join(parts[1:-1]) if len(parts) > 2 else None

    desc = aspects.get("descriptions") or {}
    description = desc.get("text")
    owners = [
        o.get("urn") or o.get("name")
        for o in (aspects.get("ownership") or {}).get("owners", []) or []
        if isinstance(o, dict)
    ]
    tags = list((aspects.get("tags") or {}).get("tags") or [])
    classification = (aspects.get("classification") or {}).get("level")
    trust = (aspects.get("trustLevel") or {}).get("level")

    # 检索文本：展示名 + URN 的可分词形式 + 描述 + 标签
    # 标识符必须切分（ods_orders → ods orders），否则按空格切词的 simple 配置搜不到
    display_name = entity["display_name"] or parts[-1]
    doc_text = " ".join(
        filter(
            None,
            [
                tokenize_identifier(display_name),
                tokenize_identifier(urn.replace(":", " ")),
                tokenize_identifier(" ".join(parts)),
                description or "",
                " ".join(tokenize_identifier(t) for t in tags),
            ],
        )
    )

    session.execute(
        text(
            """
            INSERT INTO search_doc (urn, entity_type, display_name, namespace, platform, container,
                                    description, tags, owners, classification, tsv,
                                    indexed_at, indexed_watermark)
            VALUES (:urn, :et, :dn, :ns, :platform, :container, :description,
                    :tags, :owners, :cls, to_tsvector('simple', :doc),
                    now(), :wm)
            ON CONFLICT (urn) DO UPDATE
                SET entity_type = EXCLUDED.entity_type,
                    display_name = EXCLUDED.display_name,
                    namespace = EXCLUDED.namespace,
                    platform = EXCLUDED.platform,
                    container = EXCLUDED.container,
                    description = EXCLUDED.description,
                    tags = EXCLUDED.tags,
                    owners = EXCLUDED.owners,
                    classification = EXCLUDED.classification,
                    tsv = EXCLUDED.tsv,
                    indexed_at = now(),
                    indexed_watermark = EXCLUDED.indexed_watermark
            """
        ),
        {
            "urn": urn,
            "et": entity["entity_type"],
            "dn": display_name,
            "ns": entity["namespace"],
            "platform": platform,
            "container": container,
            "description": description,
            "tags": tags,
            "owners": owners,
            "cls": classification,
            "doc": doc_text,
            "wm": watermark,
        },
    )


def index_lag(session: Session, consumer: str = CONSUMER_NAME) -> dict:
    """索引水位：用于 UI 显示"元数据已更新，索引同步中"。"""
    offset = _get_offset(session, consumer)
    latest = int(session.execute(text("SELECT COALESCE(MAX(seq), 0) FROM event_log")).scalar() or 0)
    indexed = int(
        session.execute(text("SELECT COUNT(*) FROM search_doc")).scalar() or 0
    )
    return {
        "consumer": consumer,
        "lastConsumedSeq": offset,
        "latestEventSeq": latest,
        "lag": latest - offset,
        "indexedDocs": indexed,
    }


__all__ = [
    "CONSUMER_NAME",
    "ConsumeStats",
    "consume_search_index",
    "index_lag",
    "rebuild_search_index",
    "reset_consumer",
    "truncate_search_index",
]
