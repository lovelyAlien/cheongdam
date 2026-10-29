import os
import uuid
from pathlib import Path

import psycopg
import pytest

DSN = os.environ.get("TEST_DB_DSN", "host=127.0.0.1 port=55432 dbname=cheongdam user=cheongdam password=dev")
V1 = Path(__file__).parents[2] / "backend/src/main/resources/db/migration/V1__init.sql"


@pytest.fixture
def conn():
    """V1__init.sql을 임시 스키마에 적용한 연결. 개발 DB의 데이터는 건드리지 않는다."""
    schema = f"t_{uuid.uuid4().hex[:8]}"
    try:
        admin = psycopg.connect(DSN, autocommit=True, connect_timeout=3)
    except psycopg.OperationalError:
        pytest.skip("테스트용 Postgres에 접속할 수 없음 (./dev.sh up 또는 TEST_DB_DSN)")
    admin.execute(f"CREATE SCHEMA {schema}")
    c = psycopg.connect(DSN, options=f"-c search_path={schema},public")
    c.execute(V1.read_text())
    c.commit()
    yield c
    c.close()
    admin.execute(f"DROP SCHEMA {schema} CASCADE")
    admin.close()
