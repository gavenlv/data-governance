"""调用仓库内工具（部署侧那一半）：引擎审计采集器、CI 门禁、PR 回写。

对齐 tools/java_e2e_verify.py:189-334。验证的不只是平台接口，还包括真实客户会用的脚本。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from . import config


def run_engine_audit_loader(records: list[dict], engine: str, namespace: str,
                            base_url: str, token: str) -> dict | None:
    with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        path = handle.name
    env = {**os.environ, "DG_BASE_URL": base_url, "DG_API_TOKEN": token,
           "PYTHONIOENCODING": "utf-8"}
    proc = subprocess.run(
        [sys.executable, str(config.REPO_ROOT / "tools" / "engine_audit_load.py"),
         "--engine", engine, "--namespace", namespace, "--file", path],
        capture_output=True, text=True, encoding="utf-8", errors="replace", env=env, timeout=180,
    )
    Path(path).unlink(missing_ok=True)
    if proc.returncode != 0:
        return None
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError:
        return None


def run_ci_gate(contract_path: Path, namespace: str, base_url: str, token: str,
                requirements: list[str] | None = None,
                report_path: Path | None = None) -> tuple[int, str, dict | None]:
    """跑一次 tools/ci/contract_gate.py，返回 (退出码, 控制台输出, 结论 JSON)。"""
    with tempfile.TemporaryDirectory() as tmp:
        report = report_path or Path(tmp) / "gate.md"
        result_json = Path(tmp) / "gate.json"
        command = [sys.executable, str(config.REPO_ROOT / "tools" / "ci" / "contract_gate.py"),
                   "--contract", str(contract_path), "--namespace", namespace,
                   "--base-url", base_url, "--token", token,
                   "--report-file", str(report), "--json-out", str(result_json),
                   "--source", "bdd"]
        for flag in requirements or []:
            command.append(flag)
        proc = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                              errors="replace", env={**os.environ, "PYTHONIOENCODING": "utf-8"},
                              timeout=180)
        parsed = None
        if result_json.exists():
            try:
                parsed = json.loads(result_json.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                parsed = None
        return proc.returncode, (proc.stdout or "") + (proc.stderr or ""), parsed


def run_ci_pr_comment(report_path: Path, provider: str, api_base: str,
                      token: str | None) -> tuple[int, str]:
    """token=None 表示不传凭证（验证「无凭证跳过而非失败」）。"""
    command = [sys.executable, str(config.REPO_ROOT / "tools" / "ci" / "pr_comment.py"),
               "--provider", provider, "--report", str(report_path), "--api-base", api_base]
    if provider == "github":
        command += ["--repo", "acme/dg", "--pr", "42"]
    else:
        command += ["--project", "123", "--mr", "7"]
    env = {**os.environ, "PYTHONIOENCODING": "utf-8"}
    if token is not None:
        command += ["--token", token]
    else:
        env.pop("GITHUB_TOKEN", None)
        env.pop("GITLAB_TOKEN", None)
    proc = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env, timeout=120)
    return proc.returncode, (proc.stdout or "").strip() + (proc.stderr or "").strip()


class StubGitApi:
    """记录收到的请求的假 Git API（验证回写的**请求形状**，不依赖真实 GitHub/GitLab）。"""

    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict | None]] = []

    def start(self) -> str:
        import threading
        from http.server import BaseHTTPRequestHandler, HTTPServer

        outer = self

        class Handler(BaseHTTPRequestHandler):
            def _handle(self, method: str) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length)) if length else None
                outer.calls.append((method, self.path, body))
                if method == "GET":
                    seen = [call for call in outer.calls
                            if call[0] == "GET" and call[1] == self.path]
                    payload: object = [{"id": 123, "body": "<!-- dg-contract-gate -->\n旧内容"}] \
                        if len(seen) > 1 else []
                else:
                    payload = {"id": 123}
                raw = json.dumps(payload).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self) -> None:  # noqa: N802
                self._handle("GET")

            def do_POST(self) -> None:  # noqa: N802
                self._handle("POST")

            def do_PATCH(self) -> None:  # noqa: N802
                self._handle("PATCH")

            def do_PUT(self) -> None:  # noqa: N802
                self._handle("PUT")

            def log_message(self, *args) -> None:
                return

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def stop(self) -> None:
        self.server.shutdown()