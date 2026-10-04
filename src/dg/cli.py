"""dgctl：平台命令行（docs/09 §9.10 的开发者面）。

命令：
  dgctl doctor                     健康与 schema 检查
  dgctl init                       初始化数据库 schema
  dgctl model                      打印模型摘要（实体/aspect/关系）
  dgctl collect postgres --dsn ... 执行一次采集
  dgctl index rebuild|consume      派生索引重建 / 增量消费
  dgctl serve                      启动 API 与 UI
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from dg import __version__
from dg.config import REPO_ROOT, settings
from sqlalchemy import text


def _print(obj) -> None:
    print(json.dumps(obj, ensure_ascii=False, indent=2, default=str))


def cmd_lineage(args) -> int:
    """血缘：解析 SQL 入库 / 查看解析质量。"""
    from dg.db import session_scope
    from dg.model import ModelRegistry

    if args.action == "quality":
        from dg.lineage import parse_quality_report

        with session_scope() as session:
            report = parse_quality_report(session, limit=args.limit)
        _print(report)
        return 0

    if args.action == "parse":
        from dg.lineage import ingest_sql

        if args.sql:
            sql = args.sql
        elif args.file:
            sql = Path(args.file).read_text(encoding="utf-8")
        else:
            print("parse 需要 --sql 或 --file（也可用 - 从 stdin 读）", file=sys.stderr)
            return 2

        registry = ModelRegistry.load(settings.model_dir)
        with session_scope() as session:
            result = ingest_sql(
                session,
                registry,
                sql,
                dialect=args.dialect,
                namespace=args.namespace,
                actor="cli",
                record_samples=not args.no_samples,
            )
        _print(result.as_dict())
        return 0 if result.failed == 0 else 1

    print(f"未知动作：{args.action}", file=sys.stderr)
    return 2


def cmd_alerts(args) -> int:
    """采集告警：list / check / ack / channel-add。"""
    from dg.alerting import (
        acknowledge_alert,
        check_and_notify,
        list_alerts,
        load_channels,
        upsert_channel,
    )
    from dg.db import session_scope

    if args.action == "list":
        with session_scope() as session:
            rows = list_alerts(session, state=args.state, limit=args.limit)
        _print(
            {
                "count": len(rows),
                "state": args.state,
                "alerts": [
                    {
                        "id": r["id"],
                        "severity": r["severity"],
                        "rule": r["rule"],
                        "subject": r["subject"],
                        "title": r["title"],
                        "fireCount": r["fire_count"],
                        "lastFiredAt": r["last_fired_at"],
                        "acknowledgedBy": r["acknowledged_by"],
                    }
                    for r in rows
                ],
            }
        )
        return 0

    if args.action == "check":
        with session_scope() as session:
            result = check_and_notify(
                session,
                remind_interval_seconds=settings.alert_remind_interval,
                reopen_cooldown_seconds=settings.alert_reopen_cooldown,
                dry_run=args.dry_run,
            )
        _print(result)
        return 0

    if args.action == "ack":
        if args.alert_id is None:
            print("ack 需要 <alert_id>", file=sys.stderr)
            return 2
        with session_scope() as session:
            row = acknowledge_alert(session, args.alert_id, by=args.by or "cli")
        _print(row)
        return 0

    if args.action == "channel-add":
        if not args.name or not args.url:
            print("channel-add 需要 --name 与 --url", file=sys.stderr)
            return 2
        with session_scope() as session:
            row = upsert_channel(
                session,
                name=args.name,
                kind="webhook",
                format=args.format,
                config={"url": args.url},
                min_severity=args.min_severity,
            )
        _print(row)
        return 0

    if args.action == "channels":
        with session_scope() as session:
            channels = load_channels(session)
        _print(
            {
                "count": len(channels),
                "channels": [
                    {"name": c.name, "minSeverity": c.min_severity, "type": type(c).__name__}
                    for c in channels
                ],
            }
        )
        return 0

    print(f"未知动作：{args.action}", file=sys.stderr)
    return 2


def cmd_doctor(_args) -> int:
    from dg.db import healthcheck
    from dg.model import ModelError, ModelRegistry

    report: dict = {"version": __version__, "config": {"modelDir": str(settings.model_dir)}}
    try:
        report["database"] = healthcheck()
    except Exception as exc:
        report["database"] = {"error": str(exc)}
        _print(report)
        return 1

    try:
        registry = ModelRegistry.load(settings.model_dir)
        report["model"] = registry.summary()
    except ModelError as exc:
        report["model"] = {"error": str(exc)}
        _print(report)
        return 1

    required = {
        "entity", "aspect", "aspect_history", "edge",
        "event_log", "consumer_offset", "audit_log", "search_doc",
        "collect_run", "collector_state", "collect_schedule",
        "lineage_parse_sample", "alert_event", "alert_channel", "alert_dispatch_log",
    }
    from sqlalchemy import text

    from dg.db import engine

    with engine.connect() as conn:
        present = {
            r[0]
            for r in conn.execute(
                text("SELECT tablename FROM pg_tables WHERE schemaname='public'")
            )
        }
    missing = sorted(required - present)
    report["schema"] = {"present": sorted(present & required), "missing": missing}
    report["status"] = "ok" if not missing else "schema_incomplete"
    _print(report)
    return 0 if not missing else 1


def cmd_init(_args) -> int:
    from sqlalchemy import text

    from dg.db import engine

    sql_dir = REPO_ROOT / "sql"
    files = sorted(sql_dir.glob("*.sql"))
    if not files:
        print(f"找不到迁移脚本：{sql_dir}/*.sql", file=sys.stderr)
        return 1

    total = 0
    with engine.begin() as conn:
        for sql_file in files:
            statements = _split_sql(sql_file.read_text(encoding="utf-8"))
            for stmt in statements:
                conn.execute(text(stmt))
            total += len(statements)
            print(f"  ✓ {sql_file.name}（{len(statements)} 条语句）")
    print(f"schema 初始化完成：{len(files)} 个迁移文件、{total} 条语句")
    return 0


def _split_sql(script: str) -> list[str]:
    """按分号切分（本项目 SQL 不含函数体，简单切分足够）。"""
    out: list[str] = []
    buffer: list[str] = []
    for line in script.splitlines():
        if line.strip().startswith("--"):
            continue
        buffer.append(line)
        if ";" in line:
            chunk = "\n".join(buffer)
            for part in chunk.split(";"):
                if part.strip():
                    out.append(part.strip())
            buffer = []
    if "".join(buffer).strip():
        out.append("\n".join(buffer).strip())
    return out


def cmd_model(_args) -> int:
    from dg.model import ModelRegistry

    registry = ModelRegistry.load(settings.model_dir)
    _print(
        {
            **registry.summary(),
            "entityTypes": {
                name: list(e.aspects) for name, e in sorted(registry.entity_types.items())
            },
            "relationshipTypes": {
                name: {"category": r.category, "lineage": r.lineage}
                for name, r in sorted(registry.relationship_types.items())
            },
        }
    )
    return 0


def cmd_collect(args) -> int:
    from dg.collectors.base import run_collection
    from dg.collectors.guard import GuardConfig
    from dg.db import session_scope
    from dg.model import ModelRegistry

    if args.source == "postgres":
        from dg.collectors.postgres_source import PostgresSource

        source = PostgresSource(
            args.dsn,
            include_schemas=args.schemas.split(",") if args.schemas else None,
            include_tables=args.tables.split(",") if args.tables else None,
        )
    elif args.source == "sqlite":
        from dg.collectors.sqlite_source import SqliteSource

        source = SqliteSource(args.dsn)
    elif args.source == "duckdb":
        from dg.collectors.duckdb_source import DuckDbSource

        source = DuckDbSource(
            args.dsn,
            include_schemas=args.schemas.split(",") if args.schemas else None,
            include_tables=args.tables.split(",") if args.tables else None,
            files=args.files.split(",") if getattr(args, "files", None) else None,
        )
    else:
        print(f"暂不支持的源：{args.source}", file=sys.stderr)
        return 2

    guard = GuardConfig(
        max_deletions=args.max_deletions,
        max_delete_ratio=args.max_delete_ratio,
        min_retention_ratio=args.min_retention_ratio,
    )
    registry = ModelRegistry.load(settings.model_dir)
    with session_scope() as session:
        run = run_collection(
            session,
            registry,
            source,
            namespace=args.namespace,
            database=args.database,
            guard_config=guard,
            guard_enabled=not args.no_guard,
            accept_deletions=args.accept_deletions,
        )
    _print(run.as_dict())
    # 退出码：0 成功；3 被护栏拦截（需人工介入）；1 其他失败
    if run.status == "BLOCKED":
        return 3
    return 0 if run.ok else 1


def cmd_runs(args) -> int:
    """查看采集运行历史与健康度。"""
    from dg.collectors.guard import summarize_history
    from dg.db import session_scope

    with session_scope() as session:
        rows = session.execute(
            text(
                """
                SELECT run_id, source, namespace, scope, status, block_reason,
                       datasets_seen, datasets_created, deleted_candidates, deleted,
                       duration_ms, started_at, finished_at
                  FROM collect_run
                 ORDER BY started_at DESC
                 LIMIT :limit
                """
            ),
            {"limit": args.limit},
        ).mappings().all()

        state = session.execute(
            text(
                """
                SELECT source, namespace, scope, entity_count, last_status,
                       consecutive_failures, last_success_at
                  FROM collector_state ORDER BY source, namespace, scope
                """
            )
        ).mappings().all()

    history = [dict(r) for r in rows]
    if args.health:
        _print({"health": summarize_history(history), "state": [dict(s) for s in state]})
    else:
        _print(
            {
                "count": len(history),
                "runs": [
                    {
                        **{k: v for k, v in r.items() if k != "block_reason"},
                        **({"blockReason": r["block_reason"]} if r["block_reason"] else {}),
                    }
                    for r in history
                ],
                "state": [dict(s) for s in state],
            }
        )
    return 0


def cmd_index(args) -> int:
    from dg.consumers.search_index import consume_search_index, index_lag, rebuild_search_index
    from dg.db import session_scope

    with session_scope() as session:
        if args.action == "rebuild":
            stats = rebuild_search_index(session)
        else:
            stats = consume_search_index(session)
        lag = index_lag(session)
    _print(
        {
            "action": args.action,
            "processed": stats.processed,
            "indexed": stats.indexed,
            "deleted": stats.deleted,
            "toSeq": stats.to_seq,
            "lag": lag["lag"],
            "indexedDocs": lag["indexedDocs"],
        }
    )
    return 0


def cmd_codegen(args) -> int:
    from dg.codegen import GENERATORS, check_generated, generate_all
    from dg.model import ModelRegistry

    registry = ModelRegistry.load(settings.model_dir)
    root = Path(args.out) if args.out else REPO_ROOT

    if args.check:
        stale = check_generated(registry, root)
        if stale:
            print("生成物与模型定义不一致：", file=sys.stderr)
            for item in stale:
                print(f"  - {item}", file=sys.stderr)
            return 1
        print(f"生成物与模型定义一致（{len(GENERATORS)} 个目标）")
        return 0

    written = generate_all(registry, root)
    _print({target: str(path.relative_to(root)) for target, path in written.items()})
    return 0


def cmd_schedule(args) -> int:
    """采集调度：list / apply / run。"""
    from dg.db import session_scope
    from dg.model import ModelRegistry
    from dg.scheduler import (
        CollectionScheduler,
        list_schedules,
        load_schedules_from_yaml,
        upsert_schedule,
    )

    if args.action == "list":
        with session_scope() as session:
            rows = list_schedules(session)
        _print({"count": len(rows), "schedules": rows})
        return 0

    if args.action == "apply":
        if not args.file:
            print("apply 需要 -f <yaml>", file=sys.stderr)
            return 2
        schedules = load_schedules_from_yaml(args.file)
        with session_scope() as session:
            for sched in schedules:
                upsert_schedule(session, sched)
        _print({"applied": [s.name for s in schedules], "source": args.file})
        return 0

    if args.action == "run":
        if not args.name:
            print("run 需要 <name>", file=sys.stderr)
            return 2
        registry = ModelRegistry.load(settings.model_dir)
        scheduler = CollectionScheduler(registry)
        result = scheduler.run_now(
            args.name, actor="cli", accept_deletions=args.accept_deletions
        )
        _print(result)
        if result.get("skipped"):
            return 4
        return 0 if (result.get("run") or {}).get("status") == "SUCCEEDED" else 3

    print(f"未知动作：{args.action}", file=sys.stderr)
    return 2


def cmd_serve(args) -> int:
    import uvicorn

    from dg.model import ModelRegistry

    scheduler = None
    if args.with_scheduler:
        from dg.scheduler import CollectionScheduler

        registry = ModelRegistry.load(settings.model_dir)
        scheduler = CollectionScheduler(registry)
        loaded = scheduler.start()
        print(f"内置调度器已启动，装载 {loaded} 个调度", flush=True)

    try:
        uvicorn.run(
            "dg.api.app:app",
            host=args.host or settings.api_host,
            port=args.port or settings.api_port,
            reload=args.reload,
        )
    finally:
        if scheduler is not None:
            scheduler.shutdown()
    return 0


def cmd_sidecar(args) -> int:
    """启动 SQL 解析侧车（docs/10 §2：解析放 Python 侧，Java 控制面通过内部 HTTP 调用）。"""
    import uvicorn

    host = args.host or "127.0.0.1"
    port = args.port or 8099
    print(f"SQL 解析侧车启动：http://{host}:{port}（/healthz 探活，POST /api/v1/lineage/parse 解析）",
          flush=True)
    uvicorn.run("dg.lineage.sidecar:app", host=host, port=port, log_level="info")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="dgctl", description="通用数据治理平台 CLI")
    parser.add_argument("--version", action="version", version=__version__)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("doctor", help="健康与 schema 检查").set_defaults(func=cmd_doctor)
    sub.add_parser("init", help="初始化数据库 schema").set_defaults(func=cmd_init)
    sub.add_parser("model", help="打印模型摘要").set_defaults(func=cmd_model)

    p_collect = sub.add_parser("collect", help="执行一次采集")
    p_collect.add_argument("source", choices=["postgres", "sqlite", "duckdb"])
    p_collect.add_argument("--dsn", required=True, help="源系统连接串（sqlite/duckdb 为文件路径）")
    p_collect.add_argument("--namespace", default=settings.default_namespace)
    p_collect.add_argument("--database", default=None, help="覆盖数据库名")
    p_collect.add_argument("--schemas", default=None, help="只采集这些 schema（逗号分隔）")
    p_collect.add_argument("--tables", default=None, help="只采集这些表（逗号分隔）")
    p_collect.add_argument("--files", default=None,
                           help="duckdb：额外采集的裸文件（Parquet/CSV，逗号分隔）")
    p_collect.add_argument("--max-deletions", type=int, default=200,
                           help="单次采集允许的最大删除数（超过则拦截）")
    p_collect.add_argument("--max-delete-ratio", type=float, default=0.30,
                           help="单次采集允许的最大删除比例（超过则拦截）")
    p_collect.add_argument("--min-retention-ratio", type=float, default=0.70,
                           help="实体数保留率下限（低于则视为骤降并拦截）")
    p_collect.add_argument("--accept-deletions", action="store_true",
                           help="人工确认后接受删除（绕过护栏，并更新基线）")
    p_collect.add_argument("--no-guard", action="store_true",
                           help="完全关闭护栏（危险，仅用于首次全量对齐）")
    p_collect.set_defaults(func=cmd_collect)

    p_runs = sub.add_parser("runs", help="采集运行历史与健康度")
    p_runs.add_argument("--limit", type=int, default=20)
    p_runs.add_argument("--health", action="store_true", help="只输出健康度汇总")
    p_runs.set_defaults(func=cmd_runs)

    p_index = sub.add_parser("index", help="派生索引维护")
    p_index.add_argument("action", choices=["rebuild", "consume"])
    p_index.set_defaults(func=cmd_index)

    p_codegen = sub.add_parser("codegen", help="由模型定义生成代码（Python/TypeScript/JSON Schema）")
    p_codegen.add_argument("--check", action="store_true", help="CI 模式：校验生成物是否最新")
    p_codegen.add_argument("--out", default=None, help="输出根目录（默认仓库根）")
    p_codegen.set_defaults(func=cmd_codegen)

    p_serve = sub.add_parser("serve", help="启动 API 与 UI")
    p_serve.add_argument("--host", default=None)
    p_serve.add_argument("--port", type=int, default=None)
    p_serve.add_argument("--reload", action="store_true")
    p_serve.add_argument("--with-scheduler", action="store_true",
                         help="同时启动内置采集调度器")
    p_serve.set_defaults(func=cmd_serve)

    p_sched = sub.add_parser("schedule", help="采集调度管理")
    p_sched.add_argument("action", choices=["list", "apply", "run"])
    p_sched.add_argument("name", nargs="?", default=None, help="run 时的调度名")
    p_sched.add_argument("-f", "--file", default=None, help="apply 时的 YAML 文件")
    p_sched.add_argument("--accept-deletions", action="store_true")
    p_sched.set_defaults(func=cmd_schedule)

    p_sidecar = sub.add_parser("sidecar", help="启动 SQL 解析侧车（sqlglot，供 Java 控制面调用）")
    p_sidecar.add_argument("--host", default="127.0.0.1")
    p_sidecar.add_argument("--port", type=int, default=8099)
    p_sidecar.set_defaults(func=cmd_sidecar)

    p_lineage = sub.add_parser("lineage", help="列级血缘：解析 SQL 入库 / 解析质量")
    p_lineage.add_argument("action", choices=["parse", "quality"])
    p_lineage.add_argument("--sql", default=None, help="直接给 SQL 文本")
    p_lineage.add_argument("-f", "--file", default=None, help="从文件读 SQL")
    p_lineage.add_argument("--dialect", default="hive",
                           help="SQL 方言（hive/spark/trino/postgres/bigquery/doris…）")
    p_lineage.add_argument("--namespace", default=settings.default_namespace)
    p_lineage.add_argument("--no-samples", action="store_true", help="不写解析异常样本库")
    p_lineage.add_argument("--limit", type=int, default=20, help="quality 时的条数")
    p_lineage.set_defaults(func=cmd_lineage)

    p_alerts = sub.add_parser("alerts", help="采集告警：查看 / 巡检 / 确认 / 配置通道")
    p_alerts.add_argument(
        "action", choices=["list", "check", "ack", "channel-add", "channels"]
    )
    p_alerts.add_argument("alert_id", nargs="?", type=int, default=None, help="ack 时的告警 id")
    p_alerts.add_argument("--state", default="FIRING", choices=["FIRING", "RESOLVED"])
    p_alerts.add_argument("--limit", type=int, default=50)
    p_alerts.add_argument("--dry-run", action="store_true", help="只对账不投递")
    p_alerts.add_argument("--by", default=None, help="ack 时的操作者")
    p_alerts.add_argument("--name", default=None, help="channel-add 时的通道名")
    p_alerts.add_argument("--url", default=None, help="channel-add 时的 webhook 地址")
    p_alerts.add_argument(
        "--format", default="generic", choices=["generic", "slack", "feishu", "dingtalk", "teams"]
    )
    p_alerts.add_argument(
        "--min-severity", default="WARNING", choices=["INFO", "WARNING", "CRITICAL"]
    )
    p_alerts.set_defaults(func=cmd_alerts)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
