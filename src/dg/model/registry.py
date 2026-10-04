"""Model Registry：加载、校验并演进元数据模型定义。

设计依据 docs/08-metadata-model.md §6：
  L1 数据扩展  customProperties（不改模型）
  L2 模型扩展  YAML 定义新 EntityType/AspectType/RelationshipType（本模块）
  L3 逻辑扩展  Connector / Rule / Detector 插件

本模块承担两个职责：
  1. 运行期：加载模型定义，校验 aspect 数据合法性；
  2. 构建期（CI）：新旧模型对比，**阻断不兼容变更** —— 防止"新增扩展把老客户端打爆"。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

import yaml

SUPPORTED_API_VERSIONS = {"dg.model/v1"}


class ModelError(Exception):
    """模型定义或模型变更非法。"""


# ---------------------------------------------------------------------------
# 定义对象
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PropertyDef:
    name: str
    type: str
    required: bool = False
    default: Any = None
    enum_values: tuple[str, ...] = ()
    items_type: str | None = None

    @staticmethod
    def from_dict(raw: dict) -> "PropertyDef":
        if "name" not in raw or "type" not in raw:
            raise ModelError(f"property 定义缺少 name/type: {raw!r}")
        return PropertyDef(
            name=raw["name"],
            type=raw["type"],
            required=bool(raw.get("required", False)),
            default=raw.get("default"),
            enum_values=tuple(raw.get("values", ()) or ()),
            items_type=raw.get("itemsType"),
        )


@dataclass(frozen=True)
class AspectTypeDef:
    name: str
    display_name: str
    properties: tuple[PropertyDef, ...] = ()
    # 参与字段级来源优先级判定（ADR-005）：MANUAL > IMPORTED > AUTO_COLLECTED
    source_tracked: bool = False
    time_series: bool = False

    @staticmethod
    def from_dict(raw: dict) -> "AspectTypeDef":
        return AspectTypeDef(
            name=raw["name"],
            display_name=raw.get("displayName", raw["name"]),
            properties=tuple(PropertyDef.from_dict(p) for p in raw.get("properties", [])),
            source_tracked=bool(raw.get("sourceTracked", False)),
            time_series=bool(raw.get("timeSeries", False)),
        )

    def property(self, name: str) -> PropertyDef | None:
        for prop in self.properties:
            if prop.name == name:
                return prop
        return None


@dataclass(frozen=True)
class EntityTypeDef:
    name: str
    display_name: str
    description: str = ""
    parent_types: tuple[str, ...] = ()
    aspects: tuple[str, ...] = ()
    namespace_scoped: bool = False

    @staticmethod
    def from_dict(raw: dict) -> "EntityTypeDef":
        return EntityTypeDef(
            name=raw["name"],
            display_name=raw.get("displayName", raw["name"]),
            description=raw.get("description", ""),
            parent_types=tuple(raw.get("parentTypes", ()) or ()),
            aspects=tuple(raw.get("aspects", ()) or ()),
            namespace_scoped=bool(raw.get("namespaceScoped", False)),
        )


@dataclass(frozen=True)
class RelationshipTypeDef:
    """关系类型。

    category 决定删除/级联语义（借鉴 Atlas relationshipCategory，08 §4.6）：
      COMPOSITION 子对象不能脱离父对象 → 删除父对象时级联软删
      AGGREGATION 子对象可独立存在     → 仅解除关系
      ASSOCIATION 纯引用               → 只删边
    """

    name: str
    display_name: str
    category: str
    from_types: tuple[str, ...]
    to_types: tuple[str, ...]
    lineage: bool = False
    description: str = ""

    CATEGORIES = ("COMPOSITION", "AGGREGATION", "ASSOCIATION")

    @staticmethod
    def from_dict(raw: dict) -> "RelationshipTypeDef":
        category = raw.get("category", "ASSOCIATION")
        if category not in RelationshipTypeDef.CATEGORIES:
            raise ModelError(
                f"关系 {raw.get('name')!r} 的 category={category!r} 非法，"
                f"必须是 {RelationshipTypeDef.CATEGORIES} 之一"
            )
        return RelationshipTypeDef(
            name=raw["name"],
            display_name=raw.get("displayName", raw["name"]),
            category=category,
            from_types=tuple(raw.get("from", ()) or ()),
            to_types=tuple(raw.get("to", ()) or ()),
            lineage=bool(raw.get("lineage", False)),
            description=raw.get("description", ""),
        )


# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------


@dataclass
class ModelRegistry:
    entity_types: dict[str, EntityTypeDef] = field(default_factory=dict)
    aspect_types: dict[str, AspectTypeDef] = field(default_factory=dict)
    relationship_types: dict[str, RelationshipTypeDef] = field(default_factory=dict)
    source_files: tuple[str, ...] = ()

    # -- 加载 ---------------------------------------------------------------

    @staticmethod
    def load(model_dir: Path | str) -> "ModelRegistry":
        """加载模型目录下所有 *.yaml（递归）。核心目录与前缀 extensions/ 等价处理。"""
        root = Path(model_dir)
        if not root.exists():
            raise ModelError(f"模型目录不存在：{root}")

        registry = ModelRegistry()
        files: list[str] = []
        for path in sorted(root.rglob("*.yaml")) + sorted(root.rglob("*.yml")):
            raw = yaml.safe_load(path.read_text(encoding="utf-8"))
            if not raw:
                continue
            registry._merge_document(raw, source=str(path))
            files.append(str(path.relative_to(root)))
        registry.source_files = tuple(files)
        registry._validate_consistency()
        return registry

    def _merge_document(self, raw: dict, source: str) -> None:
        api_version = raw.get("apiVersion")
        if api_version not in SUPPORTED_API_VERSIONS:
            raise ModelError(
                f"{source}: apiVersion={api_version!r} 不受支持，"
                f"支持 {sorted(SUPPORTED_API_VERSIONS)}"
            )
        kind = raw.get("kind")
        if kind == "EntityTypes":
            for item in raw.get("entityTypes", []):
                ent = EntityTypeDef.from_dict(item)
                self._register(self.entity_types, ent.name, ent, source)
            for item in raw.get("relationshipTypes", []):
                rel = RelationshipTypeDef.from_dict(item)
                self._register(self.relationship_types, rel.name, rel, source)
        elif kind == "AspectTypes":
            for item in raw.get("aspectTypes", []):
                asp = AspectTypeDef.from_dict(item)
                self._register(self.aspect_types, asp.name, asp, source)
        else:
            raise ModelError(f"{source}: 未知 kind={kind!r}")

    @staticmethod
    def _register(store: dict, name: str, obj: Any, source: str) -> None:
        if name in store:
            raise ModelError(f"{source}: 重复定义 {name!r}（已存在于其它模型文件）")
        store[name] = obj

    def _validate_consistency(self) -> None:
        """交叉校验：引用的 aspect / 父类型 / 关系端点必须存在。"""
        problems: list[str] = []
        for ent in self.entity_types.values():
            for asp in ent.aspects:
                if asp not in self.aspect_types:
                    problems.append(f"实体 {ent.name} 引用了未定义的 aspect {asp!r}")
            for parent in ent.parent_types:
                if parent not in self.entity_types:
                    problems.append(f"实体 {ent.name} 的 parentType {parent!r} 未定义")
        for rel in self.relationship_types.values():
            for side, names in (("from", rel.from_types), ("to", rel.to_types)):
                for name in names:
                    if name not in self.entity_types:
                        problems.append(f"关系 {rel.name} 的 {side} 端 {name!r} 未定义")
        if problems:
            raise ModelError("模型定义不一致：\n  - " + "\n  - ".join(problems))

    # -- 查询 ---------------------------------------------------------------

    def entity_type(self, name: str) -> EntityTypeDef:
        try:
            return self.entity_types[name]
        except KeyError:
            raise ModelError(f"未定义的实体类型：{name!r}") from None

    def aspect_type(self, name: str) -> AspectTypeDef:
        try:
            return self.aspect_types[name]
        except KeyError:
            raise ModelError(f"未定义的 aspect 类型：{name!r}") from None

    def relationship_type(self, name: str) -> RelationshipTypeDef:
        try:
            return self.relationship_types[name]
        except KeyError:
            raise ModelError(f"未定义的关系类型：{name!r}") from None

    def is_aspect_allowed(self, entity_type: str, aspect_type: str) -> bool:
        return aspect_type in self.entity_type(entity_type).aspects

    def lineage_relationship_names(self) -> set[str]:
        return {r.name for r in self.relationship_types.values() if r.lineage}

    def all_entity_names(self) -> set[str]:
        return set(self.entity_types)

    def summary(self) -> dict:
        return {
            "entity_types": len(self.entity_types),
            "aspect_types": len(self.aspect_types),
            "relationship_types": len(self.relationship_types),
            "source_files": list(self.source_files),
        }

    # -- 校验 aspect 数据 ---------------------------------------------------

    def validate_aspect_data(self, aspect_type: str, data: dict) -> list[str]:
        """按 AspectType 定义校验数据。返回错误列表（空 = 合法）。"""
        if not isinstance(data, dict):
            return [f"aspect 数据必须是对象，收到 {type(data).__name__}"]
        spec = self.aspect_type(aspect_type)
        errors: list[str] = []

        known = {p.name for p in spec.properties}
        for key in data:
            if key not in known:
                # 逃生舱：未建模字段必须带命名空间前缀，避免各团队键名冲突（08 §8 反模式 6）
                if not (key.startswith("x_") or ":" in key or "." in key):
                    errors.append(
                        f"未知属性 {key!r}：若确需扩展请使用命名空间前缀（如 x_team 或 team:key）"
                    )

        for prop in spec.properties:
            value = data.get(prop.name, None)
            if value is None:
                if prop.required and prop.default is None:
                    errors.append(f"缺少必填属性 {prop.name!r}")
                continue
            errors.extend(_check_type(aspect_type, prop, value))
        return errors

    # -- 兼容性校验（CI 强制，08 §6）----------------------------------------

    @staticmethod
    def compatibility_errors(old: "ModelRegistry", new: "ModelRegistry") -> list[str]:
        """返回**不兼容变更**清单。CI 中出现非空即应阻断合并。

        禁止：删除 EntityType/AspectType/属性；改属性类型；
              收紧 required（False→True）；移除枚举值；收紧枚举默认值。
        """
        errors: list[str] = []

        for name in old.entity_types:
            if name not in new.entity_types:
                errors.append(f"[BREAKING] 删除实体类型 {name!r}")
            else:
                o, n = old.entity_types[name], new.entity_types[name]
                removed = set(o.aspects) - set(n.aspects)
                if removed:
                    errors.append(f"[BREAKING] 实体 {name} 移除 aspect: {sorted(removed)}")

        for name, o in old.aspect_types.items():
            n = new.aspect_types.get(name)
            if n is None:
                errors.append(f"[BREAKING] 删除 aspect 类型 {name!r}")
                continue
            if o.source_tracked != n.source_tracked:
                errors.append(f"[BREAKING] aspect {name} 的 sourceTracked 变更（影响覆盖保护语义）")
            errors.extend(_compare_properties(name, o, n))
            if o.time_series and not n.time_series:
                errors.append(f"[BREAKING] aspect {name} 由时序退化为非时序")

        for name, o in old.relationship_types.items():
            n = new.relationship_types.get(name)
            if n is None:
                errors.append(f"[BREAKING] 删除关系类型 {name!r}")
                continue
            if o.category != n.category:
                errors.append(
                    f"[BREAKING] 关系 {name} 的 category 由 {o.category} 变为 {n.category}"
                    f"（改变删除级联语义）"
                )

        return errors


def _compare_properties(aspect_name: str, old: AspectTypeDef, new: AspectTypeDef) -> list[str]:
    errors: list[str] = []
    new_props = {p.name: p for p in new.properties}
    for op in old.properties:
        np = new_props.get(op.name)
        if np is None:
            errors.append(f"[BREAKING] aspect {aspect_name} 删除属性 {op.name!r}")
            continue
        if op.type != np.type:
            errors.append(
                f"[BREAKING] aspect {aspect_name}.{op.name} 类型由 {op.type} 变为 {np.type}"
            )
        if not op.required and np.required:
            errors.append(f"[BREAKING] aspect {aspect_name}.{op.name} 由可选改为必填")
        dropped = set(op.enum_values) - set(np.enum_values)
        if dropped:
            errors.append(
                f"[BREAKING] aspect {aspect_name}.{op.name} 移除枚举值 {sorted(dropped)}"
            )
    return errors


def _check_type(aspect_name: str, prop: PropertyDef, value: Any) -> list[str]:
    errs: list[str] = []
    t = prop.type
    ok = True
    if t == "string":
        ok = isinstance(value, str)
    elif t == "integer":
        ok = isinstance(value, int) and not isinstance(value, bool)
    elif t == "number":
        ok = isinstance(value, (int, float)) and not isinstance(value, bool)
    elif t == "boolean":
        ok = isinstance(value, bool)
    elif t == "object":
        ok = isinstance(value, dict)
    elif t == "array":
        ok = isinstance(value, list)
        if ok and prop.items_type == "string":
            ok = all(isinstance(v, str) for v in value)
    elif t == "enum":
        ok = value in prop.enum_values
    if not ok:
        errs.append(f"aspect {aspect_name}.{prop.name} 期望 {t}，收到 {value!r}")
    return errs


__all__ = [
    "AspectTypeDef",
    "EntityTypeDef",
    "Iterable",
    "ModelError",
    "ModelRegistry",
    "PropertyDef",
    "RelationshipTypeDef",
]
