"""通用步骤：所有 feature 复用的 HTTP 调用与断言。

设计：以「我以管理员令牌调用 GET /api/v1/xxx」这类通用步骤承载 HTTP，
领域步骤只负责构造请求与做多字段忠实断言。响应体通过 world 传递。
"""

from __future__ import annotations

import json

from pytest_bdd import given, parsers, then, when

from support import config

_TOKENS: dict[str, str | None] = {
    "管理员": config.ADMIN_TOKEN,
    "治理员": config.STEWARD_TOKEN,
    "只读": config.READER_TOKEN,
    "匿名": None,
}


def _resolve(obj, path: str):
    current = obj
    for part in path.split("."):
        if isinstance(current, dict):
            current = current.get(part)
        elif isinstance(current, list):
            try:
                current = current[int(part)]
            except (ValueError, IndexError):
                return None
        else:
            return None
    return current


def _has_path(obj, path: str) -> bool:
    current = obj
    for part in path.split("."):
        if isinstance(current, dict):
            if part not in current:
                return False
            current = current[part]
        elif isinstance(current, list):
            try:
                current = current[int(part)]
            except (ValueError, IndexError):
                return False
        else:
            return False
    return True


def _as_text(value) -> str:
    if isinstance(value, bool):
        return "True" if value else "False"
    return str(value)


def _brief(body) -> str:
    text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
    return text[:200]


def _parse_body(docstring: str | None):
    if not docstring:
        return None
    text = docstring.strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        import yaml
        return yaml.safe_load(text)


@given(parsers.parse("命名空间为 {ns}"))
def _set_namespace(world, ns: str) -> None:
    world.namespace = ns


@when(parsers.parse("我以{role}令牌调用 {method} {path}"))
def _call(world, api, role: str, method: str, path: str, docstring: str | None = None) -> None:
    body = _parse_body(docstring)
    token = _TOKENS[role]
    world.token = token
    status, payload = api.call(method.upper(), path, body, token=token)
    world.last_status = status
    world.last_body = payload
    world.calls.append((method.upper(), path, status))


@then(parsers.parse("响应状态码应为 {status:d}"))
def _assert_status(world, status: int) -> None:
    assert world.last_status == status, (
        f"期望 HTTP {status}，实际 {world.last_status}：{_brief(world.last_body)}"
    )


@then(parsers.parse("响应体中字段 {path} 应为 {expected}"))
def _assert_field_equals(world, path: str, expected: str) -> None:
    actual = _resolve(world.last_body, path)
    assert actual is not None, f"字段 {path} 缺失：{_brief(world.last_body)}"
    assert _as_text(actual) == expected, (
        f"字段 {path}：期望 {expected}，实际 {_as_text(actual)}"
    )


@then(parsers.parse("响应体中字段 {path} 非空"))
def _assert_field_present(world, path: str) -> None:
    actual = _resolve(world.last_body, path)
    assert actual is not None and actual != "" and actual != [], (
        f"字段 {path} 应非空：{_brief(world.last_body)}"
    )


@then(parsers.parse("响应体中字段 {path} 至少为 {minimum:d}"))
def _assert_field_min(world, path: str, minimum: int) -> None:
    actual = _resolve(world.last_body, path)
    assert isinstance(actual, (int, float)) or (isinstance(actual, str) and actual.isdigit()), (
        f"字段 {path} 不是数字：{_brief(world.last_body)}"
    )
    assert int(actual) >= minimum, f"字段 {path}：期望 >= {minimum}，实际 {actual}"


@then(parsers.parse("响应体中字段 {path} 应为非空列表"))
def _assert_field_list(world, path: str) -> None:
    actual = _resolve(world.last_body, path)
    assert isinstance(actual, list) and len(actual) > 0, (
        f"字段 {path} 应为非空列表：{_brief(world.last_body)}"
    )


@then(parsers.parse("响应体中字段 {path} 属于 {options}"))
def _assert_field_in(world, path: str, options: str) -> None:
    actual = _resolve(world.last_body, path)
    allowed = options.split("/")
    assert _as_text(actual) in allowed, (
        f"字段 {path}：期望属于 {allowed}，实际 {_as_text(actual)}"
    )


@then(parsers.parse("响应体中字段 {path} 的长度至少为 {minimum:d}"))
def _assert_len_min(world, path: str, minimum: int) -> None:
    actual = _resolve(world.last_body, path)
    assert isinstance(actual, (list, dict, str)), (
        f"字段 {path} 不是可计长度的类型：{_brief(world.last_body)}"
    )
    assert len(actual) >= minimum, f"字段 {path}：期望长度 >= {minimum}，实际 {len(actual)}"


@then(parsers.parse("响应体应包含文本 {needle}"))
def _assert_contains(world, needle: str) -> None:
    haystack = world.last_body if isinstance(world.last_body, str) else json.dumps(
        world.last_body, ensure_ascii=False
    )
    assert needle in haystack, f"响应中未找到「{needle}」：{_brief(world.last_body)}"


@then(parsers.parse("响应体中字段 {path} 不应包含文本 {needle}"))
def _assert_field_excludes(world, path: str, needle: str) -> None:
    """用于「凭据永不回显」这类断言：字段里不该出现口令/账号片段。"""
    actual = _resolve(world.last_body, path)
    assert actual is not None, f"字段 {path} 缺失：{_brief(world.last_body)}"
    assert needle not in _as_text(actual), (
        f"字段 {path} 不应包含「{needle}」，实际 {_as_text(actual)}"
    )


@then(parsers.parse("响应体中字段 {path} 不应存在"))
def _assert_field_absent(world, path: str) -> None:
    assert not _has_path(world.last_body, path), f"字段 {path} 不应存在"


@then(parsers.parse("响应体中应包含字段 {path}"))
def _assert_field_key(world, path: str) -> None:
    """键存在即可（值可为空列表/空字符串）——用于「给出解释口径」这类断言。"""
    assert _has_path(world.last_body, path), (
        f"响应中缺少字段 {path}：{_brief(world.last_body)}"
    )