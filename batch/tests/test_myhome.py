import json
from datetime import date, datetime

import pytest

from cheongdam_batch import collect, myhome
from cheongdam_batch.config import Config
from tests.test_collect import KST, World, one


def row(pid="1496", sn="1", **over):
    base = {"pblancId": pid, "houseSn": sn, "sttusNm": "일반공고", "pblancNm": "시흥거모 공공분양 모집공고",
            "suplyInsttNm": "LH", "houseTyNm": "아파트", "rcritPblancDe": "20261103", "przwnerPresnatnDe": "20261119",
            "refrnc": "1600-1004", "url": "https://apply.lh.or.kr/x?panId=1", "pcUrl": "https://www.myhome.go.kr/p",
            "hsmpNm": "A-1블록", "brtcNm": "경기도", "signguNm": "시흥시", "fullAdres": "경기도 시흥시 거모동",
            "sumSuplyCo": "100", "beginDe": "20261111", "endDe": "20261112", "beforePblancId": ""}
    return {**base, **over}


def test_region_names_match_filter_regions():
    assert myhome.region(row(brtcNm="서울특별시")) == "서울"
    assert myhome.region(row(brtcNm="전북특별자치도")) == "전북"
    assert myhome.region(row(brtcNm="전남광주통합특별시", signguNm="광산구")) == "광주"
    assert myhome.region(row(brtcNm="전남광주통합특별시", signguNm="담양군")) == "전남"
    with pytest.raises(ValueError):
        myhome.region(row(brtcNm="없는도"))


def test_nationwide_notice_keeps_every_region():
    seoul = dict(brtcNm="서울특별시", signguNm="")
    rows = [row(sn="1", brtcNm="경상북도"), row(sn="2", **seoul), row(sn="3", brtcNm="경상북도")]
    r = myhome.notice_row(myhome.group("rent", rows)[0])
    assert (r["region"], r["regions"]) == ("경북 외 1", ["경북", "서울"])
    assert myhome.display_region(["서울"]) == "서울"
    assert myhome.display_region([f"r{i}" for i in range(16)]) == "전국"


def test_group_by_notice_and_notice_row():
    rows = [row(sn="2", hsmpNm="A-2", beginDe="20261113", endDe="20261114", sumSuplyCo="50"), row(sn="1")]
    [n] = myhome.group("sale", rows)
    assert [r["houseSn"] for r in n["rows"]] == ["1", "2"]
    r = myhome.notice_row(n)
    assert (r["house_manage_no"], r["notice_type"], r["source_api"], r["house_kind"]) == ("MHS1496", "public_sale", "myhome_sale", None)
    assert (r["region"], r["regions"], r["developer"], r["total_households"]) == ("경기", ["경기"], "LH", 150)
    assert (r["rcept_start"], r["rcept_end"], r["winner_on"]) == (date(2026, 11, 11), date(2026, 11, 14), date(2026, 11, 19))
    assert [s["stage"] for s in myhome.schedule_rows(r)] == ["general", "winner"]
    rent = myhome.notice_row(myhome.group("rent", [row(pid="21479")])[0])
    assert (rent["house_manage_no"], rent["notice_type"]) == ("MHR21479", "public_rent")
    assert (r["supply_kind"], r["before_notice_no"]) == (None, None)
    typed = myhome.notice_row(myhome.group("rent", [row(pid="21479", suplyTyNm="행복주택", beforePblancId="21465")])[0])
    assert (typed["supply_kind"], typed["before_notice_no"]) == ("행복주택", "MHR21465")


def test_fetch_rows_reads_all_pages_and_rejects_error():
    pages = {1: [row(pid=str(i)) for i in range(100)], 2: [row(pid="100")]}

    def get(url):
        page = int(url.split("pageNo=")[1])
        return json.dumps({"response": {"header": {"resultCode": "00"}, "body": {"totalCount": 101, "item": pages[page]}}}).encode()

    assert len(myhome.fetch_rows("k", "sale", get)) == 101
    bad = lambda url: json.dumps({"response": {"header": {"resultCode": "30"}}}).encode()
    with pytest.raises(RuntimeError):
        myhome.fetch_rows("k", "sale", bad)


class MyHomeWorld(World):
    def __init__(self):
        super().__init__()
        self.sale = [row()]
        self.rent = [row(pid="21479", pblancNm="행복주택 모집")]
        self.down = False

    def myhome_get(self, url):
        if self.down:
            raise OSError("down")
        items = self.sale if "ltRsdtRcritNtcList" in url else self.rent
        return json.dumps({"response": {"header": {"resultCode": "00"}, "body": {"totalCount": len(items), "item": items}}}).encode()


def go(conn, tmp_path, world, day=29):
    cfg = Config("k", "", tmp_path, request_interval=0)
    return collect.run(cfg, conn, datetime(2026, 10, day, 6, 0, tzinfo=KST),
                       collect.Deps(world.json_get, world.pdf_get, world.myhome_get))


def test_collects_new_changed_and_same(conn, tmp_path):
    w = MyHomeWorld()
    go(conn, tmp_path, w)
    assert one(conn, "SELECT notice_type, source_api, summary_status, current_version FROM notice WHERE house_manage_no='MHS1496'") == ("public_sale", "myhome_sale", "failed", 1)
    assert one(conn, "SELECT notice_type FROM notice WHERE house_manage_no='MHR21479'") == ("public_rent",)
    assert one(conn, "SELECT count(*) FROM notice_schedule WHERE house_manage_no='MHS1496'") == (2,)
    assert one(conn, "SELECT pdf_status FROM notice_version WHERE house_manage_no='MHS1496'") == ("none",)

    go(conn, tmp_path, w)  # 그대로면 버전이 오르지 않는다
    assert one(conn, "SELECT current_version FROM notice WHERE house_manage_no='MHS1496'") == (1,)

    w.sale = [row(endDe="20261120")]
    go(conn, tmp_path, w)
    assert one(conn, "SELECT current_version, rcept_end FROM notice WHERE house_manage_no='MHS1496'") == (2, date(2026, 11, 20))
    assert one(conn, "SELECT count(*) FROM notice_version WHERE house_manage_no='MHS1496'") == (2,)


def test_myhome_failure_does_not_remove_notices(conn, tmp_path):
    w = MyHomeWorld()
    go(conn, tmp_path, w)
    w.down = True
    go(conn, tmp_path, w)
    assert one(conn, "SELECT count(*) FROM notice WHERE left(house_manage_no, 2) = 'MH' AND removed_at IS NOT NULL") == (0,)
    assert "마이홈 목록 조회 실패" in one(conn, "SELECT error FROM crawl_run ORDER BY id DESC LIMIT 1")[0]


def test_notice_gone_from_myhome_is_marked_removed(conn, tmp_path):
    w = MyHomeWorld()
    go(conn, tmp_path, w)
    w.rent = [row(pid="21480")]
    go(conn, tmp_path, w)
    assert one(conn, "SELECT removed_at IS NOT NULL FROM notice WHERE house_manage_no='MHR21479'") == (True,)
    assert one(conn, "SELECT removed_at IS NOT NULL FROM notice WHERE house_manage_no='MHS1496'") == (False,)


def test_correction_notice_supersedes_previous_one(conn, tmp_path):
    w = MyHomeWorld()
    w.rent = [row(pid="21465", pblancNm="예비입주자 모집공고"),
              row(pid="21479", pblancNm="[정정공고]예비입주자 모집공고", sttusNm="정정공고", beforePblancId="21465")]
    go(conn, tmp_path, w)
    go(conn, tmp_path, w)  # 다시 돌아도 이전 공고가 되살아나지 않는다
    assert one(conn, "SELECT successor_no, before_notice_no FROM notice WHERE house_manage_no='MHR21465'") == ("MHR21479", None)
    assert one(conn, "SELECT successor_no, before_notice_no FROM notice WHERE house_manage_no='MHR21479'") == (None, "MHR21465")
