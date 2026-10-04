"""采集护栏（docs/09 §9.1「安全阀」与「命名空间隔离防误删」）。

为什么必须有它：
  采集配置改错、账号权限变化、源端短暂不可用，都会让"采集结果"看起来像
  "大量资产被删除"。若无护栏，一次错误采集就会把目录清空 —— 而目录一旦被
  污染，用户的信任就很难恢复（ADR-005 的另一半）。

护栏有四条（任一触发即 **BLOCKED**，本轮不执行任何删除）：
  1. 最大删除数      max_deletions       默认 200
  2. 最大删除比例    max_delete_ratio    默认 0.30
  3. 实体数保留比例  min_retention_ratio 默认 0.70（本次见到数 / 上次数）
  4. 首次采集保护    previous 为空时不判删除（无基线可比）

通过后执行的是**软删**（lifecycle=DELETED_AT_SOURCE），不是物理删除：
人工成果（描述、术语映射、质量规则）保留 N 天并允许"复活"（docs/08 §5）。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable, Sequence

Action = str  # "PROCEED" | "BLOCK"


@dataclass(frozen=True)
class GuardConfig:
    max_deletions: int = 200
    max_delete_ratio: float = 0.30
    min_retention_ratio: float = 0.70
    # 上一轮实体数低于此值时不做比例判定（小目录的比例噪音大）
    ratio_min_baseline: int = 10

    def as_dict(self) -> dict:
        return {
            "maxDeletions": self.max_deletions,
            "maxDeleteRatio": self.max_delete_ratio,
            "minRetentionRatio": self.min_retention_ratio,
            "ratioMinBaseline": self.ratio_min_baseline,
        }


@dataclass
class GuardDecision:
    action: Action
    reasons: list[str] = field(default_factory=list)
    deletions: list[str] = field(default_factory=list)
    previous_count: int = 0
    current_count: int = 0

    @property
    def blocked(self) -> bool:
        return self.action == "BLOCK"

    @property
    def delete_ratio(self) -> float:
        if not self.previous_count:
            return 0.0
        return len(self.deletions) / self.previous_count

    @property
    def retention_ratio(self) -> float:
        if not self.previous_count:
            return 1.0
        return self.current_count / self.previous_count

    def as_dict(self) -> dict:
        return {
            "action": self.action,
            "reasons": self.reasons,
            "previousCount": self.previous_count,
            "currentCount": self.current_count,
            "deleteCandidates": len(self.deletions),
            "deleteRatio": round(self.delete_ratio, 4),
            "retentionRatio": round(self.retention_ratio, 4),
        }


def evaluate_guard(
    previous: Iterable[str] | None,
    current: Iterable[str],
    config: GuardConfig | None = None,
) -> GuardDecision:
    """对比上轮快照与本轮结果，决定是否可以执行删除。

    纯函数，便于单测覆盖所有触发分支。
    """
    cfg = config or GuardConfig()
    prev = set(previous or ())
    curr = set(current)
    deletions = sorted(prev - curr)
    decision = GuardDecision(
        action="PROCEED",
        deletions=deletions,
        previous_count=len(prev),
        current_count=len(curr),
    )

    if not prev:
        # 首次采集（无基线）：绝不判删除，否则会把"部分采集"当"全部消失"
        decision.reasons.append("首次采集无基线，跳过删除检测")
        return decision

    if not deletions:
        return decision

    if len(deletions) > cfg.max_deletions:
        decision.action = "BLOCK"
        decision.reasons.append(
            f"待删实体数 {len(deletions)} 超过上限 {cfg.max_deletions}"
        )

    ratio = len(deletions) / len(prev)
    if len(prev) >= cfg.ratio_min_baseline and ratio > cfg.max_delete_ratio:
        decision.action = "BLOCK"
        decision.reasons.append(
            f"删除比例 {ratio:.1%} 超过阈值 {cfg.max_delete_ratio:.0%}"
            f"（{len(deletions)}/{len(prev)}）"
        )

    retention = len(curr) / len(prev)
    if len(prev) >= cfg.ratio_min_baseline and retention < cfg.min_retention_ratio:
        decision.action = "BLOCK"
        decision.reasons.append(
            f"实体数骤降：本轮见到 {len(curr)}，上轮 {len(prev)}，"
            f"保留率 {retention:.1%} 低于阈值 {cfg.min_retention_ratio:.0%}"
        )

    if decision.action == "PROCEED" and deletions:
        decision.reasons.append(
            f"删除 {len(deletions)} 个实体（比例 {ratio:.1%}）在阈值内，执行软删"
        )
    return decision


def summarize_history(runs: Sequence[dict]) -> dict:
    """把采集运行历史汇总成健康视图（供 /api/v1/collect/health）。"""
    from datetime import datetime, timezone

    if not runs:
        return {"total": 0, "health": "UNKNOWN", "sources": []}

    now = datetime.now(timezone.utc)
    by_source: dict[str, dict] = {}
    for run in runs:  # 假定按 started_at DESC 传入
        key = f"{run['source']}@{run['namespace']}"
        entry = by_source.setdefault(
            key,
            {
                "source": run["source"],
                "namespace": run["namespace"],
                "lastRunAt": run["started_at"],
                "lastStatus": run["status"],
                "consecutiveFailures": 0,
                "runs": 0,
                "blocked": 0,
                "failed": 0,
                "stalenessHours": None,
            },
        )
        entry["runs"] += 1
        if run["status"] == "BLOCKED":
            entry["blocked"] += 1
        if run["status"] == "FAILED":
            entry["failed"] += 1

    # 连续失败次数：从最新往前数
    for key, entry in by_source.items():
        streak = 0
        for run in runs:
            if f"{run['source']}@{run['namespace']}" != key:
                continue
            if run["status"] in ("FAILED", "BLOCKED"):
                streak += 1
            else:
                break
        entry["consecutiveFailures"] = streak

        started = entry["lastRunAt"]
        if started is not None:
            if started.tzinfo is None:
                started = started.replace(tzinfo=timezone.utc)
            entry["stalenessHours"] = round((now - started).total_seconds() / 3600, 2)

    sources = sorted(by_source.values(), key=lambda x: x["source"])
    worst = "HEALTHY"
    for entry in sources:
        if entry["consecutiveFailures"] >= 3:
            worst = "UNHEALTHY"
            break
        if entry["consecutiveFailures"] >= 1 or (entry["stalenessHours"] or 0) > 48:
            worst = "DEGRADED" if worst == "HEALTHY" else worst

    return {"total": len(runs), "health": worst, "sources": sources}


__all__ = [
    "Action",
    "GuardConfig",
    "GuardDecision",
    "evaluate_guard",
    "summarize_history",
]
