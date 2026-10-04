"""核心层：元数据读写与事件（docs/08、docs/09 §9.2）。"""

from dg.core.urn import (  # noqa: F401
    ParsedUrn,
    UrnError,
    build_urn,
    column_urn,
    dataset_urn,
    is_urn,
    parse_urn,
)
