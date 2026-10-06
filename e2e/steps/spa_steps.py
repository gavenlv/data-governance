"""SPA 托管（组 23）的领域步骤：HTTP 层面确认界面外壳与构建产物被服务出去，
并用无头 Chrome 深链渲染各标签页、断言关键文案真的出现在 DOM 里。"""

from __future__ import annotations

import re
import shutil
import subprocess
import tempfile
from pathlib import Path

import pytest
from pytest_bdd import parsers, then, when

_ROOT_MARK = 'id="root"'
_BUNDLE_RE = re.compile(r"/assets/(index-[A-Za-z0-9_-]+\.js)")

# 无头渲染：候选浏览器与逐标签页关键文案（与 tools/ui_render_check.py 的 CASES 保持一致）。
_CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome Dev\Application\chrome.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
]

_TAB_NEEDLES: dict[str, list[str]] = {
    "/observability?tab=anomalies": [
        "误报率是第一优先级指标", "运行一次扫描", "检测记录", "从最近 24h 异常开事故",
    ],
    "/observability?tab=slos": [
        "达成率来自平台的实际数据", "定义 / 更新 SLO", "错误预算",
    ],
    "/observability?tab=incidents": [
        "平均 MTTR", "事故列表", "开新事故（影响面自动算）", "已解决但没有沉淀规则的事故",
    ],
    "/ai?tab=inbox": [
        "建议收件箱", "采纳率（判断 AI 有没有用的唯一口径）", "运行确定性生成器", "大模型生成",
    ],
    "/ai?tab=search": [
        "两路召回 + RRF 融合", "向量召回未实现", "索引文档",
    ],
    "/ai?tab=metrics": [
        "指标是「口径」的载体", "接入语义层定义", "指标清单",
    ],
    "/ai?tab=mcp": [
        "Agent 不走后门", "我当前可用的工具", "试调一个工具", "按权限隐藏",
    ],
    "/admin?tab=edge": [
        "推模式的协议与控制面已实现", "已注册的 Agent", "上报记录",
    ],
    "/governance?tab=engine": [
        "引擎审计", "被访问但从未被批准", "接入情况", "原始记录",
    ],
    "/admin?tab=capabilities": [
        "能力清单", "部分实现",
    ],
}


@when(parsers.parse("我依次以匿名令牌访问 SPA 路由 {first} 与 {second}"))
def _visit_routes(world, api, first: str, second: str) -> None:
    seen = []
    for route in (first, second):
        status, body = api.call("GET", route, token=None)
        world.calls.append(("GET", route, status))
        seen.append((route, status, body))
    world.remember("spa_routes", seen)


@then("两个路由都应返回 SPA 外壳（含 id=\"root\"）")
def _routes_shell(world) -> None:
    seen = world.recall("spa_routes") or []
    bad = [(route, status) for route, status, body in seen
           if status != 200 or not isinstance(body, str) or _ROOT_MARK not in body]
    assert not bad, f"以下路由未返回 SPA 外壳：{bad}"
    world.last_body = seen[-1][2] if seen else None
    world.last_status = seen[-1][1] if seen else None


@then("首页应引用构建产物 /assets/index-*.js")
def _bundle_referenced(world) -> None:
    body = world.last_body if isinstance(world.last_body, str) else ""
    match = _BUNDLE_RE.search(body)
    assert match is not None, "首页未引用 /assets/index-*.js（构建产物没有被服务出去？）"
    world.remember("bundle", match.group(1))


def _find_chrome() -> str | None:
    for candidate in _CHROME_CANDIDATES:
        if Path(candidate).exists():
            return candidate
    return shutil.which("chrome") or shutil.which("chromium") or shutil.which("msedge")


def _render(chrome: str, url: str, budget_ms: int = 9000) -> str:
    """用无头 Chrome 打开 URL 并 dump 最终 DOM（--virtual-time-budget 等渲染期异常暴露）。"""
    profile = tempfile.mkdtemp(prefix="dg_chrome_")
    try:
        proc = subprocess.run(
            [chrome, "--headless=new", "--disable-gpu", "--no-sandbox",
             f"--user-data-dir={profile}", f"--virtual-time-budget={budget_ms}",
             "--dump-dom", url],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120,
        )
        return proc.stdout or ""
    finally:
        shutil.rmtree(profile, ignore_errors=True)


@when(parsers.parse("我用无头浏览器渲染页面 {path}"))
def _render_deep_link(world, base_url, path: str) -> None:
    chrome = _find_chrome()
    if chrome is None:
        pytest.skip(
            "找不到无头 Chrome/Edge，无法渲染 SPA 标签页；候选路径："
            + "、".join(_CHROME_CANDIDATES)
            + "（可用 DG_CHROME 指定）"
        )
    url = base_url + path
    dom = _render(chrome, url)
    world.remember("chrome_render", {"path": path, "url": url, "dom": dom})


@then("该标签页的关键文案应全部出现在渲染后的 DOM 中")
def _tab_needles_rendered(world) -> None:
    ctx = world.recall("chrome_render") or {}
    path = ctx.get("path", "")
    dom = ctx.get("dom", "")
    needles = _TAB_NEEDLES.get(path)
    assert needles is not None, f"未登记渲染路径 {path} 的关键文案"
    assert dom, f"{path} 渲染后的 DOM 为空（标签页可能白屏）"
    missing = [needle for needle in needles if needle not in dom]
    assert not missing, (
        f"{path} 渲染后缺失关键文案：{missing}（DOM {len(dom)} 字符）"
    )