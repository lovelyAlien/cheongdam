import json
from urllib.parse import unquote_plus

import pytest

from cheongdam_batch import geocode
from cheongdam_batch.config import Config

CFG = Config(api_key="", db_dsn="", pdf_dir=None, kakao_key="k")
ADDR = "서울특별시 강남구 삼성동 1"


def doc(x, y, gu):
    return {"documents": [{"x": str(x), "y": str(y), "address": {"region_2depth_name": gu}}]}


class Kakao:
    """질의 → 응답. 등록되지 않은 질의는 결과 없음, 'boom'이면 예외."""

    def __init__(self, answers):
        self.answers, self.calls = answers, []

    def __call__(self, url, key):
        q = unquote_plus(url.split("query=")[1])
        self.calls.append(q)
        ans = self.answers.get(q, {"documents": []})
        if ans == "boom":
            raise OSError("down")
        return json.dumps(ans).encode()


def add(conn, no, address, geo_accuracy=None, geo_address=None):
    conn.execute(
        "INSERT INTO notice (house_manage_no, notice_type, source_api, name, region, address, announced_on,"
        " notice_url, raw, raw_hash, first_collected_at, last_checked_at, geo_accuracy, geo_address)"
        " VALUES (%s,'general','apt','단지','서울',%s,'2026-10-01','u','{}','h',now(),now(),%s,%s)",
        (no, address, geo_accuracy, geo_address),
    )
    conn.commit()


def row(conn, no):
    return conn.execute("SELECT lat, lng, geo_accuracy, geo_address FROM notice WHERE house_manage_no=%s", (no,)).fetchone()


@pytest.fixture(autouse=True)
def fast(monkeypatch):
    monkeypatch.setattr(geocode.Throttle, "wait", lambda self: None)


def test_exact(conn):
    add(conn, "1", ADDR)
    geocode.run(CFG, conn, Kakao({ADDR: doc(127.1, 37.5, "강남구")}))
    assert row(conn, "1") == (37.5, 127.1, "exact", ADDR)


def test_outside_district_falls_back_to_center(conn):
    add(conn, "1", ADDR)
    k = Kakao({ADDR: doc(129.0, 35.1, "해운대구"), "서울특별시 강남구": doc(127.0, 37.4, "강남구")})
    geocode.run(CFG, conn, k)
    assert row(conn, "1") == (37.4, 127.0, "district", ADDR)


def test_no_result_is_none(conn):
    add(conn, "1", ADDR)
    geocode.run(CFG, conn, Kakao({}))
    assert row(conn, "1") == (None, None, "none", ADDR)


def test_no_district_is_none_without_calls(conn):
    add(conn, "1", "서울특별시 어딘가")
    k = Kakao({})
    geocode.run(CFG, conn, k)
    assert row(conn, "1")[2] == "none" and k.calls == []


def test_failure_leaves_row_untouched(conn):
    add(conn, "1", ADDR)
    geocode.run(CFG, conn, Kakao({ADDR: "boom"}))
    assert row(conn, "1") == (None, None, None, None)


def test_changed_address_is_redone_and_done_is_skipped(conn):
    add(conn, "1", ADDR, "exact", "서울특별시 송파구 잠실동 1")
    add(conn, "2", ADDR, "exact", ADDR)
    k = Kakao({ADDR: doc(127.1, 37.5, "강남구")})
    assert geocode.run(CFG, conn, k) == 1
    assert k.calls == [ADDR] and row(conn, "1")[3] == ADDR


def test_no_key_skips(conn):
    add(conn, "1", ADDR)
    k = Kakao({})
    assert geocode.run(Config(api_key="", db_dsn="", pdf_dir=None), conn, k) == 0 and k.calls == []
