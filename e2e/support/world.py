"""场景上下文：在 假如/当/那么 之间传递状态（每场景一个全新实例）。"""

from __future__ import annotations

import tempfile
from dataclasses import dataclass, field


@dataclass
class World:
    token: str | None = None
    namespace: str = "java_e2e"
    last_status: int | None = None
    last_body: object = None
    pending_body: dict | None = None
    calls: list[tuple[str, str, int]] = field(default_factory=list)
    urns: dict[str, str] = field(default_factory=dict)
    state: dict[str, object] = field(default_factory=dict)

    def __post_init__(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="dg_bdd_")

    @property
    def tmp(self) -> str:
        return self._tmp.name

    def remember(self, key: str, value: object) -> None:
        self.state[key] = value

    def recall(self, key: str, default=None):
        return self.state.get(key, default)

    def cleanup(self) -> None:
        self._tmp.cleanup()