"""HTTP 客户端：语义与 tools/java_e2e_verify.py 的 call() 完全一致（:54-73）。

返回 (status, parsed_body)；JSONDecodeError 时回退原文；HTTPError 也返回响应体，
这样才能断言「501 + 设计说明」「403 + 缺哪个权限点」这类**错误响应本身**的内容。
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request

from . import config


def quote_urn(urn: str) -> str:
    """URN 含冒号，进路径必须整体编码（与验证脚本一致）。"""
    return urllib.parse.quote(urn, safe="")


class Api:
    """绑定 base_url 的 HTTP 客户端。"""

    def __init__(self, base_url: str | None = None, timeout: int = 90) -> None:
        self.base_url = (base_url or config.DEFAULT_BASE_URL).rstrip("/")
        self.timeout = timeout

    def call(self, method: str, path: str, body: dict | None = None,
             token: str | None = config.ADMIN_TOKEN):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        request = urllib.request.Request(self.base_url + path, data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if token:
            request.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8", "replace")
                return response.status, _parse(raw)
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", "replace")
            return exc.code, _parse(raw)

    # 便捷方法
    def get(self, path: str, token: str | None = config.ADMIN_TOKEN):
        return self.call("GET", path, token=token)

    def post(self, path: str, body: dict | None = None, token: str | None = config.ADMIN_TOKEN):
        return self.call("POST", path, body, token=token)

    def put(self, path: str, body: dict | None = None, token: str | None = config.ADMIN_TOKEN):
        return self.call("PUT", path, body, token=token)

    def patch(self, path: str, body: dict | None = None, token: str | None = config.ADMIN_TOKEN):
        return self.call("PATCH", path, body, token=token)

    def delete(self, path: str, body: dict | None = None, token: str | None = config.ADMIN_TOKEN):
        return self.call("DELETE", path, body, token=token)


def _parse(raw: str):
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return raw