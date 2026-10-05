"""把门禁结论回写到 PR / MR（GitHub + GitLab）。

CI 插件里最"轻"、也最影响采纳率的一步：**结论要出现在人做决定的地方**（PR 页面），
而不是躺在流水线日志里等人去翻。本脚本只做这一件事，凭证全部来自环境变量。

用法：
    # GitHub Actions
    python tools/ci/pr_comment.py --provider github --report gate.md \
        --repo "$GITHUB_REPOSITORY" --pr "$PR_NUMBER" --token "$GITHUB_TOKEN"

    # GitLab CI
    python tools/ci/pr_comment.py --provider gitlab --report gate.md \
        --project "$CI_PROJECT_ID" --mr "$CI_MERGE_REQUEST_IID" --token "$GITLAB_TOKEN"

设计要点：
  1. **没凭证就明说跳过**（打印原因并以退出码 0 结束）：CI 里最常见的失败不是"回写失败"，
     而是"因为没有凭证，整个门禁步骤被判失败"，于是团队把这一步删掉；
  2. **不做静默重试**：回写失败要打印 HTTP 状态与响应体，否则排障只能靠猜；
  3. **同一个 PR 只保留一条评论**（有 marker 就更新）：否则每次 push 都多一条，很快就没人看。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

MARKER = "<!-- dg-contract-gate -->"


def api(provider: str, path: str, token: str, method: str = "GET",
        body: dict | None = None, base_override: str | None = None) -> tuple[int, dict | list | str]:
    if base_override:
        base = base_override.rstrip("/")
    else:
        base = "https://api.github.com" if provider == "github" else "https://gitlab.com/api/v4"
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/vnd.github+json" if provider == "github" else "application/json")
    if provider == "github":
        request.add_header("Authorization", f"Bearer {token}")
    else:
        request.add_header("PRIVATE-TOKEN", token)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode("utf-8", "replace")
            try:
                return response.status, json.loads(raw)
            except json.JSONDecodeError:
                return response.status, raw
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")


def upsert_github_comment(repo: str, pr: str, token: str, report: str,
                          base_override: str | None) -> str:
    status, existing = api("github", f"/repos/{repo}/issues/{pr}/comments?per_page=100",
                           token, base_override=base_override)
    if status != 200 or not isinstance(existing, list):
        return f"读取已有评论失败（HTTP {status}）：{str(existing)[:200]}"
    body = f"{MARKER}\n{report}"
    for comment in existing:
        if MARKER in str(comment.get("body", "")):
            status, updated = api("github", f"/repos/{repo}/issues/comments/{comment['id']}",
                                  token, method="PATCH", body={"body": body},
                                  base_override=base_override)
            return f"已更新既有评论（HTTP {status}）" if status in (200, 201) \
                else f"更新评论失败（HTTP {status}）：{str(updated)[:200]}"
    status, created = api("github", f"/repos/{repo}/issues/{pr}/comments", token,
                          method="POST", body={"body": body}, base_override=base_override)
    return f"已创建评论（HTTP {status}）" if status in (200, 201) \
        else f"创建评论失败（HTTP {status}）：{str(created)[:200]}"


def upsert_gitlab_comment(project: str, mr: str, token: str, report: str,
                          base_override: str | None) -> str:
    body = f"{MARKER}\n{report}"
    status, existing = api("gitlab", f"/projects/{project}/merge_requests/{mr}/notes?per_page=100",
                           token, base_override=base_override)
    if status != 200 or not isinstance(existing, list):
        return f"读取已有评论失败（HTTP {status}）：{str(existing)[:200]}"
    for note in existing:
        if MARKER in str(note.get("body", "")):
            status, updated = api("gitlab", f"/projects/{project}/merge_requests/{mr}/notes/{note['id']}",
                                  token, method="PUT", body={"body": body},
                                  base_override=base_override)
            return f"已更新既有评论（HTTP {status}）" if status in (200, 201) \
                else f"更新评论失败（HTTP {status}）：{str(updated)[:200]}"
    status, created = api("gitlab", f"/projects/{project}/merge_requests/{mr}/notes", token,
                          method="POST", body={"body": body}, base_override=base_override)
    return f"已创建评论（HTTP {status}）" if status in (200, 201) \
        else f"创建评论失败（HTTP {status}）：{str(created)[:200]}"


def main() -> int:
    parser = argparse.ArgumentParser(description="把门禁结论回写到 PR / MR")
    parser.add_argument("--provider", required=True, choices=["github", "gitlab"])
    parser.add_argument("--report", required=True, help="门禁生成的 Markdown 报告")
    parser.add_argument("--repo", help="GitHub: owner/name")
    parser.add_argument("--pr", help="GitHub: PR 编号")
    parser.add_argument("--project", help="GitLab: 项目 ID 或 URL 编码的路径")
    parser.add_argument("--mr", help="GitLab: MR IID")
    parser.add_argument("--token", default=None, help="凭证（默认读 GITHUB_TOKEN / GITLAB_TOKEN）")
    parser.add_argument("--api-base", default=os.environ.get("DG_CI_API_BASE"),
                        help="自建实例的 API 地址（GitHub Enterprise / 私有 GitLab）")
    parser.add_argument("--dry-run", action="store_true", help="只打印报告，不调用 API")
    args = parser.parse_args()

    report_path = Path(args.report)
    if not report_path.exists():
        print(f"报告文件不存在：{report_path}", file=sys.stderr)
        return 2
    report = report_path.read_text(encoding="utf-8")

    if args.dry_run:
        print(report)
        return 0

    token = args.token or os.environ.get(
        "GITHUB_TOKEN" if args.provider == "github" else "GITLAB_TOKEN")
    if not token:
        # 刻意不失败：CI 里"因为没配凭证导致整步失败"是插件被删掉的头号原因
        print(f"⚠ 未提供 {args.provider} 凭证（--token 或环境变量）：**跳过回写**。"
              f"门禁结论仍在报告文件里：{report_path}")
        return 0

    if args.provider == "github":
        if not args.repo or not args.pr:
            print("GitHub 需要 --repo 与 --pr", file=sys.stderr)
            return 2
        outcome = upsert_github_comment(args.repo, args.pr, token, report, args.api_base)
    else:
        if not args.project or not args.mr:
            print("GitLab 需要 --project 与 --mr", file=sys.stderr)
            return 2
        outcome = upsert_gitlab_comment(args.project, args.mr, token, report, args.api_base)

    print(outcome)
    return 0 if outcome.startswith(("已创建", "已更新")) else 1


if __name__ == "__main__":
    raise SystemExit(main())
