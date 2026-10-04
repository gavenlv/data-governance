"""URN 构造与解析（docs/08-metadata-model.md §2）。

格式：urn:dg:<entityType>:<part1>.<part2>...

约定（08 §2 的设计约束）：
  - URN 一旦分配永不复用（删除进墓碑）
  - 列作为 dataset 的子资源（点号续接），保证列级血缘指向稳定标识
  - **前缀不得依赖"可能缺失的列"** —— 采集账号权限降级会导致静默缺段（08 §2 实测发现）
"""

from __future__ import annotations

import re
from dataclasses import dataclass

URN_PREFIX = "urn:dg:"
_URN_RE = re.compile(r"^urn:dg:(?P<type>[A-Za-z][A-Za-z0-9]*):(?P<path>[^\s]+)$")


class UrnError(ValueError):
    """URN 非法。"""


@dataclass(frozen=True)
class ParsedUrn:
    entity_type: str
    path: str

    @property
    def parts(self) -> tuple[str, ...]:
        return tuple(self.path.split("."))

    @property
    def parent_urn(self) -> str | None:
        """父实体 URN（去掉最后一段）；无父时返回 None。"""
        parts = self.parts
        if len(parts) <= 1:
            return None
        return build_urn(self.entity_type, *parts[:-1])


def build_urn(entity_type: str, *parts: str) -> str:
    if not entity_type or not entity_type[0].isalpha():
        raise UrnError(f"entityType 非法：{entity_type!r}")
    if not parts:
        raise UrnError("URN 至少需要一个路径段")
    for part in parts:
        if not part or "." in part or ":" in part or " " in part:
            raise UrnError(f"路径段非法（不可含 . : 空格）：{part!r}")
    return f"{URN_PREFIX}{entity_type}:{'.'.join(parts)}"


def parse_urn(urn: str) -> ParsedUrn:
    match = _URN_RE.match(urn or "")
    if not match:
        raise UrnError(f"URN 格式非法：{urn!r}（期望 urn:dg:<EntityType>:<a.b.c>）")
    return ParsedUrn(entity_type=match.group("type"), path=match.group("path"))


def dataset_urn(namespace: str, platform: str, database: str, schema: str, table: str) -> str:
    """数据集的便捷构造：urn:dg:dataset:<ns>.<platform>.<db>.<schema>.<table>"""
    return build_urn("Dataset", namespace, platform, database, schema, table)


def column_urn(dataset: str, column: str) -> str:
    """列的便捷构造：dataset URN 续接列名。"""
    parsed = parse_urn(dataset)
    return build_urn("Column", *parsed.parts, column)


def is_urn(value: str) -> bool:
    try:
        parse_urn(value)
        return True
    except UrnError:
        return False
