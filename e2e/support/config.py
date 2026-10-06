"""BDD 套件的常量与默认值。

与 tools/java_e2e_verify.py 的常量语义保持一致（:41-47），但改为从环境变量读取，
因为 BDD 的 base_url 由 pytest 选项 `--base-url` 在运行时注入。
"""

from __future__ import annotations

import os
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

DEFAULT_BASE_URL = os.environ.get("DG_E2E_BASE_URL", "http://127.0.0.1:8081")

ADMIN_TOKEN = os.environ.get("DG_TOKEN", "dev-admin-token")
READER_TOKEN = os.environ.get("DG_READER_TOKEN", "dev-reader-token")
STEWARD_TOKEN = os.environ.get("DG_STEWARD_TOKEN", "dev-steward-token")

SIDECAR_URL = os.environ.get("DG_LINEAGE_SIDECAR_URL", "http://127.0.0.1:8099")

DEFAULT_NAMESPACE = os.environ.get("DG_E2E_NAMESPACE", "java_e2e")

# 控制面未就绪时打印的启动命令（与 README「快速开始」一致）
START_COMMAND = (
    "cd backend; mvn -B package -DskipTests; cd ..\n"
    '$env:DG_MODEL_DIR="$PWD\\model"; $env:DG_SQL_DIR="$PWD\\sql"; '
    '$env:DG_WEB_DIST="$PWD\\web\\dist"; $env:DG_API_PORT=\'8081\'\n'
    "java -jar backend\\dg-api\\target\\dg-api-0.1.0.jar"
)

# 外部基础设施端口（对齐 java_e2e_verify.py 的连接器清单）
CLICKHOUSE_ADDR = ("127.0.0.1", 8123)
MONGODB_ADDR = ("127.0.0.1", 27018)
SUPERSET_ADDR = ("127.0.0.1", 18089)

DB_HOST = os.environ.get("DG_DB_HOST", "localhost")
DB_PORT = int(os.environ.get("DG_DB_PORT", "25011"))
DB_USER = os.environ.get("DG_DB_USER", "postgres")
DB_PASSWORD = os.environ.get("DG_DB_PASSWORD", "root")
DB_NAME = os.environ.get("DG_DB_NAME", "dg")