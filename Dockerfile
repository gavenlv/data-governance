# ---------------------------------------------------------------------------
# 通用数据治理平台 —— 单机镜像
# 构建：docker build -t dg-platform:0.1.0 .
# 运行见 docker-compose.yml
# ---------------------------------------------------------------------------
FROM python:3.12-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONPATH=/app/src

WORKDIR /app

# 依赖单独一层（依赖不变时复用缓存）
COPY pyproject.toml ./
RUN pip install --no-cache-dir \
        "fastapi>=0.115" "uvicorn[standard]>=0.30" "sqlalchemy>=2.0" \
        "psycopg2-binary>=2.9" "pydantic>=2.7" "pyyaml>=6.0" "pyjwt>=2.8"

# 应用代码与资产
COPY src ./src
COPY model ./model
COPY sql ./sql
COPY tools ./tools

# 非 root 运行
RUN useradd --create-home --uid 10001 dg && chown -R dg:dg /app
USER dg

EXPOSE 8080

# 健康检查：/healthz 为公开端点（不依赖认证）
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request,sys; \
sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=4).status==200 else 1)"

# 默认：初始化 schema（幂等）后启动 API
CMD ["sh", "-c", "python -m dg.cli init && python -m dg.cli serve --host 0.0.0.0 --port 8080"]
