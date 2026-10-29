import json
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from cheongdam_batch import collect
from cheongdam_batch.config import Config

KST = ZoneInfo("Asia/Seoul")
HTML = ('<a href="https://static.applyhome.co.kr/ai/aia/getAtchmnfl.do?houseManageNo=1&amp;seq=1" class="x">'
        "모집공고문 보기</a>").encode()


def apt_notice(no="2026000001", **over):
    raw = {"HOUSE_MANAGE_NO": no, "PBLANC_NO": no, "HOUSE_NM": "테스트단지", "HOUSE_SECD_NM": "APT",
           "HOUSE_DTL_SECD_NM": "민영", "SUBSCRPT_AREA_CODE_NM": "부산", "RCRIT_PBLANC_DE": "2026-10-20",
           "SPSPLY_RCEPT_BGNDE": "2026-10-30", "SPSPLY_RCEPT_ENDDE": "2026-10-30",
           "GNRL_RNK1_CRSPAREA_RCPTDE": "2026-11-02", "GNRL_RNK1_CRSPAREA_ENDDE": "2026-11-02",
           "PRZWNER_PRESNATN_DE": "2026-11-10", "CNTRCT_CNCLS_BGNDE": "2026-11-20", "CNTRCT_CNCLS_ENDDE": "2026-11-22",
           "PBLANC_URL": "https://www.applyhome.co.kr/ai/aia/selectAPTLttotPblancDetail.do?houseManageNo=1",
           "TOT_SUPLY_HSHLDCO": 10, "MVN_PREARNGE_YM": "203101", "SPECLT_RDN_EARTH_AT": "N"}
    return {**raw, **over}


def apt_unit(no="2026000001", price="50000"):
    return {"HOUSE_MANAGE_NO": no, "MODEL_NO": "01", "HOUSE_TY": "084.8860A ", "LTTOT_TOP_AMOUNT": price,
            "SUPLY_AR": "110.5", "SUPLY_HSHLDCO": 6, "SPSPLY_HSHLDCO": 4, "NWWDS_HSHLDCO": 4}


class World:
    """청약홈을 흉내 낸다: 공고·주택형·PDF 내용을 바꿔 가며 run을 여러 번 돌린다."""

    def __init__(self):
        self.notices = [apt_notice()]
        self.units = {"2026000001": [apt_unit()]}
        self.pdf = b"%PDF-1.4 v1"
        self.api_down = False

    def json_get(self, url):
        if self.api_down:
            raise OSError("api down")
        if "getAPTLttotPblancDetail" in url:
            data = self.notices
        elif "getAPTLttotPblancMdl" in url:
            no = url.split("HOUSE_MANAGE_NO%3A%3AEQ%5D=")[1].split("&")[0]
            data = self.units.get(no, [])
        else:  # 무순위·잔여세대는 비어 있다
            data = []
        return json.dumps({"data": data, "matchCount": len(data)}).encode()

    def pdf_get(self, url):
        return (HTML, {}) if "selectAPT" in url else (self.pdf, {})


@pytest.fixture
def world():
    return World()


def go(conn, tmp_path, world, day=29):
    cfg = Config("k", "", tmp_path, request_interval=0)
    return collect.run(cfg, conn, datetime(2026, 10, day, 6, 0, tzinfo=KST),
                       collect.Deps(world.json_get, world.pdf_get))


def one(conn, sql, *args):
    return conn.execute(sql, args).fetchone()


def test_first_run_inserts_everything_and_queues_extraction(conn, tmp_path, world):
    run_id = go(conn, tmp_path, world)
    assert one(conn, "SELECT status, notice_seen, notice_new, notice_changed FROM crawl_run WHERE id=%s", run_id) \
        == ("success", 1, 1, 0)
    assert one(conn, "SELECT notice_type, rcept_start, rcept_end, current_version, summary_status, last_changed_at"
                     " FROM notice") == ("general", datetime(2026, 10, 30).date(), datetime(2026, 11, 2).date(),
                                         1, "pending", None)
    assert one(conn, "SELECT count(*) FROM notice_schedule")[0] == 4  # special, rank1, winner, contract
    assert one(conn, "SELECT top_price_man, exclusive_area::float, sp_newlywed FROM notice_unit") == (50000, 84.886, 4)
    assert one(conn, "SELECT pdf_status, pdf_path, change_source FROM notice_version") == ("ok", "2026000001/v1.pdf", None)
    assert (tmp_path / "2026000001/v1.pdf").read_bytes() == world.pdf
    assert one(conn, "SELECT status FROM extraction_run") == ("pending",)


def test_rerun_without_change_only_touches_last_checked(conn, tmp_path, world):
    go(conn, tmp_path, world)
    run_id = go(conn, tmp_path, world, day=29)
    assert one(conn, "SELECT notice_new, notice_changed FROM crawl_run WHERE id=%s", run_id) == (0, 0)
    assert one(conn, "SELECT count(*) FROM notice_version")[0] == 1


def test_api_correction_creates_version_and_diff(conn, tmp_path, world):
    go(conn, tmp_path, world)
    world.units["2026000001"] = [apt_unit(price="52000")]
    world.notices = [apt_notice(PRZWNER_PRESNATN_DE="2026-11-12")]
    run_id = go(conn, tmp_path, world)
    assert one(conn, "SELECT notice_changed FROM crawl_run WHERE id=%s", run_id) == (1,)
    assert one(conn, "SELECT current_version, winner_on, last_changed_at IS NOT NULL FROM notice") \
        == (2, datetime(2026, 11, 12).date(), True)
    assert one(conn, "SELECT change_source, pdf_path FROM notice_version WHERE version=2") \
        == ("api", "2026000001/v1.pdf")  # PDF가 같으면 파일을 새로 쓰지 않는다
    assert one(conn, "SELECT top_price_man FROM notice_unit")[0] == 52000
    changes = sorted(conn.execute("SELECT field, before_value, after_value FROM notice_change").fetchall())
    assert changes == [("PRZWNER_PRESNATN_DE", "2026-11-10", "2026-11-12"),
                       ("주택형01.LTTOT_TOP_AMOUNT", "50000", "52000")]


def test_pdf_only_correction_is_detected(conn, tmp_path, world):
    go(conn, tmp_path, world)
    world.pdf = b"%PDF-1.4 corrected"
    go(conn, tmp_path, world)
    assert one(conn, "SELECT current_version FROM notice")[0] == 2
    assert one(conn, "SELECT change_source, pdf_path FROM notice_version WHERE version=2") == ("pdf", "2026000001/v2.pdf")
    assert (tmp_path / "2026000001/v2.pdf").read_bytes() == world.pdf
    assert one(conn, "SELECT count(*) FROM notice_change")[0] == 0
    assert one(conn, "SELECT count(*) FROM extraction_run")[0] == 2


def test_missing_pdf_link_marks_summary_failed(conn, tmp_path, world):
    world.pdf_get = lambda url: (b"LH link only", {})
    go(conn, tmp_path, world)
    assert one(conn, "SELECT summary_status FROM notice")[0] == "failed"
    assert one(conn, "SELECT pdf_status FROM notice_version")[0] == "none"
    assert one(conn, "SELECT count(*) FROM extraction_run")[0] == 0


def test_pdf_failure_then_recovery_is_not_a_correction(conn, tmp_path, world):
    good = world.pdf_get

    def broken(url):
        raise OSError("timeout")
    world.pdf_get = broken
    go(conn, tmp_path, world)
    assert one(conn, "SELECT pdf_status FROM notice_version")[0] == "failed"
    world.pdf_get = good
    go(conn, tmp_path, world)
    assert one(conn, "SELECT current_version FROM notice")[0] == 1
    assert one(conn, "SELECT pdf_status, pdf_path FROM notice_version") == ("ok", "2026000001/v1.pdf")
    assert one(conn, "SELECT count(*) FROM extraction_run")[0] == 1


def test_disappeared_notice_is_flagged_not_deleted_and_dev_seed_untouched(conn, tmp_path, world):
    go(conn, tmp_path, world)
    conn.execute("INSERT INTO notice (house_manage_no, notice_type, source_api, name, region, announced_on, notice_url,"
                 " raw, raw_hash, first_collected_at, last_checked_at) VALUES ('DEV1','general','apt','샘플','서울',"
                 "'2026-10-20','u','{}','h',now(),now())")
    conn.commit()
    world.notices = [apt_notice("2026000002")]
    world.units["2026000002"] = [apt_unit("2026000002")]
    go(conn, tmp_path, world)
    removed = dict(conn.execute("SELECT house_manage_no, removed_at IS NOT NULL FROM notice").fetchall())
    assert removed == {"2026000001": True, "2026000002": False, "DEV1": False}


def test_api_down_marks_run_failed(conn, tmp_path, world):
    world.api_down = True
    run_id = go(conn, tmp_path, world)
    assert one(conn, "SELECT status, finished_at IS NOT NULL FROM crawl_run WHERE id=%s", run_id) == ("failed", True)
    assert one(conn, "SELECT count(*) FROM notice")[0] == 0


def test_one_bad_notice_does_not_stop_the_run(conn, tmp_path, world):
    world.notices = [apt_notice("2026000009", RCRIT_PBLANC_DE=None), apt_notice()]
    run_id = go(conn, tmp_path, world)
    status, seen, new, error = one(conn, "SELECT status, notice_seen, notice_new, error FROM crawl_run WHERE id=%s", run_id)
    assert (status, seen, new) == ("success", 2, 1) and "2026000009" in error


def finish_extraction(conn, no="2026000001", version=1, status="done"):
    """추출 워커가 끝낸 것처럼 결과를 넣는다."""
    conn.execute("UPDATE extraction_run SET status=%s, model='m', prompt_version='p1', page_count=40,"
                 " input_tokens=100000, output_tokens=2000, cost_usd=1.2 WHERE house_manage_no=%s AND version=%s",
                 (status, no, version))
    conn.execute("UPDATE notice SET summary_status='done' WHERE house_manage_no=%s", (no,))
    for category, code, table in ((1, None, None), (6, "resale_limit", '{"rows": [1]}')):
        item_id = conn.execute(
            "INSERT INTO extracted_item (house_manage_no, version, category, constraint_code, targets, requirement,"
            " criteria_table, status) VALUES (%s,%s,%s,%s,'{전체}','요건',%s,'confirmed') RETURNING id",
            (no, version, category, code, table)).fetchone()[0]
        conn.execute("INSERT INTO item_evidence (item_id, page, quote, verified) VALUES (%s, 3, '인용', true)", (item_id,))
    conn.commit()


def api_correct(world, winner):
    world.notices = [apt_notice(PRZWNER_PRESNATN_DE=winner)]


def test_api_only_correction_copies_finished_extraction(conn, tmp_path, world):
    go(conn, tmp_path, world)
    finish_extraction(conn)
    api_correct(world, "2026-11-12")
    go(conn, tmp_path, world)
    assert one(conn, "SELECT status, reused_from_version, input_tokens, cost_usd::float, model"
                     " FROM extraction_run WHERE version=2") == ("done", 1, 0, 0.0, "m")
    assert one(conn, "SELECT count(*) FROM extracted_item WHERE version=2")[0] == 2
    assert one(conn, "SELECT count(*), bool_and(i.version=2) FROM item_evidence e JOIN extracted_item i"
                     " ON i.id=e.item_id WHERE i.version=2") == (2, True)
    assert one(conn, "SELECT criteria_table FROM extracted_item WHERE version=2 AND category=6")[0] == {"rows": [1]}
    assert one(conn, "SELECT summary_status FROM notice")[0] == "done"  # 다시 정리 중으로 돌아가지 않는다


def test_reuse_chain_points_to_original_run(conn, tmp_path, world):
    go(conn, tmp_path, world)
    finish_extraction(conn)
    api_correct(world, "2026-11-12")
    go(conn, tmp_path, world)
    api_correct(world, "2026-11-13")
    go(conn, tmp_path, world)
    assert one(conn, "SELECT reused_from_version FROM extraction_run WHERE version=3")[0] == 1


def test_changed_pdf_is_extracted_again(conn, tmp_path, world):
    go(conn, tmp_path, world)
    finish_extraction(conn)
    world.pdf = b"%PDF-1.4 corrected"
    go(conn, tmp_path, world)
    assert one(conn, "SELECT status, reused_from_version FROM extraction_run WHERE version=2") == ("pending", None)
    assert one(conn, "SELECT count(*) FROM extracted_item WHERE version=2")[0] == 0
    assert one(conn, "SELECT summary_status FROM notice")[0] == "pending"


def test_unfinished_or_failed_previous_extraction_is_not_copied(conn, tmp_path, world):
    go(conn, tmp_path, world)
    finish_extraction(conn, status="failed")
    api_correct(world, "2026-11-12")
    go(conn, tmp_path, world)
    assert one(conn, "SELECT status, reused_from_version FROM extraction_run WHERE version=2") == ("pending", None)
