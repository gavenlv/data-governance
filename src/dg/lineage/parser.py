"""SQL 列级血缘解析（docs/09 §9.2、docs/research/04 附录·补充 20）。

三层流水线（本模块实现 L0 + L1；L2 的类型/作用域深度校验需要 Calcite，见文末说明）：
  L0 预筛   sqlparse.split 切语句 + 丢弃无血缘价值内容（注释/SET/USE/事务控制）
  L1 主解析 sqlglot parse + qualify + lineage 产出列级血缘
  失败      **显式降级为表级并记录原因** —— 绝不静默返回空（这是开源平台的通病）

实现要点（全部来自 09 §9.2 的"实现约束"）：
  - **方向**：本模块输出"上游表/列 → 下游表/列"；OpenLineage 的 columnLineage
    facet 方向相反（输出列→输入列），接入时必须反转（见 map_openlineage_column_lineage）
  - **控制依赖与值依赖分开**：窗口函数 PARTITION BY / ORDER BY 列不进入值级血缘，
    但必须记录为 CONTROL（影响分析需要）
  - **基数变化要标注**：UNNEST/EXPLODE/PIVOT 会放大行数，否则影响面评估失真
  - **parse_level 分层**：exact / derived / table_level_only / failed
  - **方言注册表与版本**：方言名受校验，运行时锁定 sqlglot 版本
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterable

import sqlglot
from sqlglot import exp
from sqlglot.errors import ParseError
from sqlglot.lineage import lineage as sqlglot_lineage
from sqlglot.optimizer.qualify import qualify

SUPPORTED_DIALECTS = {
    "hive", "spark", "spark2", "databricks", "presto", "trino", "postgres", "mysql",
    "clickhouse", "doris", "starrocks", "snowflake", "bigquery", "redshift", "tsql",
    "oracle", "duckdb", "sqlite", "athena", "teradata",
}

# L0 预筛：这些语句没有血缘价值
_SKIP_PREFIXES = (
    "set ", "use ", "begin", "commit", "rollback", "grant", "revoke",
    "create user", "create role", "analyze", "explain", "show ", "describe ",
    "truncate table",
)

CARDINALITY_EXPANDING = {"unnest", "explode", "posexplode", "inline", "pivot", "unpivot"}


@dataclass
class ColumnEdge:
    """一条列级血缘边（上游 → 下游）。"""

    from_table: str
    from_column: str
    to_table: str
    to_column: str
    transform: str = "DIRECT"          # DIRECT | INDIRECT | AGGREGATED | MASKED | FILTER
    expression: str | None = None
    dependency_kind: str = "VALUE"     # VALUE | CONTROL
    parse_level: str = "exact"         # exact | derived
    confidence: float = 0.8
    cardinality: str = "ONE_TO_ONE"    # ONE_TO_ONE | ONE_TO_MANY | MANY_TO_ONE

    def key(self) -> tuple:
        return (
            self.from_table.lower(), self.from_column.lower(),
            self.to_table.lower(), self.to_column.lower(),
            self.dependency_kind,
        )

    def as_dict(self) -> dict:
        return {
            "fromTable": self.from_table,
            "fromColumn": self.from_column,
            "toTable": self.to_table,
            "toColumn": self.to_column,
            "transform": self.transform,
            "expression": self.expression,
            "dependencyKind": self.dependency_kind,
            "parseLevel": self.parse_level,
            "confidence": self.confidence,
            "cardinality": self.cardinality,
        }


@dataclass
class ParseResult:
    """一条 SQL 语句的解析结果。"""

    sql: str
    dialect: str
    statement_type: str = "UNKNOWN"
    target_table: str | None = None
    target_columns: list[str] = field(default_factory=list)
    source_tables: list[str] = field(default_factory=list)
    column_edges: list[ColumnEdge] = field(default_factory=list)
    parse_level: str = "exact"          # exact | derived | table_level_only | failed
    error: str | None = None
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.parse_level in ("exact", "derived")

    def as_dict(self) -> dict:
        return {
            "statementType": self.statement_type,
            "targetTable": self.target_table,
            "targetColumns": self.target_columns,
            "sourceTables": self.source_tables,
            "parseLevel": self.parse_level,
            "error": self.error,
            "warnings": self.warnings,
            "columnEdges": [e.as_dict() for e in self.column_edges],
        }


# ---------------------------------------------------------------------------
# L0 预筛
# ---------------------------------------------------------------------------
def split_statements(sql: str) -> list[str]:
    """切分多语句脚本。sqlparse 只用于这一层（它做不了语义分析）。"""
    try:
        import sqlparse

        raw = sqlparse.split(sql)
    except ImportError:  # pragma: no cover - 退化路径
        raw = [s for s in sql.split(";")]
    return [s.strip() for s in raw if s and s.strip()]


def is_interesting(statement: str) -> bool:
    """是否有血缘价值（丢弃 SET/USE/事务控制等）。"""
    head = " ".join(statement.strip().lower().split())[:40]
    return not any(head.startswith(p) for p in _SKIP_PREFIXES)


# ---------------------------------------------------------------------------
# L1 主解析
# ---------------------------------------------------------------------------
def _table_name(node: exp.Expression | None) -> str | None:
    if node is None:
        return None
    table = node if isinstance(node, exp.Table) else node.find(exp.Table)
    if table is None:
        return None
    return table.name if not table.db else f"{table.db}.{table.name}"


def _target_of(expr: exp.Expression) -> tuple[str | None, list[str]]:
    """取语句的写入目标表与显式目标列。"""
    if isinstance(expr, exp.Insert):
        target = _table_name(expr.this)
        columns = [c.name for c in expr.this.expressions] if isinstance(expr.this, exp.Schema) else []
        if isinstance(expr.this, exp.Schema):
            target = _table_name(expr.this.this)
        return target, columns
    if isinstance(expr, exp.Create):  # CREATE TABLE ... AS SELECT（CTAS）
        target = _table_name(expr.this)
        columns = [c.name for c in expr.this.expressions] if isinstance(expr.this, exp.Schema) else []
        return target, columns
    return None, []


def _select_of(expr: exp.Expression) -> exp.Expression | None:
    if isinstance(expr, exp.Insert):
        return expr.expression
    if isinstance(expr, exp.Create):
        return expr.expression
    if isinstance(expr, exp.Select):
        return expr
    return expr.find(exp.Select)


def _alias_map(select: exp.Expression) -> dict[str, str]:
    """别名 → 限定表名。

    为什么必须自己建这张表：不给 schema 时 sqlglot 的 lineage 叶子节点
    `source_name` 是**空字符串**（实测确认），只有 `name` 里的限定符（如 `o.amount`）。
    因此"来源表"必须由我们通过别名映射还原。
    """
    mapping: dict[str, str] = {}
    for table in select.find_all(exp.Table):
        qualified = f"{table.db}.{table.name}" if table.db else table.name
        mapping[(table.alias or table.name).lower()] = qualified
        mapping[table.name.lower()] = qualified
    return mapping


def _resolve_table(qualifier: str | None, alias_map: dict[str, str]) -> str | None:
    if qualifier:
        return alias_map.get(qualifier.lower(), qualifier)
    # 无限定符：仅当源表唯一时可确定
    unique = {v for v in alias_map.values()}
    return next(iter(unique)) if len(unique) == 1 else None


def _control_columns_by_output(select: exp.Expression) -> dict[str, set[tuple[str | None, str]]]:
    """按**输出列**收集控制依赖列。

    为什么要按输出列作用域：同一个列名可能既作普通输出（值依赖）又作窗口分区
    （控制依赖）。若用全局集合，`dept` 会把自己误判成 CONTROL —— 这是一个
    会污染影响分析的过度匹配（实测发现）。
    """
    out: dict[str, set[tuple[str | None, str]]] = {}
    for proj in select.expressions:
        out_name = proj.alias if isinstance(proj, exp.Alias) else (
            proj.name if isinstance(proj, exp.Column) else None
        )
        if not out_name:
            continue
        cols: set[tuple[str | None, str]] = set()
        for window in proj.find_all(exp.Window):
            parts: list[exp.Expression] = []
            partition = window.args.get("partition_by")
            if partition:
                parts.extend(partition)
            order = window.args.get("order")
            if order is not None:
                parts.extend(getattr(order, "expressions", []) or [])
            for part in parts:
                for col in part.find_all(exp.Column):
                    qualifier = (col.table or "").lower() or None
                    cols.add((qualifier, col.name.lower()))
        if cols:
            out[out_name] = cols
    return out


def _is_control(
    control_cols: set[tuple[str | None, str]] | None, qualifier: str | None, column: str
) -> bool:
    if not control_cols:
        return False
    col = column.lower()
    key = (qualifier.lower() if qualifier else None, col)
    return key in control_cols or (None, col) in control_cols


def _projection_names(select: exp.Expression) -> list[str]:
    """输出列名（alias 优先，否则用表达式推导——那是 derived 级别）。"""
    names: list[str] = []
    for proj in select.expressions:
        if isinstance(proj, exp.Alias):
            names.append(proj.alias)
        elif isinstance(proj, exp.Column):
            names.append(proj.name)
        elif isinstance(proj, exp.Star):
            names.append("*")
        else:
            names.append(proj.sql(dialect="hive")[:64])
    return names


def _transform_kind(expression: str | None) -> str:
    """按表达式判定转换类型（决定合规标签能否阻断传播）。"""
    if not expression:
        return "DIRECT"
    low = expression.lower()
    if any(fn in low for fn in ("md5(", "sha1(", "sha256(", "hash(", "mask", "redact", "***")):
        return "MASKED"
    if any(fn in low for fn in ("sum(", "count(", "avg(", "max(", "min(", "array_agg(")):
        return "AGGREGATED"
    if any(fn in low for fn in ("cast(", "round(", "substr(", "concat(", "coalesce(", "||", "+", "-", "*", "/")):
        return "INDIRECT"
    return "DIRECT"


def _control_dependencies(select: exp.Expression, target: str | None, to_column: str) -> list[ColumnEdge]:
    """窗口函数的 PARTITION BY / ORDER BY 列属**控制依赖**，不参与值级血缘（09 §9.2）。"""
    edges: list[ColumnEdge] = []
    if target is None:
        return edges
    for window in select.find_all(exp.Window):
        parts: list[exp.Expression] = []
        if window.args.get("partition_by"):
            parts.extend(window.args["partition_by"])
        if window.args.get("order"):
            order = window.args["order"]
            parts.extend(order.expressions if hasattr(order, "expressions") else [order])
        for part in parts:
            for col in part.find_all(exp.Column):
                if not col.table:
                    continue
                edges.append(
                    ColumnEdge(
                        from_table=col.table,
                        from_column=col.name,
                        to_table=target,
                        to_column=to_column,
                        transform="FILTER",
                        expression=part.sql()[:200],
                        dependency_kind="CONTROL",
                        parse_level="derived",
                        confidence=0.6,
                    )
                )
    return edges


def _has_cardinality_change(select: exp.Expression) -> bool:
    for node in select.walk():
        name = type(node).__name__.lower()
        if any(k in name for k in CARDINALITY_EXPANDING):
            return True
    return False


def parse_statement(
    sql: str,
    *,
    dialect: str = "hive",
    default_schema: str | None = None,
) -> ParseResult:
    """解析单条 SQL 语句，产出列级血缘。

    失败时返回 parse_level="failed" 且带 error，**同时尽力给出表级血缘**
    （table_level_only），以便调用方仍能获得部分价值。
    """
    result = ParseResult(sql=sql.strip(), dialect=dialect)
    if dialect not in SUPPORTED_DIALECTS:
        result.parse_level = "failed"
        result.error = f"不支持的方言 {dialect!r}（已注册：{sorted(SUPPORTED_DIALECTS)}）"
        return result

    try:
        expressions = sqlglot.parse(
            sql,
            dialect=dialect,
            # 批量解析韧性：坏语句不应中断整批（research/04 附录·补充 17）
            error_level=sqlglot.ErrorLevel.RAISE,
        )
    except ParseError as exc:
        result.parse_level = "failed"
        result.error = f"ParseError: {exc}"
        return result
    except Exception as exc:  # noqa: BLE001 - 解析器可能抛多种异常
        result.parse_level = "failed"
        result.error = f"{type(exc).__name__}: {exc}"
        return result

    expression = next((e for e in expressions if e is not None), None)
    if expression is None:
        result.parse_level = "failed"
        result.error = "语句为空或无法解析"
        return result

    result.statement_type = type(expression).__name__.upper()
    target, target_columns = _target_of(expression)
    result.target_table = target
    result.target_columns = target_columns

    select = _select_of(expression)
    if select is None:
        result.parse_level = "table_level_only"
        result.warnings.append("未找到 SELECT（可能是纯 DDL），仅记录语句类型")
        return result

    # 源表（限定名优先）
    for table in select.find_all(exp.Table):
        name = table.name
        if result.target_table and name == result.target_table.split(".")[-1]:
            continue
        qualified = f"{table.db}.{name}" if table.db else name
        if qualified not in result.source_tables:
            result.source_tables.append(qualified)

    # 纯查询（无写入目标）：只能给表级
    if target is None:
        result.parse_level = "table_level_only"
        result.warnings.append("纯 SELECT 无写入目标，仅产出表级血缘")
        return result

    # 列级：逐输出列追溯来源
    try:
        qualified_sql = qualify(
            select.copy(),
            dialect=dialect,
            schema={} if default_schema is None else {},
            validate_qualify_columns=False,
        ).sql(dialect=dialect)
    except Exception:  # qualify 失败不致命，退回原始 SQL
        qualified_sql = select.sql(dialect=dialect)

    projection_names = _projection_names(select)
    expanded = _has_cardinality_change(select)
    alias_map = _alias_map(select)
    control_by_output = _control_columns_by_output(select)
    produced: dict[tuple, ColumnEdge] = {}
    derived_only = False

    for idx, out_name in enumerate(projection_names):
        to_column = (
            target_columns[idx] if idx < len(target_columns) else
            (out_name if out_name != "*" else "_star_")
        )
        if out_name == "*":
            result.warnings.append("存在 SELECT *，未展开列级血缘（缺 schema）")
            derived_only = True
            continue

        try:
            node = sqlglot_lineage(out_name, qualified_sql, dialect=dialect)
        except Exception as exc:  # 单列失败不影响其它列
            result.warnings.append(f"列 {out_name} 追溯失败：{type(exc).__name__}")
            derived_only = True
            continue

        sources = _collect_sources(node)
        if not sources:
            result.warnings.append(f"列 {out_name} 未追溯到源列")
            derived_only = True
            continue

        for qualifier, column_name, _expr in sources:
            table_name = _resolve_table(qualifier, alias_map)
            if not table_name:
                result.warnings.append(
                    f"列 {out_name} 的来源 {qualifier or column_name!r} 无法定位到表"
                    f"（无表限定且存在多个源表）"
                )
                derived_only = True
                continue

            expression = None
            for proj in select.expressions:
                if isinstance(proj, exp.Alias) and proj.alias == out_name:
                    expression = proj.this.sql()[:200]
                    break
                if isinstance(proj, exp.Column) and proj.name == out_name:
                    expression = proj.sql()[:200]
                    break

            control = _is_control(control_by_output.get(out_name), qualifier, column_name)
            edge = ColumnEdge(
                from_table=table_name,
                from_column=column_name,
                to_table=target,
                to_column=to_column,
                transform="FILTER" if control else _transform_kind(expression),
                expression=expression,
                dependency_kind="CONTROL" if control else "VALUE",
                parse_level="derived" if derived_only else "exact",
                confidence=0.8 if not derived_only else 0.65,
                cardinality="ONE_TO_MANY" if expanded else "ONE_TO_ONE",
            )
            produced.setdefault(edge.key(), edge)

    result.column_edges = list(produced.values())
    if not result.column_edges:
        result.parse_level = "table_level_only"
        result.warnings.append("未能产出任何列级边，降级为表级")
    elif derived_only:
        result.parse_level = "derived"
    else:
        result.parse_level = "exact"
    return result


def _collect_sources(node) -> list[tuple[str | None, str, str | None]]:
    """从 sqlglot lineage 节点收集叶子源列。

    返回 (限定符|None, 列名, 表达式)。限定符是别名或表名，由调用方用
    `_alias_map` 还原为真实表名 —— 因为不给 schema 时 `source_name` 为空。
    """
    out: list[tuple[str | None, str, str | None]] = []
    seen: set[tuple[str | None, str]] = set()

    def walk(current, depth: int = 0) -> None:
        if current is None or depth > 12:
            return
        downstream = list(getattr(current, "downstream", []) or [])
        if not downstream:
            name = getattr(current, "name", None)
            if not name:
                return
            parts = str(name).split(".")
            qualifier = parts[0] if len(parts) > 1 else None
            column = parts[-1]
            key = (qualifier, column)
            if key in seen:
                return
            seen.add(key)
            out.append((qualifier, column, None))
            return
        for child in downstream:
            walk(child, depth + 1)

    walk(node, 0)
    return out


def parse_sql(
    sql: str,
    *,
    dialect: str = "hive",
    default_schema: str | None = None,
) -> list[ParseResult]:
    """解析多语句脚本，返回每条语句的结果（含 L0 被跳过的语句）。"""
    results: list[ParseResult] = []
    for statement in split_statements(sql):
        if not is_interesting(statement):
            continue  # 预筛掉了，不产生结果也不报错
        results.append(parse_statement(statement, dialect=dialect, default_schema=default_schema))
    return results


# ---------------------------------------------------------------------------
# OpenLineage 互操作
# ---------------------------------------------------------------------------
def map_openlineage_column_lineage(facet: dict, *, downstream_dataset: str) -> list[ColumnEdge]:
    """把 OpenLineage 的 columnLineage facet 转成平台的边。

    ⚠️ 方向反转（docs/08 §7.1）：OL 的 `fields` 是 **输出列 → 输入列**
    （`{"<输出列>": {"inputFields": [{"namespace","name","field"}]}}`），
    而平台约定 from=上游、to=下游。这是接入时最容易搞错的一步。
    """
    edges: list[ColumnEdge] = []
    fields = (facet or {}).get("fields") or {}
    for out_col, spec in fields.items():
        for inp in (spec or {}).get("inputFields") or []:
            upstream_dataset = inp.get("name") or ""
            upstream_col = inp.get("field") or ""
            if not upstream_dataset or not upstream_col:
                continue
            transforms = inp.get("transformations") or []
            ttype = (transforms[0].get("type") if transforms else "DIRECT") or "DIRECT"
            masking = any(
                (t.get("subtype") or "").lower().startswith("mask") for t in transforms
            )
            transform = "MASKED" if masking else ("DIRECT" if ttype.upper() == "DIRECT" else "INDIRECT")
            edges.append(
                ColumnEdge(
                    from_table=upstream_dataset,
                    from_column=upstream_col,
                    to_table=downstream_dataset,
                    to_column=out_col,
                    transform=transform,
                    dependency_kind="VALUE",
                    parse_level="exact",
                    confidence=0.95,          # 运行时上报置信度最高
                )
            )
    return edges


def sqlglot_version() -> str:
    return getattr(sqlglot, "__version__", "unknown")


__all__ = [
    "CARDINALITY_EXPANDING",
    "ColumnEdge",
    "ParseResult",
    "SUPPORTED_DIALECTS",
    "is_interesting",
    "map_openlineage_column_lineage",
    "parse_sql",
    "parse_statement",
    "split_statements",
    "sqlglot_version",
]
