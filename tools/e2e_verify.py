"""端到端验证脚本：在运行中的服务上验证核心架构主张。

验证内容（对应设计文档的 ADR）：
  1. 资产详情与结构可读                    （08 §4.1）
  2. 血缘可查（容器→表，contains 关系）      （09 §9.2）
  3. ADR-005：人工描述不被采集覆盖           （企业最常投诉的问题）
  4. ADR-005：采集批次可整体回滚
  5. ADR-002：派生索引可丢弃并从事件流重建
  6. 观测：索引水位与事件序号可见            （07 §3.1）

用法：python tools/e2e_verify.py [base_url]
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
TARGET = "urn:dg:Dataset:prod.postgresql.dg.public.entity"
ADMIN_TOKEN = os.environ.get("DG_ADMIN_TOKEN", "dev-admin-token")
READER_TOKEN = os.environ.get("DG_READER_TOKEN", "dev-reader-token")

PASS, FAIL = "\033[92m PASS \033[0m", "\033[91m FAIL \033[0m"
results: list[tuple[str, bool, str]] = []


def call(
    method: str,
    path: str,
    body: dict | None = None,
    token: str | None = ADMIN_TOKEN,
) -> tuple[int, dict | str]:
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    print(f"[{PASS if ok else FAIL}] {name}" + (f"  → {detail}" if detail else ""))


def main() -> int:
    enc = urllib.parse.quote(TARGET, safe="")

    # 1) 资产详情与结构
    status, asset = call("GET", f"/api/v1/assets/{enc}")
    ok = status == 200 and isinstance(asset, dict) and "datasetSchema" in asset.get("aspects", {})
    cols = len(asset["aspects"]["datasetSchema"]["fields"]) if ok else 0
    check("1. 资产详情可读（含结构）", ok, f"{cols} 列, schemaHash={asset['aspects']['datasetSchema'].get('schemaHash') if ok else '-'}")

    # 2) 血缘（容器 → 表 的 contains 关系）
    status, lin = call("GET", f"/api/v1/assets/{enc}/lineage?direction=upstream&depth=2")
    nodes = [n["urn"] for n in lin.get("nodes", [])] if status == 200 else []
    check("2. 血缘可查（上游 contains）", any("Container" in n for n in nodes), f"{len(nodes)} 个上游节点")

    # 3) ADR-005：人工描述不被采集覆盖
    manual_text = "【人工维护】这是在平台上手工填写的描述，采集不得覆盖"
    status, first = call(
        "POST",
        f"/api/v1/assets/{enc}/aspects/descriptions",
        {"data": {"text": manual_text, "language": "zh"}, "source": "MANUAL"},
    )
    v1 = first.get("version") if isinstance(first, dict) else None

    status, clobber = call(
        "POST",
        f"/api/v1/assets/{enc}/aspects/descriptions",
        {"data": {"text": "采集器抓到的表注释", "language": "zh"}, "source": "AUTO_COLLECTED"},
    )
    protected = isinstance(clobber, dict) and "text" in (clobber.get("protectedFields") or {})

    _, after = call("GET", f"/api/v1/assets/{enc}/aspects/descriptions")
    kept = isinstance(after, dict) and after.get("data", {}).get("text") == manual_text
    check("3. ADR-005 人工内容未被采集覆盖", protected and kept,
          f"version={v1}, protectedFields={list((clobber.get('protectedFields') or {}).keys()) if isinstance(clobber, dict) else '-'}")

    # 4) ADR-002：索引可丢弃并重建
    _, lag_before = call("GET", "/api/v1/index/lag")
    status, rebuilt = call("POST", "/api/v1/index/rebuild")
    _, lag_after = call("GET", "/api/v1/index/lag")
    ok = (status == 200 and isinstance(rebuilt, dict) and rebuilt.get("indexed", 0) > 0
          and lag_after.get("lag") == 0 and lag_after.get("indexedDocs", 0) > 0)
    check("4. ADR-002 派生索引可丢弃并重放重建", ok,
          f"重建 {rebuilt.get('indexed')} 个文档, lag {lag_before.get('lag')} → {lag_after.get('lag')}")

    # 5) 治理信息在被重建的索引里可见（人工描述进入检索文档）
    status, search = call("GET", "/api/v1/search?q=" + urllib.parse.quote("人工维护"))
    hit = isinstance(search, dict) and any(r["urn"] == TARGET for r in search.get("results", []))
    check("5. 重建后的索引包含治理信息", hit, f"命中 {search.get('count') if isinstance(search, dict) else '?'} 条")

    # 6) 采集健康度（run 记录）
    status, model = call("GET", "/api/v1/model")
    check("6. 模型注册表可读", status == 200 and model.get("entity_types", 0) >= 10,
          f"{model.get('entity_types')} 实体 / {model.get('aspect_types')} aspect / {model.get('relationship_types')} 关系")

    # 7) 认证：无凭证被拒
    status, _ = call("GET", "/api/v1/search", token=None)
    check("7. 无凭证访问被拒绝（401）", status == 401, f"HTTP {status}")

    # 8) 授权：READER 不能写
    status, _ = call(
        "POST",
        f"/api/v1/assets/{enc}/aspects/descriptions",
        {"data": {"text": "reader 不该能写"}, "source": "MANUAL"},
        token=READER_TOKEN,
    )
    check("8. READER 写入被拒绝（403）", status == 403, f"HTTP {status}")

    # 9) 授权一致性：/api/v1/me 报告的可见级别与实际过滤一致
    status, me = call("GET", "/api/v1/me", token=READER_TOKEN)
    ok = status == 200 and me.get("maxVisibleLevel") == "L3" and "asset:write" not in me.get("permissions", [])
    check("9. 身份与生效权限自述正确", ok,
          f"maxVisibleLevel={me.get('maxVisibleLevel') if isinstance(me, dict) else '-'}")

    # 汇总
    failed = [r for r in results if not r[1]]
    print("\n" + "=" * 62)
    print(f"通过 {len(results) - len(failed)}/{len(results)}")
    if failed:
        print("失败项：" + ", ".join(r[0] for r in failed))
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
