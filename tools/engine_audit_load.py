"""引擎审计日志参考采集器（能力 policy.engine-audit-ingest 的部署侧半边）。

平台侧只接受**推送**（`POST /api/v1/access/engine-audit`），因为各企业日志的落盘格式、
位置、轮转策略差异极大，属于部署环境配置。这个脚本就是"参考采集器"：
把常见的 JSONL / CSV 查询日志转成平台的审计记录格式并分批推送。

它刻意做得很薄 —— 只做三件事：读文件、字段改名、分批推送。
**不做**的事情同样重要：不解析 SQL 文本（那是血缘侧的事）、不猜测缺失字段（缺时间/主体/资源
的记录会被平台拒收并说明缺什么，而不是被悄悄补一个默认值）。

用法：
  # JSONL（每行一个 JSON 对象）
  python tools/engine_audit_load.py --engine trino --namespace prod --file trino-queries.jsonl

  # CSV（表头即字段名；支持 user/queryId/table/columns 等别名，由平台侧归一）
  python tools/engine_audit_load.py --engine warehouse --namespace prod --file warehouse.csv --format csv

  # 先看会推什么、不真的推
  python tools/engine_audit_load.py --engine ranger --namespace prod --file audit.json --dry-run
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get("DG_BASE_URL", "http://127.0.0.1:8081")
TOKEN = os.environ.get("DG_API_TOKEN", "dev-admin-token")


def read_records(path: Path, fmt: str) -> list[dict]:
    text = path.read_text(encoding="utf-8-sig")
    if fmt == "jsonl":
        records = []
        for line_no, line in enumerate(text.splitlines(), start=1):
            line = line.strip()
            if not line:
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no} 不是合法 JSON：{exc}") from exc
            if isinstance(item, dict):
                records.append(item)
        return records
    if fmt == "json":
        payload = json.loads(text)
        if isinstance(payload, list):
            return [item for item in payload if isinstance(item, dict)]
        if isinstance(payload, dict):
            for key in ("records", "data", "queries", "events"):
                if isinstance(payload.get(key), list):
                    return [item for item in payload[key] if isinstance(item, dict)]
        raise SystemExit(f"{path}: JSON 顶层应为数组，或含 records/data/queries/events 数组")
    if fmt == "csv":
        with path.open(encoding="utf-8-sig", newline="") as handle:
            return [dict(row) for row in csv.DictReader(handle)]
    raise SystemExit(f"不支持的格式：{fmt}（jsonl / json / csv）")


def post(batch: list[dict], engine: str, namespace: str, dry_run: bool) -> dict:
    body = json.dumps({"engine": engine, "namespace": namespace, "records": batch},
                      ensure_ascii=False).encode("utf-8")
    if dry_run:
        return {"dryRun": True, "received": len(batch)}
    request = urllib.request.Request(BASE + "/api/v1/access/engine-audit", data=body,
                                     headers={"Content-Type": "application/json",
                                              "Authorization": f"Bearer {TOKEN}"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        raise SystemExit(f"推送失败 HTTP {exc.code}：{detail[:400]}") from exc


def main() -> int:
    parser = argparse.ArgumentParser(description="引擎审计日志参考采集器")
    parser.add_argument("--engine", required=True, choices=["trino", "ranger", "warehouse", "superset", "other"])
    parser.add_argument("--namespace", default="prod", help="平台命名空间（用于把表名解析成资产 URN）")
    parser.add_argument("--file", required=True, help="日志文件路径")
    parser.add_argument("--format", default=None, choices=["jsonl", "json", "csv"],
                        help="默认按扩展名推断")
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--dry-run", action="store_true", help="只统计不推送")
    args = parser.parse_args()

    path = Path(args.file)
    if not path.exists():
        raise SystemExit(f"找不到文件：{path}")
    fmt = args.format or {"jsonl": "jsonl", "ndjson": "jsonl", "json": "json",
                          "csv": "csv"}.get(path.suffix.lstrip(".").lower())
    if fmt is None:
        raise SystemExit(f"无法从扩展名推断格式（{path.suffix}），请用 --format 指定")

    records = read_records(path, fmt)
    if not records:
        raise SystemExit("日志文件里没有可用记录")

    batches = [records[i:i + args.batch_size] for i in range(0, len(records), args.batch_size)]
    totals = {"received": 0, "accepted": 0, "duplicated": 0, "unresolved": 0, "rejected": 0}
    rejected_samples: list[str] = []
    for batch in batches:
        result = post(batch, args.engine, args.namespace, args.dry_run)
        for key in totals:
            totals[key] += int(result.get(key, 0) or 0)
        rejected_samples.extend(result.get("rejectedSamples", []) or [])

    print(json.dumps({"file": str(path), "format": fmt, "batches": len(batches), **totals,
                      "rejectedSamples": rejected_samples[:5],
                      "note": "duplicated 是被幂等去重掉的记录（日志重放是常态，重复不是错误）；"
                              "unresolved 是没能解析到平台资产的记录（仍已入库，可用 coverage 接口查看）"},
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
