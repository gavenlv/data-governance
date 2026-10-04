"""Model Registry 生成的 Python 类型（占位）。

实际内容由 `python -m dg.cli codegen` 生成到本目录；本文件仅用于
在尚未生成时保持包可导入（CI 的 `codegen --check` 会要求生成物存在）。
"""

try:  # pragma: no cover
    from dg.generated.model_gen import (  # noqa: F401
        ASPECT_PROPERTIES,
        ASPECT_SOURCE_TRACKED,
        ASPECT_TYPES,
        ENTITY_ASPECTS,
        ENTITY_PARENTS,
        ENTITY_TYPES,
        LINEAGE_RELATIONSHIPS,
        RELATIONSHIP_CATEGORIES,
        RELATIONSHIP_ENDS,
    )

    GENERATED = True
except ImportError:  # pragma: no cover
    GENERATED = False
    ENTITY_TYPES = ()
    ASPECT_TYPES = ()

__all__ = ["GENERATED"]
