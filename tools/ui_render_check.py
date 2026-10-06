"""界面渲染冒烟：用无头 Chrome 逐个 Tab 渲染，检查关键内容真的出现在 DOM 里。

为什么需要它：antd 的 Tabs 默认懒挂载，只 dump 第一个 Tab 根本验证不到其余 Tab
（表格列写错、数据形状假设错都会在渲染期抛错，页面会变空白）。
这个脚本用 `?tab=` 深链逐个渲染，任何一处渲染期异常都会表现为关键文案缺失。

用法：python tools/ui_render_check.py [base_url]
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8081"

CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome Dev\Application\chrome.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
]

# 每个页面/标签页必须出现的关键文案（缺失 = 该标签页没渲染出来）
CASES: list[tuple[str, str, list[str]]] = [
    ("/observability?tab=anomalies", "可观测 / 异常检测",
     ["误报率是第一优先级指标", "运行一次扫描", "检测记录", "从最近 24h 异常开事故"]),
    ("/observability?tab=slos", "可观测 / SLO",
     ["达成率来自平台的实际数据", "定义 / 更新 SLO", "错误预算"]),
    ("/observability?tab=incidents", "可观测 / 事故",
     ["平均 MTTR", "事故列表", "开新事故（影响面自动算）", "已解决但没有沉淀规则的事故"]),
    ("/ai?tab=inbox", "AI / 建议收件箱",
     ["建议收件箱", "采纳率（判断 AI 有没有用的唯一口径）", "运行确定性生成器", "大模型生成"]),
    ("/ai?tab=search", "AI / 混合检索",
     ["两路召回 + RRF 融合", "向量召回未实现", "索引文档"]),
    ("/ai?tab=metrics", "AI / 语义层指标",
     ["指标是「口径」的载体", "接入语义层定义", "指标清单"]),
    ("/ai?tab=mcp", "AI / MCP 工具",
     ["Agent 不走后门", "我当前可用的工具", "试调一个工具", "按权限隐藏"]),
    ("/admin?tab=edge", "管理 / Edge Agent",
     ["推模式的协议与控制面已实现", "已注册的 Agent", "上报记录"]),
    ("/governance?tab=engine", "治理 / 引擎审计",
     ["引擎审计", "被访问但从未被批准", "接入情况", "原始记录"]),
    ("/lineage?tab=quality", "血缘 / 血缘质量（含 L2 检查）",
     ["血缘 L2 检查", "能靠采集补上", "需要改 SQL"]),
    ("/admin?tab=capabilities", "管理 / 能力清单", ["能力清单", "部分实现"]),
]


def find_chrome() -> str | None:
    for candidate in CHROME_CANDIDATES:
        if Path(candidate).exists():
            return candidate
    return shutil.which("chrome") or shutil.which("chromium") or shutil.which("msedge")


def render(chrome: str, url: str, budget_ms: int = 9000) -> str:
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


def main() -> int:
    chrome = find_chrome()
    if chrome is None:
        print("找不到 Chrome/Edge，无法做界面渲染检查（可用 DG_CHROME 指定路径）")
        return 2
    chrome = os.environ.get("DG_CHROME", chrome)

    failed: list[str] = []
    for path, label, needles in CASES:
        html = render(chrome, BASE + path)
        missing = [needle for needle in needles if needle not in html]
        ok = bool(html) and not missing
        print(f"[{'PASS' if ok else 'FAIL'}] {label}（{path}）"
              + ("" if ok else f"  缺失：{missing}，DOM {len(html)} 字符"))
        if not ok:
            failed.append(label)

    print("\n" + "=" * 60)
    print(f"界面渲染：通过 {len(CASES) - len(failed)}/{len(CASES)}")
    if failed:
        print("失败：" + "、".join(failed))
    print("=" * 60)
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
