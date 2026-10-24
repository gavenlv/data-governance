"""嵌入资产卡端到端验证：短期令牌 + 字段白名单 + 拒绝面。

为什么单独验证：`/api/v1/embed/assets/**` 是平台**唯一不需要登录的数据端点**，
它的安全性完全依赖令牌签名与字段白名单。这类"公开端点"一旦回归，影响是直接的越权读取，
因此必须有可复现的检查，而不是只靠代码评审。

要求：控制面已启动，且设置了 DG_SECRET_KEY（否则签发返回 502，脚本会明确提示）。

用法：python tools/embed_verify.py [--base http://127.0.0.1:8081 --token dev-admin-token]
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

RESULTS: list[tuple[bool, str, str]] = []


def ok(name: str, detail: str = "") -> None:
    RESULTS.append((True, name, detail))
    print(f"PASS  {name}" + (f" —— {detail}" if detail else ""))


def fail(name: str, detail: str = "") -> None:
    RESULTS.append((False, name, detail))
    print(f"FAIL  {name}" + (f" —— {detail}" if detail else ""))


def call(method: str, url: str, token: str | None = None, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode("utf-8", "replace")
            return response.status, (json.loads(raw) if raw.startswith("{") else raw)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, raw


def first_asset(base: str, token: str) -> str | None:
    status, payload = call("GET", f"{base}/api/v1/assets?limit=1", token)
    if status == 200 and isinstance(payload, dict) and payload.get("assets"):
        return payload["assets"][0]["urn"]
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8081")
    parser.add_argument("--token", default="dev-admin-token")
    args = parser.parse_args()
    base = args.base.rstrip("/")

    urn = first_asset(base, args.token)
    if urn is None:
        print("平台上没有资产，无法验证嵌入卡（先跑一次采集）")
        return 2

    # 1) 签发需要平台登录
    status, _ = call("POST", f"{base}/api/v1/embed/token", None, {"urn": urn})
    if status == 401:
        ok("签发嵌入令牌需要平台登录", "无令牌 → 401")
    else:
        fail("签发嵌入令牌需要平台登录", f"实际 {status}")

    # 2) 正常签发
    status, minted = call("POST", f"{base}/api/v1/embed/token", args.token, {"urn": urn})
    if status == 200 and isinstance(minted, dict) and minted.get("token"):
        ok("签发嵌入令牌", f"ttl={minted.get('ttlSeconds')}s")
    elif status == 502:
        fail("签发嵌入令牌", "控制面未配置 DG_SECRET_KEY：嵌入令牌必须签名，不能降级（这是预期行为）")
        return 1
    else:
        fail("签发嵌入令牌", f"实际 {status}: {minted}")
        return 1

    token = minted["token"]
    card_url = f"{base}/api/v1/embed/assets/{urllib.parse.quote(urn, safe='')}"

    # 3) 凭令牌读取，并检查字段白名单
    status, card = call("GET", f"{card_url}?t={urllib.parse.quote(token)}")
    if status == 200 and isinstance(card, dict):
        ok("凭令牌读取资产卡", f"{card.get('displayName')} / {card.get('classification')}")
        required = {"urn", "displayName", "entityType", "classification", "owners", "links", "notice"}
        missing = required - set(card)
        if not missing:
            ok("卡片包含必要字段", ", ".join(sorted(required)))
        else:
            fail("卡片包含必要字段", f"缺 {sorted(missing)}")
        forbidden = {"aspects", "datasetSchema", "connection", "versions", "audit"}
        leaked = forbidden & set(card)
        if not leaked:
            ok("卡片不含敏感字段（schema/连接/版本/审计）")
        else:
            fail("卡片不含敏感字段", f"泄漏 {sorted(leaked)}")
        body = json.dumps(card, ensure_ascii=False)
        # 检查的是"字段结构"与"连接信息"这类内容，而不是 schema 这个词本身：
        # 卡片里出现"不包含字段结构"的说明文字是正常的，用词面匹配会误报
        if "datasetSchema" not in body and '"aspects"' not in body and "connection" not in body:
            ok("卡片正文不含字段结构与连接信息")
        else:
            fail("卡片正文不含字段结构与连接信息", "字段白名单被绕过")
    else:
        fail("凭令牌读取资产卡", f"实际 {status}: {card}")

    # 4) 无令牌 → 401
    status, _ = call("GET", card_url)
    if status == 401:
        ok("无令牌读取 → 401")
    else:
        fail("无令牌读取 → 401", f"实际 {status}")

    # 5) 篡改令牌 → 401
    tampered = token[:-2] + ("AA" if not token.endswith("AA") else "BB")
    status, _ = call("GET", f"{card_url}?t={urllib.parse.quote(tampered)}")
    if status == 401:
        ok("篡改令牌 → 401")
    else:
        fail("篡改令牌 → 401", f"实际 {status}")

    # 6) 令牌绑定单个资产：换一个资产读同一令牌 → 401
    status, payload = call("GET", f"{base}/api/v1/assets?limit=2", args.token)
    other = None
    if status == 200 and isinstance(payload, dict):
        assets = payload.get("assets") or []
        other = next((item["urn"] for item in assets if item["urn"] != urn), None)
    if other:
        other_url = f"{base}/api/v1/embed/assets/{urllib.parse.quote(other, safe='')}?t={urllib.parse.quote(token)}"
        status, _ = call("GET", other_url)
        if status == 401:
            ok("令牌只能读签发时那一个资产", "跨资产复用 → 401")
        else:
            fail("令牌跨资产复用", f"实际 {status}")
    else:
        print("SKIP  令牌绑定单个资产 —— 平台只有一个资产，无法构造跨资产用例")

    failed = [item for item in RESULTS if not item[0]]
    print(f"\n共 {len(RESULTS)} 项检查：{len(RESULTS) - len(failed)} 通过 / {len(failed)} 失败")
    for _, name, detail in failed:
        print(f"  - {name}: {detail}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
