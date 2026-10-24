"""认证端到端验证：JWT/JWKS（生产形态）、静态令牌（开发形态）、生产 fail-closed。

为什么需要独立的验证脚本，而不是只留单测：

1. **单测测不到"配置是否真的接上了"**。`dg.auth.jwt.roles-claim` 这类属性从环境变量
   到 Spring 绑定、再到认证实现，中间任何一环漏了，单测都是绿的。这里用一个真实进程
   验证"改环境变量 → 行为改变"。
2. **单测测不到"控制面会不会起不来"**。生产 fail-closed 的价值全在"启动被拒绝"，
   而这只有在真的启动一次、观察退出码与日志时才能确认。
3. **算法混淆、alg=none 这类攻击，单测里是我们自己构造的令牌**；这里用的是
   PyJWT + cryptography 生成的令牌 —— 换一个实现生成攻击载荷，才说明防御不是
   "只防住了自己写的测试"。

覆盖的攻击/配置矩阵：
  无令牌 / 合法令牌 / 权限不足 / 过期 / nbf 未来 / iss 不符 / aud 不符 /
  HS256（公钥当 HMAC 密钥）/ alg=none / 未知 kid / 载荷篡改 /
  可配置角色声明（groups）/ 安全响应头 / actuator 鉴权 / 生产拒绝开发令牌

用法：
  python tools/auth_jwt_verify.py                     # 端口由内核分配，避免本机保留端口
  python tools/auth_jwt_verify.py --jar backend/dg-api/target/dg-api-0.1.0.jar
  python tools/auth_jwt_verify.py --static-base http://127.0.0.1:8081
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

import jwt as pyjwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

ROOT = Path(__file__).resolve().parent.parent
ISSUER = "https://idp.example.test"
AUDIENCE = "dg-platform"
KID = "dg-e2e-key"

RESULTS: list[tuple[bool, str, str]] = []


def ok(name: str, detail: str = "") -> None:
    RESULTS.append((True, name, detail))
    print(f"PASS  {name}" + (f" —— {detail}" if detail else ""))


def fail(name: str, detail: str = "") -> None:
    RESULTS.append((False, name, detail))
    print(f"FAIL  {name}" + (f" —— {detail}" if detail else ""))


def skip(name: str, detail: str) -> None:
    print(f"SKIP  {name} —— {detail}")


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def http(method: str, url: str, token: str | None = None, timeout: float = 20.0):
    """返回 (status, headers, body)。HTTP 错误也是结果，不抛异常。"""
    request = urllib.request.Request(url, method=method)
    if token is not None:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, dict(response.headers), response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read().decode("utf-8", "replace")
    except urllib.error.URLError as error:
        return 0, {}, f"连接失败：{error.reason}"


def generate_key() -> tuple[rsa.RSAPrivateKey, str, str]:
    """生成 RSA 密钥、JWKS（公钥）与 PEM 公钥（用于 HS256 混淆攻击）。"""
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = key.public_key().public_numbers()
    jwks = {
        "keys": [
            {
                "kty": "RSA",
                "kid": KID,
                "use": "sig",
                "alg": "RS256",
                "n": b64url(public.n.to_bytes((public.n.bit_length() + 7) // 8, "big")),
                "e": b64url(public.e.to_bytes((public.e.bit_length() + 7) // 8, "big")),
            }
        ]
    }
    public_pem = key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    return key, json.dumps(jwks), public_pem


def claims(subject: str, roles: list[str], **overrides) -> dict:
    now = int(time.time())
    payload = {
        "iss": ISSUER,
        "aud": AUDIENCE,
        "sub": subject,
        "name": "端到端测试用户",
        "groups": roles,
        "iat": now - 30,
        "nbf": now - 30,
        "exp": now + 600,
    }
    payload.update(overrides)
    return payload


def sign(key, payload: dict, *, algorithm: str = "RS256", kid: str = KID) -> str:
    return pyjwt.encode(payload, key, algorithm=algorithm, headers={"kid": kid})


def unsigned_none(payload: dict) -> str:
    header = b64url(json.dumps({"alg": "none", "typ": "JWT", "kid": KID}).encode())
    body = b64url(json.dumps(payload).encode())
    return f"{header}.{body}."


def hmac_with_public_key(public_pem: str, payload: dict) -> str:
    """算法混淆攻击：把**公钥材料**当成 HMAC 密钥签 HS256。

    PyJWT 会主动拒绝这种用法（"asymmetric key should not be used as an HMAC secret"），
    这本身是正确的防线，却让"我们是否也防住了"无法验证。因此这里按攻击者的做法手工拼一个
    合法格式的 HS256 令牌：如果服务端错误地允许对称算法，这个令牌就能通过校验。
    """
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT", "kid": KID}).encode())
    body = b64url(json.dumps(payload).encode())
    signing_input = f"{header}.{body}".encode()
    signature = hmac.new(public_pem.encode(), signing_input, hashlib.sha256).digest()
    return f"{header}.{body}.{b64url(signature)}"


def wait_for_port(log_path: Path, process: subprocess.Popen, timeout: float = 120.0) -> int | None:
    """从启动日志里读出 Tomcat 实际监听的端口。

    刻意让 JVM 自己选端口（--server.port=0）：本机存在「通配地址绑定失败、回环地址可绑」
    的保留端口（现象是 0.0.0.0:p 报 10048 而 127.0.0.1:p 正常）。硬编码端口会让验证工具
    在特定机器上莫名其妙地起不来，而这类环境差异不该混进业务结论。
    """
    pattern = re.compile(r"Tomcat started on port (\d+)")
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            return None
        try:
            matched = pattern.search(log_path.read_text(encoding="utf-8", errors="replace"))
        except FileNotFoundError:
            matched = None
        if matched:
            return int(matched.group(1))
        time.sleep(0.5)
    return None


def wait_ready(base_url: str, process: subprocess.Popen, timeout: float = 90.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            return False
        status, _, _ = http("GET", f"{base_url}/actuator/health", timeout=3.0)
        if status == 200:
            return True
        time.sleep(1.0)
    return False


def start_server(jar: Path, env_extra: dict, log_path: Path, args_extra: list[str] | None = None):
    env = os.environ.copy()
    env.update(
        {
            # 相对路径的默认值（../model）是按 backend/ 为工作目录设计的；
            # 这里从仓库根启动，因此显式给绝对路径，否则控制面会因为找不到模型目录而启动失败
            "DG_MODEL_DIR": str(ROOT / "model"),
            "DG_SQL_DIR": str(ROOT / "sql"),
            "DG_WEB_DIST": str(ROOT / "web" / "dist"),
            "DG_MIGRATE_ON_START": "false",
            "DG_SCHEDULER_ENABLED": "false",
            "DG_SEARCH_CONSUME_INTERVAL_MS": "0",
            "DG_LINEAGE_SIDECAR_URL": "",
        }
    )
    env.update(env_extra)
    command = ["java", "-jar", str(jar), "--server.port=0"] + (args_extra or [])
    log = log_path.open("w", encoding="utf-8")
    process = subprocess.Popen(
        command, cwd=str(ROOT), env=env, stdout=log, stderr=subprocess.STDOUT,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    return process, log


def stop_server(process: subprocess.Popen, log) -> None:
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=25)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
    log.close()


def expect_exit_nonzero(jar: Path, env_extra: dict, log_path: Path, name: str,
                        must_contain: str) -> None:
    process, log = start_server(jar, env_extra, log_path)
    try:
        try:
            code = process.wait(timeout=120)
        except subprocess.TimeoutExpired:
            fail(name, "进程没有按预期退出（危险配置被接受了）")
            return
        text = log_path.read_text(encoding="utf-8", errors="replace")
        if code == 0:
            fail(name, "退出码为 0：危险配置没有被拒绝")
        elif must_contain not in text:
            fail(name, f"退出码 {code}，但日志里没有「{must_contain}」")
        else:
            ok(name, f"退出码 {code}，日志含「{must_contain}」")
    finally:
        stop_server(process, log)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path,
                        default=ROOT / "backend" / "dg-api" / "target" / "dg-api-0.1.0.jar")
    parser.add_argument("--static-base", default="http://127.0.0.1:8081",
                        help="已在运行的控制面（静态令牌模式），用于回归检查；不可达则跳过")
    args = parser.parse_args()

    if not args.jar.exists():
        print(f"找不到 jar：{args.jar}\n先构建：cd backend; mvn -B package -DskipTests")
        return 2

    key, jwks_json, public_pem = generate_key()
    temp_dir = Path(tempfile.mkdtemp(prefix="dg-auth-e2e-"))
    jwks_file = temp_dir / "jwks.json"
    jwks_file.write_text(jwks_json, encoding="utf-8")
    jwt_log = temp_dir / "server-jwt.log"

    print(f"JWKS 文件：{jwks_file}")
    print("启动 JWT 模式控制面（端口由内核分配）…")

    process, log = start_server(
        args.jar,
        {
            "DG_AUTH_MODE": "jwt",
            "DG_JWT_ISSUER": ISSUER,
            "DG_JWT_AUDIENCE": AUDIENCE,
            "DG_JWT_JWKS_FILE": str(jwks_file),
            # 刻意改成 groups：验证配置真的接上了，而不是默认值恰好能用
            "DG_JWT_ROLES_CLAIM": "groups",
        },
        jwt_log,
    )
    try:
        port = wait_for_port(jwt_log, process)
        if port is None:
            tail = jwt_log.read_text(encoding="utf-8", errors="replace")[-3000:]
            print("控制面未能启动，日志尾部：\n" + tail)
            return 1
        base = f"http://127.0.0.1:{port}"
        print(f"控制面已监听：{base}")
        if not wait_ready(base, process):
            tail = jwt_log.read_text(encoding="utf-8", errors="replace")[-3000:]
            print("控制面未能就绪，日志尾部：\n" + tail)
            return 1

        admin = sign(key, claims("alice@corp", ["ADMIN"]))
        reader = sign(key, claims("bob@corp", ["READER"]))

        # ---- 基本认证 ----
        status, headers, body = http("GET", f"{base}/api/v1/capabilities")
        if status == 401:
            ok("无令牌 → 401", "未认证请求不能拿到任何数据")
        else:
            fail("无令牌 → 401", f"实际 {status}")
        if headers.get("WWW-Authenticate", "").lower().startswith("bearer"):
            ok("401 带 WWW-Authenticate: Bearer")
        else:
            fail("401 带 WWW-Authenticate: Bearer", f"实际 {headers.get('WWW-Authenticate')}")
        try:
            if json.loads(body).get("error") == "unauthenticated":
                ok("401 响应体是 JSON（error=unauthenticated）", "前端能区分「没登录」与「接口挂了」")
            else:
                fail("401 响应体是 JSON", body[:200])
        except json.JSONDecodeError:
            fail("401 响应体是 JSON", body[:200])

        status, _, body = http("GET", f"{base}/api/v1/capabilities", admin)
        if status == 200:
            ok("合法 JWT → 200", "RS256 + 正确 iss/aud + 有效期内")
        else:
            fail("合法 JWT → 200", f"实际 {status}: {body[:200]}")
        if "policy.oidc-auth" in body:
            ok("能力清单暴露 policy.oidc-auth", "认证是可被查询的能力，而不是隐藏配置")
        else:
            fail("能力清单暴露 policy.oidc-auth", "能力清单里没有这一项")

        # ---- 权限（认证 ≠ 授权）----
        status, _, _ = http("POST", f"{base}/api/v1/index/rebuild", reader)
        if status == 403:
            ok("READER 调重建索引 → 403", "身份对了但权限不够，必须是 403 而不是 401")
        else:
            fail("READER 调重建索引 → 403", f"实际 {status}")

        # ---- 令牌有效性 ----
        cases = [
            ("过期令牌 → 401", sign(key, claims("eve@corp", ["ADMIN"], exp=int(time.time()) - 600,
                                             nbf=int(time.time()) - 1200, iat=int(time.time()) - 1200))),
            ("nbf 在未来 → 401", sign(key, claims("eve@corp", ["ADMIN"], nbf=int(time.time()) + 300))),
            ("iss 不符 → 401", sign(key, claims("eve@corp", ["ADMIN"], iss="https://evil.example.test"))),
            ("aud 不符 → 401", sign(key, claims("eve@corp", ["ADMIN"], aud="another-app"))),
            ("未知 kid → 401", sign(key, claims("eve@corp", ["ADMIN"]), kid="unknown-kid")),
            ("alg=none → 401", unsigned_none(claims("eve@corp", ["ADMIN"]))),
            ("HS256（公钥当 HMAC 密钥）→ 401", hmac_with_public_key(public_pem, claims("eve@corp", ["ADMIN"]))),
        ]
        for name, token in cases:
            status, _, _ = http("GET", f"{base}/api/v1/capabilities", token)
            if status == 401:
                ok(name)
            else:
                fail(name, f"实际 {status}")

        tampered = admin.split(".")
        payload = json.loads(base64.urlsafe_b64decode(tampered[1] + "=="))
        payload["groups"] = ["ADMIN", "SUPERUSER"]
        payload["sub"] = "mallory@corp"
        tampered_token = ".".join([tampered[0], b64url(json.dumps(payload).encode()), tampered[2]])
        status, _, _ = http("GET", f"{base}/api/v1/capabilities", tampered_token)
        if status == 401:
            ok("载荷篡改 → 401")
        else:
            fail("载荷篡改 → 401", f"实际 {status}")

        # ---- 安全响应头 ----
        status, headers, _ = http("GET", f"{base}/api/v1/capabilities", admin)
        expected_headers = {
            "X-Content-Type-Options": "nosniff",
            "X-Frame-Options": "DENY",
            "Referrer-Policy": "no-referrer",
        }
        for header, value in expected_headers.items():
            actual = headers.get(header, "")
            if value in actual:
                ok(f"响应头 {header}: {value}")
            else:
                fail(f"响应头 {header}: {value}", f"实际 {actual!r}")
        csp = headers.get("Content-Security-Policy", "")
        if "script-src 'self'" in csp and "frame-ancestors 'none'" in csp:
            ok("CSP 生效（script-src 'self'，禁内联脚本）")
        else:
            fail("CSP 生效", f"实际 {csp!r}")

        # ---- actuator 鉴权 ----
        status, _, _ = http("GET", f"{base}/actuator/health")
        if status == 200:
            ok("actuator/health 无需令牌（探活）")
        else:
            fail("actuator/health 无需令牌", f"实际 {status}")
        status, _, _ = http("GET", f"{base}/actuator/metrics")
        if status == 401:
            ok("actuator/metrics 需要认证", "指标含内部信息，不能公开")
        else:
            fail("actuator/metrics 需要认证", f"实际 {status}")
    finally:
        stop_server(process, log)

    # ---- 生产 fail-closed ----
    print("\n生产 profile 的 fail-closed 校验（进程必须启动失败）：")
    expect_exit_nonzero(
        args.jar,
        {
            "SPRING_PROFILES_ACTIVE": "prod",
            "DG_AUTH_MODE": "static",
            "DG_ADMIN_TOKEN": "dev-admin-token",
        },
        temp_dir / "prod-dev-token.log",
        "生产 + 内置开发令牌 → 启动失败",
        "内置开发令牌",
    )
    expect_exit_nonzero(
        args.jar,
        {
            "SPRING_PROFILES_ACTIVE": "prod",
            "DG_AUTH_MODE": "jwt",
            "DG_JWT_ISSUER": ISSUER,
            "DG_JWT_JWKS_FILE": str(jwks_file),
        },
        temp_dir / "prod-no-audience.log",
        "生产 + JWT 未配 audience → 启动失败",
        "audience",
    )

    # ---- 生产 profile 正向校验：既能起来，也确实收紧了 ----
    print("\n生产 profile 正向校验（必须能启动，且收紧项真的生效）：")
    prod_log = temp_dir / "prod-ok.log"
    prod_process, prod_handle = start_server(
        args.jar,
        {
            "SPRING_PROFILES_ACTIVE": "prod",
            "DG_AUTH_MODE": "jwt",
            "DG_JWT_ISSUER": ISSUER,
            "DG_JWT_AUDIENCE": AUDIENCE,
            "DG_JWT_JWKS_FILE": str(jwks_file),
        },
        prod_log,
    )
    try:
        prod_port = wait_for_port(prod_log, prod_process)
        if prod_port is None:
            fail("生产 profile 可启动", "找不到监听端口（日志：" + prod_log.read_text(
                encoding="utf-8", errors="replace")[-500:] + "）")
        elif not wait_ready(f"http://127.0.0.1:{prod_port}", prod_process):
            fail("生产 profile 可启动", "/actuator/health 未就绪")
        else:
            prod_base = f"http://127.0.0.1:{prod_port}"
            ok("生产 profile 可启动", "严格默认值不等于起不来")
            status, _, _ = http("GET", f"{prod_base}/api/v1/capabilities",
                                sign(key, claims("prod-admin@corp", ["ADMIN"])))
            if status == 200:
                ok("生产 profile 下合法 JWT 可用")
            else:
                fail("生产 profile 下合法 JWT 可用", f"实际 {status}")
            status, _, _ = http("GET", f"{prod_base}/docs")
            if status == 404:
                ok("生产关闭 /docs（接口文档是侦察资料）")
            else:
                fail("生产关闭 /docs", f"实际 {status}")
            status, _, body = http("GET", f"{prod_base}/actuator/health")
            if status == 200 and "database" not in body.lower():
                ok("生产 health 不带详情（只回 UP/DOWN）")
            else:
                fail("生产 health 不带详情", f"实际 {status}: {body[:160]}")
            _, headers, _ = http("GET", f"{prod_base}/actuator/health")
            if "Strict-Transport-Security" in headers:
                ok("生产默认发送 HSTS（TLS 之后才生效，HTTP 下浏览器忽略）")
            else:
                fail("生产默认发送 HSTS", "响应头里没有 Strict-Transport-Security")
    finally:
        stop_server(prod_process, prod_handle)

    # ---- 静态令牌模式回归（对已在运行的控制面）----
    status, _, body = http("GET", f"{args.static_base}/api/v1/capabilities", "dev-admin-token")
    if status == 200:
        ok("静态令牌模式回归：dev-admin-token 仍可用", "换认证方式不能把开发路径弄坏")
        status, _, _ = http("GET", f"{args.static_base}/api/v1/capabilities", "wrong-token")
        if status == 401:
            ok("静态令牌模式：错误令牌 → 401")
        else:
            fail("静态令牌模式：错误令牌 → 401", f"实际 {status}")
    elif status == 0:
        skip("静态令牌模式回归", f"{args.static_base} 不可达")
    else:
        fail("静态令牌模式回归", f"实际 {status}: {body[:200]}")

    failed = [item for item in RESULTS if not item[0]]
    print(f"\n共 {len(RESULTS)} 项检查：{len(RESULTS) - len(failed)} 通过 / {len(failed)} 失败")
    if failed:
        for _, name, detail in failed:
            print(f"  - {name}: {detail}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
