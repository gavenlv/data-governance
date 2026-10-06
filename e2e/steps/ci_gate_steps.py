"""CI 门禁（组 16）与门禁插件 GitHub/GitLab（组 37）的领域步骤。

组 37 只调用仓库内的真实脚本（tools/ci/contract_gate.py、tools/ci/pr_comment.py），
不重写它们；回写用 StubGitApi 断言「请求形状」，不依赖真实 GitHub/GitLab。
"""

from __future__ import annotations

from pathlib import Path

import yaml
from pytest_bdd import given, then, when

from support import config
from support.client import quote_urn
from support.tools_runner import StubGitApi, run_ci_gate, run_ci_pr_comment
from steps.contract_steps import (
    build_contract_document,
    ensure_event_log_target,
    new_contract_id,
    register_contract,
)

_GOVERNANCE_POLICY = {
    "requireOwner": True,
    "requireDescription": True,
    "requireNoUndetectedConsumers": True,
}


def _remember_call(world, method: str, path: str, status: int, payload) -> None:
    world.token = config.ADMIN_TOKEN
    world.last_status = status
    world.last_body = payload
    world.calls.append((method, path, status))


def _ci_check(api, world, document: dict, allow_breaking: bool = False,
              policy: dict | None = None, source: str = "e2e"):
    body: dict = {"document": document, "namespace": world.namespace, "source": source}
    if allow_breaking:
        body["allowBreaking"] = True
    if policy is not None:
        body["policy"] = policy
    return api.call("POST", "/api/v1/contracts/ci-check", body, token=config.ADMIN_TOKEN)


def _gate_document(world) -> dict:
    """相对已登记基线构造一次**新的**破坏性变更（删掉 created_at 列）。"""
    document = dict(world.recall("contract_doc"))
    document["version"] = "3.1.0"
    document["schema"] = [field for field in document["schema"] if field["name"] != "created_at"]
    return document


# ------------------------------------------------------------------ 组 16 门禁

@when("我以管理员令牌对该契约发起一次破坏性变更的 CI 门禁检查")
def _ci_check_breaking(world, api) -> None:
    status, payload = _ci_check(api, world, _gate_document(world))
    _remember_call(world, "POST", "/api/v1/contracts/ci-check", status, payload)


@when("我以管理员令牌在顶层开启 allowBreaking 对该契约发起 CI 门禁检查")
def _ci_check_allow_breaking(world, api) -> None:
    status, payload = _ci_check(api, world, _gate_document(world), allow_breaking=True)
    _remember_call(world, "POST", "/api/v1/contracts/ci-check", status, payload)


@when("我以管理员令牌按治理属性必填策略对该契约发起 CI 门禁检查")
def _ci_check_governance_policy(world, api) -> None:
    status, payload = _ci_check(api, world, _gate_document(world), policy=_GOVERNANCE_POLICY)
    _remember_call(world, "POST", "/api/v1/contracts/ci-check", status, payload)


@given("我已对该契约发起过 3 次不同策略的 CI 门禁检查")
def _three_gate_checks(world, api) -> None:
    document = _gate_document(world)
    _ci_check(api, world, document)
    _ci_check(api, world, document, allow_breaking=True)
    _ci_check(api, world, document, policy=_GOVERNANCE_POLICY)
    world.calls.append(("POST", "/api/v1/contracts/ci-check", 200))


@then("门禁判定应为非阻断")
def _verdict_not_block(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    assert body.get("verdict") != "BLOCK", (
        f"allowBreaking 是顶层开关，判定不应为 BLOCK：{body.get('verdict')} "
        f"{body.get('blocking')}"
    )


@then("门禁阻断项应点名 Owner 与未登记消费者")
def _blocking_names_owner_and_consumers(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    text = " ".join(str(item) for item in (body.get("blocking") or []))
    assert "Owner" in text, f"阻断项未点名 Owner：{text[:200]}"
    assert "未登记消费者" in text, f"阻断项未点名未登记消费者：{text[:200]}"


@then("门禁历史中每条记录都应有判定结论")
def _history_every_record_has_verdict(world) -> None:
    body = world.last_body if isinstance(world.last_body, dict) else {}
    checks = body.get("checks") or []
    assert checks, f"门禁历史为空：{body}"
    missing = [row for row in checks if not row.get("verdict")]
    assert not missing, f"存在没有判定结论的门禁记录：{missing[:1]}"


# --------------------------------------------------------- 组 37 门禁插件

def _prepare_gate_contract(world, api) -> None:
    if world.recall("good_contract_path") is not None:
        return
    target = ensure_event_log_target(api, world.namespace)
    contract_id = new_contract_id("gate")
    document = build_contract_document(contract_id, target, "1.0.0")
    status, payload = register_contract(api, world.namespace, document)
    world.calls.append(("POST", "/api/v1/contracts", status))
    assert status == 200 and payload.get("registered") is True, (
        f"登记门禁基线契约失败：HTTP {status} {payload}"
    )
    world.remember("gate_contract_urn", payload.get("contract"))
    world.remember("target_urn", target)
    path = Path(world.tmp) / "good.yaml"
    path.write_text(yaml.safe_dump(document, allow_unicode=True), encoding="utf-8")
    world.remember("good_contract_path", path)


def _run_gate(world, base_url: str, contract_path, report_path, prefix: str,
              requirements: list[str] | None = None) -> None:
    exit_code, output, parsed = run_ci_gate(
        contract_path, world.namespace, base_url, config.ADMIN_TOKEN,
        requirements=requirements, report_path=report_path)
    world.remember(f"{prefix}_exit", exit_code)
    world.remember(f"{prefix}_output", output)
    world.remember(f"{prefix}_json", parsed)
    world.remember(f"{prefix}_report", report_path)


@given("我已登记一份可用于门禁插件的基线契约并导出契约文档")
def _given_gate_contract(world, api) -> None:
    _prepare_gate_contract(world, api)


@given("我已用门禁插件生成一份 Markdown 报告")
def _given_gate_report(world, api, base_url) -> None:
    _prepare_gate_contract(world, api)
    _run_gate(world, base_url, world.recall("good_contract_path"),
              Path(world.tmp) / "gate.md", prefix="gate")


@when("我用门禁插件对该契约文档执行一次 CI 门禁")
def _when_run_gate(world, base_url) -> None:
    _run_gate(world, base_url, world.recall("good_contract_path"),
              Path(world.tmp) / "gate.md", prefix="gate")


@when("我用门禁插件对删去末列并升大版本的契约文档执行门禁")
def _when_run_gate_breaking(world, base_url, api) -> None:
    urn = world.recall("gate_contract_urn")
    status, detail = api.call("GET", f"/api/v1/contracts/{quote_urn(urn)}",
                              token=config.ADMIN_TOKEN)
    world.calls.append(("GET", f"/api/v1/contracts/{quote_urn(urn)}", status))
    assert status == 200 and isinstance(detail, dict) and detail.get("spec"), (
        f"读取当前已登记契约失败：HTTP {status} {detail}"
    )
    spec = detail["spec"]
    raw_schema = spec.get("schema") or {}
    fields = raw_schema.get("fields") if isinstance(raw_schema, dict) else raw_schema
    fields = list(fields or [])
    assert fields, "当前契约没有字段，无法构造破坏性变更"
    version = str(spec.get("contractVersion") or "1.0.0")
    parts = version.split(".")
    try:
        parts[0] = str(int(parts[0]) + 1)
    except ValueError:
        parts = ["9", "0", "0"]
    breaking_document = {
        "apiVersion": spec.get("apiVersion", "v3.0.2"),
        "kind": spec.get("kind", "DataContract"),
        "id": detail.get("id"),
        "version": ".".join(parts),
        "status": spec.get("status", "ACTIVE"),
        "dataset": detail.get("dataset"),
        "primaryKey": spec.get("primaryKey"),
        "schema": fields[:-1],
        "quality": spec.get("quality"),
        "sla": spec.get("sla"),
    }
    path = Path(world.tmp) / "bad.yaml"
    path.write_text(yaml.safe_dump(breaking_document, allow_unicode=True), encoding="utf-8")
    _run_gate(world, base_url, path, Path(world.tmp) / "bad.md", prefix="bad")


@when("我在严格模式（--fail-closed）下对该契约文档执行门禁")
def _when_run_gate_strict(world, base_url) -> None:
    _run_gate(world, base_url, world.recall("good_contract_path"),
              Path(world.tmp) / "strict.md", prefix="strict", requirements=["--fail-closed"])


@when("我把该报告分别回写到 GitHub 与 GitLab 各两次")
def _when_pr_comment(world) -> None:
    report = world.recall("gate_report")
    assert report is not None and Path(report).exists(), f"缺少门禁 Markdown 报告：{report}"
    stub = StubGitApi()
    api_base = stub.start()
    try:
        github_first = run_ci_pr_comment(report, "github", api_base, "stub-token")
        github_second = run_ci_pr_comment(report, "github", api_base, "stub-token")
        gitlab_first = run_ci_pr_comment(report, "gitlab", api_base, "stub-token")
        gitlab_second = run_ci_pr_comment(report, "gitlab", api_base, "stub-token")
        calls = list(stub.calls)
    finally:
        stub.stop()
    world.remember("pr_github", (github_first, github_second))
    world.remember("pr_gitlab", (gitlab_first, gitlab_second))
    world.remember("pr_calls", calls)


@when("我在不提供任何凭证的情况下回写 GitHub")
def _when_pr_comment_without_token(world) -> None:
    report = world.recall("gate_report")
    assert report is not None and Path(report).exists(), f"缺少门禁 Markdown 报告：{report}"
    stub = StubGitApi()
    api_base = stub.start()
    try:
        result = run_ci_pr_comment(report, "github", api_base, None)
    finally:
        stub.stop()
    world.remember("pr_no_token", result)


@then("门禁插件应产出退出码与结论 JSON")
def _gate_artifacts(world) -> None:
    exit_code = world.recall("gate_exit")
    parsed = world.recall("gate_json")
    assert parsed is not None, "门禁插件未产出结论 JSON"
    assert exit_code in (0, 1), f"退出码应在 (0, 1)：{exit_code}"
    assert parsed.get("verdict") in ("PASS", "WARN", "BLOCK"), (
        f"结论 JSON 的 verdict 非法：{parsed.get('verdict')}"
    )


@then("门禁插件的控制台输出应包含「判定」")
def _gate_output_has_verdict(world) -> None:
    output = world.recall("gate_output") or ""
    assert "判定" in output, f"控制台输出未包含判定：{output[:200]}"


@then("门禁插件的 Markdown 报告应包含判定标题")
def _gate_report_has_heading(world) -> None:
    report = world.recall("gate_report")
    text = Path(report).read_text(encoding="utf-8") if report and Path(report).exists() else ""
    assert "### " in text, f"Markdown 报告缺少判定标题（### ）：{text[:200]}"


@then("门禁插件的 Markdown 报告应注明判定依据来自平台")
def _gate_report_has_provenance(world) -> None:
    report = world.recall("gate_report")
    text = Path(report).read_text(encoding="utf-8") if report and Path(report).exists() else ""
    assert "判定依据来自平台" in text, f"Markdown 报告未注明判定依据来源：{text[-200:]}"


@then("门禁插件退出码应为 1 且结论为 BLOCK")
def _gate_breaking_blocked(world) -> None:
    exit_code = world.recall("bad_exit")
    parsed = world.recall("bad_json")
    assert exit_code == 1, (
        f"破坏性变更的退出码应为 1：{exit_code}（{str(world.recall('bad_output'))[:200]}）"
    )
    assert parsed is not None and parsed.get("verdict") == "BLOCK", (
        f"破坏性变更的结论应为 BLOCK：{parsed and parsed.get('verdict')}"
    )
    assert parsed.get("blocking"), "破坏性变更应给出阻断项"


@then("严格模式下的退出码应为 0 或 1")
def _gate_strict_exit(world) -> None:
    exit_code = world.recall("strict_exit")
    assert exit_code in (0, 1), f"严格模式下的退出码应在 (0, 1)：{exit_code}"


@then("GitHub 与 GitLab 各自先创建再更新同一条评论")
def _pr_upsert(world) -> None:
    github_first, github_second = world.recall("pr_github")
    gitlab_first, gitlab_second = world.recall("pr_gitlab")
    calls = world.recall("pr_calls") or []
    create_calls = [call for call in calls if call[0] in ("POST", "PATCH", "PUT")]
    assert github_first[0] == 0 and github_second[0] == 0 \
        and gitlab_first[0] == 0 and gitlab_second[0] == 0, (
        f"回写退出码应为 0：GitHub {github_first[0]}/{github_second[0]} "
        f"GitLab {gitlab_first[0]}/{gitlab_second[0]}"
    )
    assert "已创建评论" in github_first[1] and "已更新既有评论" in github_second[1], (
        f"GitHub 未先创建再更新：{github_first[1]} / {github_second[1]}"
    )
    assert "已创建评论" in gitlab_first[1] and "已更新既有评论" in gitlab_second[1], (
        f"GitLab 未先创建再更新：{gitlab_first[1]} / {gitlab_second[1]}"
    )
    assert len(create_calls) == 4, (
        f"期望 4 次写请求（各平台创建 + 更新），实际 {len(create_calls)}"
    )


@then("每次回写都带上门禁标记")
def _pr_marker_present(world) -> None:
    calls = world.recall("pr_calls") or []
    create_calls = [call for call in calls if call[0] in ("POST", "PATCH", "PUT")]
    assert create_calls, "没有任何回写请求"
    bad = [call for call in create_calls
           if "dg-contract-gate" not in str((call[2] or {}).get("body", ""))]
    assert not bad, f"存在未带门禁标记的回写请求：{bad[:1]}"


@then("GitHub 回写应打到 issues comments 端点")
def _pr_github_endpoint(world) -> None:
    calls = world.recall("pr_calls") or []
    path = next((call[1] for call in calls if "issues" in call[1] and call[0] == "POST"), "")
    assert path.startswith("/repos/acme/dg/issues/42/comments"), f"GitHub 回写端点错误：{path}"


@then("GitLab 回写应打到 MR notes 端点")
def _pr_gitlab_endpoint(world) -> None:
    calls = world.recall("pr_calls") or []
    path = next((call[1] for call in calls if "notes" in call[1] and call[0] == "POST"), "")
    assert path.startswith("/projects/123/merge_requests/7/notes"), f"GitLab 回写端点错误：{path}"


@then("回写应跳过而不是失败")
def _pr_skips_without_token(world) -> None:
    exit_code, output = world.recall("pr_no_token")
    assert exit_code == 0, f"没有凭证时应跳过（退出码 0）：{exit_code}"
    assert "跳过" in output, f"没有凭证时应明说跳过：{output[:200]}"
    assert "dg-contract-gate" not in output, f"没有凭证时不应产生回写标记：{output[:200]}"


@then("仓库内应存在 GitHub Action、GitLab 模板与仓库 CI 流水线的门禁定义")
def _pipeline_definitions_present(world) -> None:
    action_file = config.REPO_ROOT / ".github" / "actions" / "contract-gate" / "action.yml"
    gitlab_ci = config.REPO_ROOT / "ci" / "contract-gate.gitlab-ci.yml"
    workflow = config.REPO_ROOT / ".github" / "workflows" / "ci.yml"
    action_doc = yaml.safe_load(action_file.read_text(encoding="utf-8")) \
        if action_file.exists() else {}
    gitlab_doc = yaml.safe_load(gitlab_ci.read_text(encoding="utf-8")) \
        if gitlab_ci.exists() else {}
    workflow_doc = yaml.safe_load(workflow.read_text(encoding="utf-8")) \
        if workflow.exists() else {}
    assert action_doc.get("runs", {}).get("using") == "composite", (
        "GitHub Action 应为复合 Action（runs.using=composite）"
    )
    assert "contract" in action_doc.get("inputs", {}), "GitHub Action 应声明 contract 输入"
    assert "contract-gate:mr" in gitlab_doc, "GitLab 模板应包含 contract-gate:mr job"
    assert "jobs" in workflow_doc, "仓库 CI 流水线应包含 jobs"