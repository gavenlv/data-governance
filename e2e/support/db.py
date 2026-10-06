"""DB 夹具：构造端到端验证所需的合成历史状态。

对齐 tools/java_e2e_verify.py:117-230。SQL 一律走 **UTF-8 临时文件** 而非 `-c`：
Windows 控制台是 GBK，含中文的 `-c` 参数会被 psql 判成非法 UTF-8（原脚本踩过这个坑）。
"""

from __future__ import annotations

import os
import subprocess
import tempfile
from pathlib import Path

from . import config


def psql_binary() -> str | None:
    override = os.environ.get("DG_PSQL")
    if override and Path(override).exists():
        return override
    candidates = [
        Path(r"C:\sandbox\tools\postgresql16\bin\psql.exe"),
        Path("/usr/bin/psql"),
        Path("/usr/local/bin/psql"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return str(candidate)
    from shutil import which
    return which("psql")


def psql_exec(psql: str, sql: str) -> bool:
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False, encoding="utf-8") as handle:
        handle.write(sql)
        path = handle.name
    env = {**os.environ, "PGPASSWORD": config.DB_PASSWORD, "PGCLIENTENCODING": "UTF8"}
    proc = subprocess.run(
        [psql, "-h", config.DB_HOST, "-p", str(config.DB_PORT), "-U", config.DB_USER,
         "-d", config.DB_NAME, "-v", "ON_ERROR_STOP=1", "-f", path],
        capture_output=True, text=True, encoding="utf-8", errors="replace", env=env,
    )
    Path(path).unlink(missing_ok=True)
    return proc.returncode == 0


def seed_metric_series(psql: str, dataset_urn: str, metric: str = "row_count") -> bool:
    """14 个点：13 个稳定 + 最后 1 个越界（驱动 MAD 检出）。"""
    sql = f"""
    DELETE FROM profile_metric
     WHERE dataset_urn = '{dataset_urn}' AND metric = '{metric}' AND column_name IS NULL;
    INSERT INTO profile_metric (dataset_urn, column_name, metric, window_start, value_num,
                                precision, precision_source, sampling_method)
    SELECT '{dataset_urn}', NULL, '{metric}', now() - (n || ' days')::interval,
           CASE WHEN n = 0 THEN 100000 ELSE 1000 END, 'EXACT', 'bdd', 'bdd_synthetic'
      FROM generate_series(0, 13) AS n;
    """
    return psql_exec(psql, sql)


def seed_short_series(psql: str, dataset_urn: str, metric: str = "e2e_short_series") -> bool:
    """只写 3 个点：验证「样本不足 → 不判定」这条分支。"""
    sql = f"""
    DELETE FROM profile_metric
     WHERE dataset_urn = '{dataset_urn}' AND metric = '{metric}' AND column_name IS NULL;
    INSERT INTO profile_metric (dataset_urn, column_name, metric, window_start, value_num,
                                precision, precision_source, sampling_method)
    SELECT '{dataset_urn}', NULL, '{metric}', now() - (n || ' days')::interval,
           CASE WHEN n = 0 THEN 99999 ELSE 10 END, 'EXACT', 'bdd', 'bdd_synthetic'
      FROM generate_series(0, 2) AS n;
    """
    return psql_exec(psql, sql)


def seed_backdated_grant(psql: str, subject: str, resource_urn: str, age_days: int) -> bool:
    """造一条「N 天前批的」授权（生命周期早于观测窗口，用于未使用判定）。"""
    sql = f"""
    DELETE FROM access_grant WHERE subject = '{subject}' AND resource_urn = '{resource_urn}';
    INSERT INTO access_grant (subject, resource_urn, granularity, permissions, purpose,
                              granted_by, granted_at, expires_at, status)
    VALUES ('{subject}', '{resource_urn}', 'DATASET', ARRAY['SELECT'], 'bdd 引擎审计验证',
            'steward@local', now() - interval '{age_days} days', now() + interval '100 days', 'ACTIVE');
    """
    return psql_exec(psql, sql)