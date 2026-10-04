"""数据库访问层：SQLAlchemy 2.0 engine / session 管理。

真相源是 PostgreSQL（ADR-001）。所有写操作必须在**一个事务**内同时完成
业务写入与 event_log(outbox) 追加 —— 这是 ADR-002 的核心约束。
"""

from __future__ import annotations

from contextlib import contextmanager
from typing import Iterator

from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session, sessionmaker

from dg.config import settings

engine = create_engine(
    settings.dsn_psycopg2(),
    pool_pre_ping=True,
    pool_size=5,
    max_overflow=10,
    future=True,
)

SessionLocal = sessionmaker(bind=engine, expire_on_commit=False, future=True)


@contextmanager
def session_scope() -> Iterator[Session]:
    """事务边界：成功提交，异常回滚。"""
    session = SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def healthcheck() -> dict:
    """连通性与 schema 就绪检查（供 /healthz 与 dgctl doctor 使用）。"""
    with engine.connect() as conn:
        row = conn.execute(
            text(
                """
                SELECT current_database() AS db,
                       current_user      AS usr,
                       current_setting('server_version') AS version,
                       (SELECT count(*) FROM information_schema.tables
                         WHERE table_schema = 'public') AS table_count
                """
            )
        ).one()
    return {
        "database": row.db,
        "user": row.usr,
        "server_version": row.version,
        "table_count": int(row.table_count),
    }
