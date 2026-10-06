"""多源连接器（组 19-22、38）的领域步骤。

涵盖 ClickHouse / MongoDB / Superset / BigQuery / dbt manifest 五类连接器。断言与
``tools/java_e2e_verify.py`` 的 Batch 3（行 806-932）与 dbt 组（行 1785-1850）一致，不强不弱。

外部基础设施（127.0.0.1:8123 / :27018 / :18089）缺失时由 conftest 依 SKIP_PROBES 追加
skip（原因含精确 host:port）；BigQuery 与 dbt 不跳过。每个场景在「假如」里幂等准备前置
（真实采集一次），因此可独立重跑。
"""

from __future__ import annotations

import json

from pytest_bdd import given, parsers, then, when

from support import config
from support.client import quote_urn

# --------------------------------------------------------------- 常量与工具

_MANIFEST = config.REPO_ROOT / "tools" / "fixtures" / "dbt" / "manifest.json"
_DBT_DSN = "dbt:///" + str(_MANIFEST).replace("\\", "/")
_DBT_PROJECT = "dg_demo"
_DBT_MODEL_SCHEMA = "public"

# 与验证脚本 connectors 字典一致（行 809-815）
_CLICKHOUSE_DSN = "clickhouse://default:@127.0.0.1:8123"
_MONGODB_DSN = "mongodb://127.0.0.1:27018"
_SUPERSET_DSN = "superset://admin:admin@127.0.0.1:18089"
_SUPERSET_METADB_DSN = "postgresql://superset:superset@127.0.0.1:15434/superset"

# java_e2e 的物理 PostgreSQL（dbt source 解析落点所需）
_E2E_POSTGRES_DSN = "postgresql://postgres:root@localhost:25011/dg"


def _brief(body) -> str:
    text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
    return text[:220]


def _dbt_body(ns: str) -> dict:
    return {"source": "dbt", "dsn": _DBT_DSN, "namespace": ns}


def _dbt_model_urn(world, model: str) -> str:
    return (f"urn:dg:Dataset:{world.namespace}.dbt."
            f"{_DBT_PROJECT}.{_DBT_MODEL_SCHEMA}.{model}")


def _collect(world, api, body: dict):
    """POST /api/v1/collect/run，把结果写入 world（供通用断言复用）。"""
    status, payload = api.call("POST", "/api/v1/collect/run", body, token=config.ADMIN_TOKEN)
    world.calls.append(("POST", "/api/v1/collect/run", status))
    world.last_status = status
    world.last_body = payload
    return status, payload


def _require_succeeded(status, payload, what: str) -> None:
    assert status == 200 and isinstance(payload, dict), (
        f"{what}采集未返回结构化结果：HTTP {status} {_brief(payload)}"
    )
    assert payload.get("status") == "SUCCEEDED", (
        f"{what}采集状态应为 SUCCEEDED，实际 {payload.get('status')}：{_brief(payload)}"
    )


def _first_asset_detail(world, api, prefix: str, urn_suffix: str | None = None) -> str:
    path = f"/api/v1/assets?prefix={prefix}&limit=25"
    status, listing = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    assert status == 200 and isinstance(listing, dict), (
        f"资产列表不可读：HTTP {status} {_brief(listing)}"
    )
    assets = listing.get("assets") or []
    if urn_suffix is not None:
        urn = next((a.get("urn") for a in assets
                    if str(a.get("urn", "")).endswith(urn_suffix)), None)
    else:
        urn = assets[0].get("urn") if assets else None
    assert urn, f"未找到目标资产（prefix={prefix}，后缀={urn_suffix}）：{_brief(listing)}"
    detail_path = f"/api/v1/assets/{quote_urn(urn)}"
    status, detail = api.call("GET", detail_path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", detail_path, status))
    world.last_status = status
    world.last_body = detail
    world.remember("asset_urn", urn)
    return urn


# ======================================================================= 前置


@given(parsers.parse("我已把 {ns} 命名空间的 PostgreSQL 物理表采集入库"))
def _collect_postgres_physical(world, api, ns: str) -> None:
    status, payload = _collect(world, api, {
        "source": "postgres", "dsn": _E2E_POSTGRES_DSN, "namespace": ns, "schemas": ["public"]})
    _require_succeeded(status, payload, "PostgreSQL")


@given(parsers.parse("我已采集 ClickHouse 命名空间 {ns} 的 {db} 库"))
def _collect_clickhouse(world, api, ns: str, db: str) -> None:
    status, payload = _collect(world, api, {
        "source": "clickhouse", "dsn": _CLICKHOUSE_DSN, "namespace": ns, "databases": [db]})
    _require_succeeded(status, payload, "ClickHouse")


@given(parsers.parse("我已采集 MongoDB 命名空间 {ns} 的 {db} 库"))
def _collect_mongodb(world, api, ns: str, db: str) -> None:
    status, payload = _collect(world, api, {
        "source": "mongodb", "dsn": _MONGODB_DSN, "namespace": ns,
        "databases": [db], "sampleSize": 200})
    _require_succeeded(status, payload, "MongoDB")


@given(parsers.parse("我已把命名空间 {ns} 的 Superset 仪表板采集入库"))
def _collect_superset(world, api, ns: str) -> None:
    # 先采集 Superset 自身元数据库：为报表血缘提供数据集落点。失败不致命（仅 21c 需要它）
    _collect(world, api, {"source": "postgres", "dsn": _SUPERSET_METADB_DSN,
                          "namespace": ns, "schemas": ["public"]})
    status, payload = _collect(world, api, {
        "source": "superset", "dsn": _SUPERSET_DSN, "namespace": ns, "reconcileOrphans": True})
    _require_succeeded(status, payload, "Superset")
    assert payload.get("dashboardsSeen", 0) > 0, f"没有采到仪表板：{_brief(payload)}"
    world.remember("superset_run", payload)
    # 再从资产里取与数据集连上的样例仪表板（供 21c 直接使用）
    _scan_dashboards(world, api, ns)


@given(parsers.parse("我已把 dbt manifest 采集到 {ns} 命名空间"))
def _collect_dbt_given(world, api, ns: str) -> None:
    status, payload = _collect(world, api, _dbt_body(ns))
    _require_succeeded(status, payload, "dbt")
    world.remember("dbt_run", payload)


@when(parsers.parse("我把 dbt 夹具 manifest 采集到 {ns} 命名空间"))
def _collect_dbt_when(world, api, ns: str) -> None:
    status, payload = _collect(world, api, _dbt_body(ns))
    _require_succeeded(status, payload, "dbt")
    world.remember("dbt_run", payload)


# ================================================================ 19a 清单


@then(parsers.parse("连接器清单应声明已实现 {ids}"))
def _assert_implemented(world, ids: str) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    implemented = body.get("implemented") or []
    present = {item.get("id") for item in implemented if isinstance(item, dict)}
    wanted = [part for part in ids.split("/") if part]
    missing = [part for part in wanted if part not in present]
    assert not missing, f"连接器清单缺少已实现项 {missing}：{_brief(body)}"


@then(parsers.parse("连接器清单中 {cid} 标注「对真实系统验证过」应为 {expected}"))
def _assert_verified_flag(world, cid: str, expected: str) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    implemented = body.get("implemented") or []
    item = next((row for row in implemented
                 if isinstance(row, dict) and row.get("id") == cid), None)
    assert item is not None, f"连接器清单中没有 {cid}：{_brief(body)}"
    want = expected.strip().lower() in ("true", "是", "1")
    actual = bool(item.get("verifiedAgainstRealSystem"))
    assert actual is want, (
        f"{cid}.verifiedAgainstRealSystem：期望 {want}，实际 {actual}：{_brief(item)}"
    )


# ============================================================= 19c ClickHouse


@when(parsers.parse("我取回 {ns} 命名空间下首个 ClickHouse 资产的详情"))
def _clickhouse_detail(world, api, ns: str) -> None:
    _first_asset_detail(world, api, f"urn:dg:Dataset:{ns}")


# ================================================== 20b/20c MongoDB 推断质量


@when(parsers.parse("我取回 {ns} 命名空间下 {name} 集合资产的详情"))
def _mongo_collection_detail(world, api, ns: str, name: str) -> None:
    _first_asset_detail(world, api, f"urn:dg:Dataset:{ns}", urn_suffix=f".{name}")


def _mongo_fields(world) -> list:
    detail = world.last_body if isinstance(world.last_body, dict) else {}
    fields = ((detail.get("aspects") or {}).get("datasetSchema") or {}).get("fields")
    assert isinstance(fields, list) and fields, f"集合资产缺少推断出的字段：{_brief(detail)}"
    return fields


@then("MongoDB 推断结果应含下钻出的嵌套点号路径字段")
def _assert_mongo_nested(world) -> None:
    fields = _mongo_fields(world)
    nested = [f.get("name") for f in fields if "." in str(f.get("name", ""))]
    assert nested, f"没有嵌套下钻出的点号路径字段：{[f.get('name') for f in fields]}"


@then("MongoDB 推断结果应显式暴露类型不稳定的字段")
def _assert_mongo_mixed(world) -> None:
    fields = _mongo_fields(world)
    mixed = {f.get("name"): f.get("type") for f in fields if "mixed" in str(f.get("type"))}
    assert mixed, (
        f"没有显式暴露类型不稳定（mixed(...)）的字段："
        f"{ {f.get('name'): f.get('type') for f in fields} }"
    )


@then("MongoDB 稀疏字段应被标注覆盖度")
def _assert_mongo_sparse(world) -> None:
    fields = _mongo_fields(world)
    sparse = [f.get("name") for f in fields if "稀疏" in str(f.get("description", ""))]
    assert sparse, (
        f"没有标注为稀疏（覆盖度）的字段："
        f"{ {f.get('name'): f.get('description') for f in fields} }"
    )


@then(parsers.parse("MongoDB 的 {field} 字段应被判为可空"))
def _assert_mongo_nullable(world, field: str) -> None:
    fields = {f.get("name"): f for f in _mongo_fields(world)}
    target = fields.get(field)
    assert target is not None, f"推断结果中没有 {field} 字段：{sorted(fields)}"
    assert target.get("nullable") is True, (
        f"{field} 应被判为可空（覆盖「字段缺失」与「出现过 null」）：{_brief(target)}"
    )


# ==================================================== 21b/21c Superset BI 血缘


@when(parsers.parse("我汇总 {ns} 命名空间下仪表板的图表清单与数据集依赖"))
def _aggregate_dashboards(world, api, ns: str) -> None:
    _scan_dashboards(world, api, ns)


def _scan_dashboards(world, api, ns: str) -> None:
    prefix = f"urn:dg:Dashboard:{ns}"
    path = f"/api/v1/assets?prefix={prefix}&limit=25"
    status, listing = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    assert status == 200 and isinstance(listing, dict), (
        f"仪表板列表不可读：HTTP {status} {_brief(listing)}"
    )
    dashboards = listing.get("assets") or []
    with_charts = 0
    sample = None
    for row in dashboards:
        detail_path = f"/api/v1/assets/{quote_urn(row['urn'])}"
        dstatus, detail = api.call("GET", detail_path, token=config.ADMIN_TOKEN)
        world.calls.append(("GET", detail_path, dstatus))
        if dstatus != 200 or not isinstance(detail, dict):
            continue
        spec = (detail.get("aspects") or {}).get("dashboardSpec") or {}
        if spec.get("chartCount", 0) > 0:
            with_charts += 1
        if spec.get("datasetUrns") and sample is None:
            sample = (row["urn"], spec)
    world.remember("dashboard_count", len(dashboards))
    world.remember("dashboards_with_charts", with_charts)
    world.remember("sample_dashboard", sample)
    world.last_status = status
    world.last_body = listing


@then(parsers.parse("{ns} 命名空间应至少有 {minimum:d} 个仪表板带图表清单"))
def _assert_dashboards_with_charts(world, ns: str, minimum: int) -> None:
    total = world.recall("dashboard_count")
    with_charts = world.recall("dashboards_with_charts")
    assert total, f"{ns} 命名空间没有采到仪表板"
    assert with_charts is not None and with_charts >= minimum, (
        f"{total} 个仪表板中仅 {with_charts} 个含图表清单（期望 >= {minimum}）"
    )


@when("我定位一个已连到数据集的仪表板并查询其数据集的下游血缘")
def _bi_lineage(world, api) -> None:
    sample = world.recall("sample_dashboard")
    assert sample, "没有仪表板成功连到数据集（检查 Superset 数据源是否为物理数据集）"
    dashboard_urn, spec = sample
    dataset_urn = spec["datasetUrns"][0]
    graph_path = (f"/api/v1/lineage/graph?urn={quote_urn(dataset_urn)}"
                  "&direction=downstream&depth=2")
    status, graph = api.call("GET", graph_path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", graph_path, status))
    impact_path = (f"/api/v1/lineage/impact/{quote_urn(dataset_urn)}"
                   "?direction=downstream&depth=3")
    status2, impact = api.call("GET", impact_path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", impact_path, status2))
    world.remember("bi_dashboard_urn", dashboard_urn)
    world.remember("bi_dataset_urn", dataset_urn)
    world.remember("bi_graph", graph)
    world.remember("bi_impact", impact)
    world.last_status = status
    world.last_body = graph


@then("该仪表板应出现在其数据集的下游节点中")
def _assert_bi_downstream(world) -> None:
    graph = world.recall("bi_graph") or {}
    nodes = [node.get("urn") for node in (graph.get("nodes") or [])]
    dashboard_urn = world.recall("bi_dashboard_urn")
    assert dashboard_urn in nodes, (
        f"仪表板未出现在数据集下游（血缘方向应为 数据集→报表）：{nodes}"
    )


@then("影响分析应报告受影响的报表数大于 0")
def _assert_bi_impact(world) -> None:
    impact = world.recall("bi_impact") or {}
    assert impact.get("affectedCount", 0) > 0, (
        f"影响分析受影响报表数应为正（改这张表哪些看板受影响）：{_brief(impact)}"
    )


# ================================================================== 22 BigQuery


@then("BigQuery 缺凭据时应给出可读错误而不是静默空数据集")
def _assert_bigquery_credential_error(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    assert world.last_status == 200, (
        f"期望 HTTP 200（错误记在运行结果里），实际 {world.last_status}：{_brief(world.last_body)}"
    )
    assert body.get("status") == "FAILED", (
        f"缺凭据应判 FAILED 而不是静默成功：{_brief(body)}"
    )
    errors = " ".join(str(item) for item in (body.get("errors") or []))
    assert ("凭据" in errors) or ("credentials" in errors.lower()), (
        f"错误未指出凭据问题（不得静默返回空数据集）：{errors[:200]}"
    )


# ===================================================================== 38 dbt


@then("dbt 连接器应已登记且说明指向 manifest")
def _assert_dbt_registered(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    implemented = body.get("implemented") or []
    dbt = next((row for row in implemented
                if isinstance(row, dict) and row.get("id") == "dbt"), None)
    assert dbt is not None, f"dbt 未登记为已实现连接器：{_brief(body)}"
    assert "manifest" in str(dbt.get("note")), (
        f"dbt 说明未指向 manifest：{dbt.get('note')}"
    )
    not_implemented = body.get("notImplemented") or {}
    assert "dbt" not in (not_implemented if isinstance(not_implemented, dict) else {}), (
        "dbt 仍被列为未实现（notImplemented）"
    )


@then("dbt 采集应回报解析不到的上游依赖（未解析）")
def _assert_dbt_skip_notes(world) -> None:
    run = world.last_body if isinstance(world.last_body, dict) else {}
    notes = run.get("edgeSkipNotes") or []
    assert notes, (
        f"没有回报跳过的边（夹具里的缺失依赖未被检出）：{_brief(run)}"
    )
    assert any("未解析" in str(note) for note in notes), (
        f"跳过说明未指明「未解析」：{notes}"
    )


@when(parsers.parse("我取回 dbt 模型 {model} 的资产详情"))
def _dbt_model_detail(world, api, model: str) -> None:
    urn = _dbt_model_urn(world, model)
    path = f"/api/v1/assets/{quote_urn(urn)}"
    status, detail = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    world.last_status = status
    world.last_body = detail
    world.remember("dbt_model_urn", urn)


@then("dbt 模型应是独立资产且带列与描述")
def _assert_dbt_model_asset(world) -> None:
    detail = world.last_body if isinstance(world.last_body, dict) else {}
    assert world.last_status == 200, (
        f"dbt 模型资产不可读：HTTP {world.last_status} {_brief(world.last_body)}"
    )
    assert detail.get("entityType") == "Dataset", (
        f"dbt 模型应是 Dataset 实体，实际 {detail.get('entityType')}"
    )
    fields = ((detail.get("aspects") or {}).get("datasetSchema") or {}).get("fields") or []
    assert len(fields) >= 2, f"dbt 模型应带至少 2 列，实际 {len(fields)}：{_brief(detail)}"
    assert any(field.get("description") for field in fields), (
        f"dbt 模型的列应带描述：{[field.get('name') for field in fields]}"
    )


@when(parsers.parse("我查询 dbt 模型 {model} 的上游血缘子图"))
def _dbt_upstream_subgraph(world, api, model: str) -> None:
    urn = _dbt_model_urn(world, model)
    path = (f"/api/v1/lineage/subgraph?urn={quote_urn(urn)}"
            "&direction=upstream&depth=3&includeColumns=true")
    status, body = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    world.last_status = status
    world.last_body = body
    world.remember("dbt_upstream_body", body)


@when(parsers.parse("我查询 dbt 模型 {model} 的下游血缘子图"))
def _dbt_downstream_subgraph(world, api, model: str) -> None:
    urn = _dbt_model_urn(world, model)
    path = (f"/api/v1/lineage/subgraph?urn={quote_urn(urn)}"
            "&direction=downstream&depth=3")
    status, body = api.call("GET", path, token=config.ADMIN_TOKEN)
    world.calls.append(("GET", path, status))
    world.last_status = status
    world.last_body = body
    world.remember("dbt_downstream_body", body)


def _subgraph_urns(body) -> list:
    return [str(node.get("urn", "")) for node in ((body or {}).get("nodes") or [])]


@then(parsers.parse("dbt 模型的上游应包含已采集的物理表 {table}"))
def _assert_dbt_upstream_table(world, table: str) -> None:
    body = world.recall("dbt_upstream_body") or {}
    urns = _subgraph_urns(body)
    assert any(urn.endswith(f"postgresql.dg.public.{table}") for urn in urns), (
        f"上游未解析到已采集的物理表 {table}：{[urn.split('.')[-1] for urn in urns]}"
    )


@then(parsers.parse("dbt 模型的下游应包含物化出的物理表 {table}"))
def _assert_dbt_downstream_table(world, table: str) -> None:
    body = world.recall("dbt_downstream_body") or {}
    urns = _subgraph_urns(body)
    assert any(urn.endswith(f"postgresql.dg.public.{table}") for urn in urns), (
        f"下游未出现物化出的物理表 {table}：{[urn.split('.')[-1] for urn in urns]}"
    )


@then("dbt 血缘边应带来源 dbt_manifest 且置信度为 1.0")
def _assert_dbt_edge_provenance(world) -> None:
    body = world.recall("dbt_downstream_body") or {}
    edges = [edge for edge in (body.get("edges") or [])
             if edge.get("source") == "dbt_manifest"]
    assert edges, f"没有 source=dbt_manifest 的血缘边：{_brief(body)}"
    bad = [edge for edge in edges if abs(float(edge.get("confidence", 0)) - 1.0) > 1e-6]
    assert not bad, (
        f"以下 dbt 边置信度不是 1.0："
        f"{[(edge.get('fromUrn'), edge.get('confidence')) for edge in bad]}"
    )


@then("manifest 不存在时应给出可操作的错误并提示先 dbt compile")
def _assert_dbt_manifest_missing(world) -> None:
    body = world.last_body
    message = str(body.get("message", "")) if isinstance(body, dict) else str(body)
    errors = (" ".join(str(item) for item in (body.get("errors") or []))
              if isinstance(body, dict) else "")
    ok = (
        (world.last_status == 400 and "dbt compile" in message)
        or (isinstance(body, dict) and body.get("status") == "FAILED" and "dbt compile" in errors)
    )
    assert ok, (
        f"缺少可操作错误（应提示先 dbt compile）：HTTP {world.last_status} {_brief(body)}"
    )