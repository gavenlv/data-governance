"""通知通道（docs/09 §9.1）。

设计要点：
  - **log 通道永远可用**（作为兜底），即使 webhook 全部失败，告警仍会落在日志里
  - webhook 支持常见 IM 的 payload 形态（generic/slack/feishu/dingtalk/teams）
  - 投递结果写 alert_dispatch_log，避免"告警到底发出去没有"无从排查
  - 通道失败**不影响告警状态机**（告警已在库里，重试/换通道是运维动作）
"""

from __future__ import annotations

import json
import logging
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Protocol

log = logging.getLogger("dg.alerting")

SEVERITY_EMOJI = {"INFO": "ℹ️", "WARNING": "⚠️", "CRITICAL": "🚨"}


@dataclass
class AlertMessage:
    """发送给通道的消息（与数据库告警行解耦，便于测试）。"""

    dedup_key: str
    rule: str
    severity: str
    subject: str
    title: str
    scope: str | None = None
    detail: dict[str, Any] = field(default_factory=dict)
    recovered: bool = False
    fire_count: int = 1

    def text(self) -> str:
        icon = "✅" if self.recovered else SEVERITY_EMOJI.get(self.severity, "•")
        state = "已恢复" if self.recovered else "告警"
        head = f"{icon} [{state}][{self.severity}] {self.title}"
        lines = [head, f"对象：{self.subject}" + (f" / {self.scope}" if self.scope else "")]
        if not self.recovered and self.fire_count > 1:
            lines.append(f"重复次数：{self.fire_count}")
        for key, value in (self.detail or {}).items():
            if isinstance(value, (dict, list)):
                value = json.dumps(value, ensure_ascii=False)
            lines.append(f"{key}：{value}")
        return "\n".join(lines)

    def as_dict(self) -> dict:
        return {
            "dedupKey": self.dedup_key,
            "rule": self.rule,
            "severity": self.severity,
            "subject": self.subject,
            "scope": self.scope,
            "title": self.title,
            "detail": self.detail,
            "state": "RESOLVED" if self.recovered else "FIRING",
            "fireCount": self.fire_count,
            "text": self.text(),
        }


@dataclass
class DispatchResult:
    channel: str
    ok: bool
    status_code: int | None = None
    error: str | None = None


class Channel(Protocol):
    name: str
    min_severity: str

    def send(self, message: AlertMessage) -> DispatchResult: ...


SEVERITY_ORDER = {"INFO": 0, "WARNING": 1, "CRITICAL": 2}


def _passes_severity(min_severity: str, severity: str) -> bool:
    return SEVERITY_ORDER.get(severity, 0) >= SEVERITY_ORDER.get(min_severity, 1)


# ---------------------------------------------------------------------------
# 通道实现
# ---------------------------------------------------------------------------
@dataclass
class LogChannel:
    """兜底通道：写日志。永远可用，不需要外部依赖。"""

    name: str = "log"
    min_severity: str = "INFO"

    def send(self, message: AlertMessage) -> DispatchResult:
        if not _passes_severity(self.min_severity, message.severity):
            return DispatchResult(self.name, True, error="skipped by severity")
        log.warning("[ALERT] %s", message.text().replace("\n", " | "))
        return DispatchResult(self.name, True)


@dataclass
class WebhookChannel:
    """HTTP POST 通道。支持 generic/slack/feishu/dingtalk/teams 的 payload 形态。"""

    name: str
    url: str
    format: str = "generic"
    headers: dict[str, str] = field(default_factory=dict)
    min_severity: str = "WARNING"
    timeout: int = 5

    def build_payload(self, message: AlertMessage) -> dict:
        text = message.text()
        if self.format == "slack":
            return {"text": text}
        if self.format == "feishu":
            return {"msg_type": "text", "content": {"text": text}}
        if self.format == "dingtalk":
            return {"msgtype": "text", "text": {"content": text}}
        if self.format == "teams":
            return {"@type": "MessageCard", "@context": "http://schema.org/extensions", "text": text}
        # generic：结构化 JSON + 人类可读 text
        return message.as_dict()

    def send(self, message: AlertMessage) -> DispatchResult:
        if not _passes_severity(self.min_severity, message.severity):
            return DispatchResult(self.name, True, error="skipped by severity")

        body = json.dumps(self.build_payload(message), ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json; charset=utf-8", **self.headers}
        request = urllib.request.Request(self.url, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return DispatchResult(self.name, True, status_code=response.status)
        except urllib.error.HTTPError as exc:
            return DispatchResult(self.name, False, status_code=exc.code, error=str(exc))
        except Exception as exc:  # 网络错误等
            return DispatchResult(self.name, False, error=f"{type(exc).__name__}: {exc}")


@dataclass
class RecordingChannel:
    """测试用：把消息留在内存里，便于断言。"""

    name: str = "recording"
    min_severity: str = "INFO"
    sent: list[AlertMessage] = field(default_factory=list)

    def send(self, message: AlertMessage) -> DispatchResult:
        if not _passes_severity(self.min_severity, message.severity):
            return DispatchResult(self.name, True, error="skipped by severity")
        self.sent.append(message)
        return DispatchResult(self.name, True)


def channel_from_row(row: dict) -> Channel | None:
    """由 alert_channel 表行构造通道。配置非法时返回 None（不阻断其它通道）。

    注意 `format` 取自表的独立列（config 里的同名键可覆盖）——
    此前只从 config 里读，与表结构不一致，实测发现后修正。
    """
    kind = row.get("kind")
    config = row.get("config") or {}
    name = row["name"]
    min_severity = row.get("min_severity") or "WARNING"
    payload_format = config.get("format") or row.get("format") or "generic"

    if kind == "log":
        return LogChannel(name=name, min_severity=min_severity)
    if kind == "webhook":
        url = config.get("url")
        if not url:
            log.error("通道 %s 缺少 config.url，已跳过", name)
            return None
        return WebhookChannel(
            name=name,
            url=url,
            format=payload_format,
            headers=dict(config.get("headers") or {}),
            min_severity=min_severity,
            timeout=int(config.get("timeout", 5)),
        )
    log.error("未知通道类型 %s（通道 %s）", kind, name)
    return None


__all__ = [
    "AlertMessage",
    "Channel",
    "DispatchResult",
    "LogChannel",
    "RecordingChannel",
    "SEVERITY_EMOJI",
    "SEVERITY_ORDER",
    "WebhookChannel",
    "channel_from_row",
]
