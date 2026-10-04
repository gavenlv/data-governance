"""代码生成：把 Model Registry 的 YAML 定义转成各语言类型与常量。

设计依据 docs/08-metadata-model.md §6 与 docs/06 §2.1：
  **Schema-First 单一事实源** —— 一份 YAML 定义生成多语言类型，
  根治"API / SDK / UI 不一致"（这是 OpenMetadata 最值得借鉴的工程不变量）。

生成目标：
  python        dataclass 化的 aspect 视图 + 常量（供服务端与采集器使用）
  typescript    interface + 字面量联合类型（供前端使用）
  jsonschema    JSON Schema（供外部校验与文档生成）

CI 用法：`dgctl codegen --check` —— 生成结果与磁盘不一致即失败，
       防止"改了模型忘了重新生成"。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Protocol

from dg.model import AspectTypeDef, EntityTypeDef, ModelRegistry, PropertyDef

HEADER_LINES = [
    "AUTO-GENERATED FILE — DO NOT EDIT BY HAND.",
    "Source of truth: model/**.yaml",
    "Regenerate: python -m dg.cli codegen --target {target}",
]

PY_TYPE_MAP = {
    "string": "str",
    "integer": "int",
    "number": "float",
    "boolean": "bool",
    "object": "dict[str, Any]",
    "array": "list[Any]",
    "enum": "str",
}


def _banner(comment: str, target: str, registry: ModelRegistry) -> list[str]:
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")
    lines = [f"{comment} " + HEADER_LINES[0], f"{comment} " + HEADER_LINES[1],
             f"{comment} " + HEADER_LINES[2].format(target=target),
             f"{comment} Generated at {stamp} (timestamp line is ignored by --check)"]
    return lines


def _py_literal(value) -> str:
    return repr(value)


def _camel_to_snake(name: str) -> str:
    out: list[str] = []
    for i, ch in enumerate(name):
        if ch.isupper() and i > 0 and not name[i - 1].isupper():
            out.append("_")
        out.append(ch.lower())
    return "".join(out)


def _pascal(name: str) -> str:
    return name[:1].upper() + name[1:]


class Generator(Protocol):
    target: str
    filename: str
    subdir: str

    def render(self, registry: ModelRegistry) -> str: ...


# ---------------------------------------------------------------------------
# Python
# ---------------------------------------------------------------------------
@dataclass
class PythonGenerator:
    target: str = "python"
    filename: str = "model_gen.py"
    subdir: str = "src/dg/generated"

    def render(self, registry: ModelRegistry) -> str:
        lines: list[str] = []
        lines += _banner("#", self.target, registry)
        lines += [
            "from __future__ import annotations",
            "",
            "from dataclasses import dataclass, field",
            "from typing import Any, Literal",
            "",
            "# ---------------------------------------------------------------------------",
            "# 实体类型",
            "# ---------------------------------------------------------------------------",
            f"ENTITY_TYPES: tuple[str, ...] = {tuple(registry.entity_types)!r}",
            "",
            "ENTITY_ASPECTS: dict[str, tuple[str, ...]] = {",
        ]
        for name, ent in sorted(registry.entity_types.items()):
            lines.append(f"    {name!r}: {tuple(ent.aspects)!r},")
        lines += ["}", "", "ENTITY_PARENTS: dict[str, tuple[str, ...]] = {"]
        for name, ent in sorted(registry.entity_types.items()):
            lines.append(f"    {name!r}: {tuple(ent.parent_types)!r},")
        lines += [
            "}",
            "",
            "# ---------------------------------------------------------------------------",
            "# Aspect 类型与字段规格：(字段名, 类型, 是否必填)",
            "# ---------------------------------------------------------------------------",
            f"ASPECT_TYPES: tuple[str, ...] = {tuple(registry.aspect_types)!r}",
            "",
            "ASPECT_PROPERTIES: dict[str, tuple[tuple[str, str, bool], ...]] = {",
        ]
        for name, asp in sorted(registry.aspect_types.items()):
            props = tuple((p.name, p.type, p.required) for p in asp.properties)
            lines.append(f"    {name!r}: {props!r},")
        lines += [
            "}",
            "",
            "ASPECT_SOURCE_TRACKED: dict[str, bool] = {",
        ]
        for name, asp in sorted(registry.aspect_types.items()):
            lines.append(f"    {name!r}: {asp.source_tracked!r},")
        lines += [
            "}",
            "",
            "# ---------------------------------------------------------------------------",
            "# 关系类型",
            "# ---------------------------------------------------------------------------",
            "RELATIONSHIP_CATEGORIES: dict[str, str] = {",
        ]
        for name, rel in sorted(registry.relationship_types.items()):
            lines.append(f"    {name!r}: {rel.category!r},")
        lines += [
            "}",
            f"LINEAGE_RELATIONSHIPS: tuple[str, ...] = {tuple(sorted(registry.lineage_relationship_names()))!r}",
            "",
            "RELATIONSHIP_ENDS: dict[str, tuple[tuple[str, ...], tuple[str, ...]]] = {",
        ]
        for name, rel in sorted(registry.relationship_types.items()):
            lines.append(f"    {name!r}: ({tuple(rel.from_types)!r}, {tuple(rel.to_types)!r}),")
        lines += [
            "}",
            "",
            "# ---------------------------------------------------------------------------",
            "# 常用 aspect 的类型化视图（可直接用于构造与校验）",
            "# ---------------------------------------------------------------------------",
        ]
        for name, asp in sorted(registry.aspect_types.items()):
            lines += self._render_dataclass(asp)
        lines.append("")
        return "\n".join(lines)

    def _render_dataclass(self, asp: AspectTypeDef) -> list[str]:
        cls = _pascal(asp.name)
        out = ["", "@dataclass", f"class {cls}:"]
        if asp.display_name:
            out.append(f'    """{asp.display_name}"""')
        out.append("")
        if not asp.properties:
            out.append("    pass")
            return out
        # 必填字段必须排在可选字段之前：Python 的 dataclass 不允许
        # "无默认值的字段" 出现在 "有默认值的字段" 之后。
        # 这是**生成器的职责**，不是模型作者要记住的规则 ——
        # YAML 里属性按可读性排序（apiVersion 挨着 contractVersion），生成物自己重排。
        properties = list(asp.properties)
        ordered = [p for p in properties if p.required] + [p for p in properties if not p.required]
        for prop in ordered:
            ann = self._annotation(prop)
            if prop.required:
                out.append(f"    {_camel_to_snake(prop.name)}: {ann}")
            else:
                default = self._default(prop)
                out.append(f"    {_camel_to_snake(prop.name)}: {ann} = {default}")
        return out

    @staticmethod
    def _annotation(prop: PropertyDef) -> str:
        if prop.type == "array":
            inner = "str" if prop.items_type == "string" else "Any"
            return f"list[{inner}]"
        if prop.type == "enum" and prop.enum_values:
            return "Literal[" + ", ".join(repr(v) for v in prop.enum_values) + "]"
        return PY_TYPE_MAP.get(prop.type, "Any")

    @staticmethod
    def _default(prop: PropertyDef) -> str:
        if prop.default is not None:
            return repr(prop.default)
        if prop.type == "array":
            return "field(default_factory=list)"
        if prop.type == "object":
            return "field(default_factory=dict)"
        if prop.type == "boolean":
            return "False"
        if prop.type in ("integer", "number"):
            return "0"
        if prop.type == "enum" and prop.enum_values:
            return repr(prop.enum_values[0])
        return "''"


# ---------------------------------------------------------------------------
# TypeScript
# ---------------------------------------------------------------------------
@dataclass
class TypeScriptGenerator:
    target: str = "typescript"
    filename: str = "model_gen.ts"
    subdir: str = "web/generated"

    def render(self, registry: ModelRegistry) -> str:
        lines: list[str] = []
        lines += _banner("//", self.target, registry)
        lines += [
            "",
            "export const ENTITY_TYPES = [",
        ]
        for name in sorted(registry.entity_types):
            lines.append(f"  '{name}',")
        lines += ["] as const;", "export type EntityType = (typeof ENTITY_TYPES)[number];", ""]

        lines += ["export const ASPECT_TYPES = ["]
        for name in sorted(registry.aspect_types):
            lines.append(f"  '{name}',")
        lines += ["] as const;", "export type AspectType = (typeof ASPECT_TYPES)[number];", ""]

        lines += ["export const RELATIONSHIP_CATEGORIES: Record<string, 'COMPOSITION' | 'AGGREGATION' | 'ASSOCIATION'> = {"]
        for name, rel in sorted(registry.relationship_types.items()):
            lines.append(f"  {name}: '{rel.category}',")
        lines += ["};", ""]

        lines += [f"export const LINEAGE_RELATIONSHIPS: readonly string[] = {json.dumps(sorted(registry.lineage_relationship_names()))};", ""]

        lines += ["export const ENTITY_ASPECTS: Record<EntityType, readonly string[]> = {"]
        for name, ent in sorted(registry.entity_types.items()):
            lines.append(f"  {name}: {json.dumps(list(ent.aspects))},")
        lines += ["};", ""]

        lines += ["// ---- aspect 视图 ----"]
        for name, asp in sorted(registry.aspect_types.items()):
            lines += self._render_interface(asp)
        lines.append("")
        return "\n".join(lines)

    def _render_interface(self, asp: AspectTypeDef) -> list[str]:
        out = ["", f"export interface {_pascal(asp.name)} {{"]
        for prop in asp.properties:
            optional = "" if prop.required else "?"
            out.append(f"  {prop.name}{optional}: {self._ts_type(prop)};")
        out.append("}")
        return out

    @staticmethod
    def _ts_type(prop: PropertyDef) -> str:
        if prop.type == "string":
            return "string"
        if prop.type in ("integer", "number"):
            return "number"
        if prop.type == "boolean":
            return "boolean"
        if prop.type == "array":
            return "string[]" if prop.items_type == "string" else "unknown[]"
        if prop.type == "object":
            return "Record<string, unknown>"
        if prop.type == "enum" and prop.enum_values:
            return " | ".join(f"'{v}'" for v in prop.enum_values)
        return "unknown"


# ---------------------------------------------------------------------------
# JSON Schema
# ---------------------------------------------------------------------------
@dataclass
class JsonSchemaGenerator:
    target: str = "jsonschema"
    filename: str = "model_gen.schema.json"
    subdir: str = "schema/generated"

    def render(self, registry: ModelRegistry) -> str:
        defs: dict[str, dict] = {}
        for name, asp in sorted(registry.aspect_types.items()):
            props: dict[str, dict] = {}
            for prop in asp.properties:
                props[prop.name] = self._schema_for(prop)
            defs[f"aspect.{name}"] = {
                "type": "object",
                "properties": props,
                "required": [p.name for p in asp.properties if p.required],
                "additionalProperties": False,
            }
        payload = {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$id": "https://data-governance.local/model/v1",
            "title": "Data Governance Platform metadata model",
            "generatedFrom": list(registry.source_files),
            "x-entityTypes": sorted(registry.entity_types),
            "x-relationshipCategories": {
                n: r.category for n, r in sorted(registry.relationship_types.items())
            },
            "$defs": defs,
        }
        return json.dumps(payload, ensure_ascii=False, indent=2) + "\n"

    @staticmethod
    def _schema_for(prop: PropertyDef) -> dict:
        base: dict = {"title": prop.name}
        if prop.type == "string":
            base["type"] = "string"
        elif prop.type == "integer":
            base["type"] = "integer"
        elif prop.type == "number":
            base["type"] = "number"
        elif prop.type == "boolean":
            base["type"] = "boolean"
        elif prop.type == "object":
            base["type"] = "object"
        elif prop.type == "array":
            base["type"] = "array"
            if prop.items_type in ("string", "integer", "number", "boolean", "object"):
                base["items"] = {"type": prop.items_type}
        elif prop.type == "enum":
            base["type"] = "string"
            base["enum"] = list(prop.enum_values)
        if prop.default is not None:
            base["default"] = prop.default
        return base


GENERATORS: dict[str, Generator] = {
    "python": PythonGenerator(),
    "typescript": TypeScriptGenerator(),
    "jsonschema": JsonSchemaGenerator(),
}


def generate_all(registry: ModelRegistry, root: Path) -> dict[str, Path]:
    """生成全部目标，返回 {target: 写入路径}。"""
    written: dict[str, Path] = {}
    for target, gen in GENERATORS.items():
        out_dir = root / gen.subdir
        out_dir.mkdir(parents=True, exist_ok=True)
        path = out_dir / gen.filename
        path.write_text(gen.render(registry), encoding="utf-8")
        written[target] = path
    return written


def _strip_timestamp(text: str) -> str:
    """--check 时忽略生成时间戳行（否则每次都判定为不一致）。"""
    return "\n".join(
        line for line in text.splitlines() if "Generated at" not in line
    )


def check_generated(registry: ModelRegistry, root: Path) -> list[str]:
    """CI 用：返回不一致的目标清单（空 = 一致）。"""
    stale: list[str] = []
    for target, gen in GENERATORS.items():
        path = root / gen.subdir / gen.filename
        expected = _strip_timestamp(gen.render(registry))
        if not path.exists():
            stale.append(f"{target}: 缺少 {path}（请运行 dgctl codegen）")
            continue
        actual = _strip_timestamp(path.read_text(encoding="utf-8"))
        if actual != expected:
            stale.append(f"{target}: {path} 与模型定义不一致（请重新生成）")
    return stale


__all__ = [
    "GENERATORS",
    "Generator",
    "JsonSchemaGenerator",
    "PythonGenerator",
    "TypeScriptGenerator",
    "check_generated",
    "generate_all",
]
