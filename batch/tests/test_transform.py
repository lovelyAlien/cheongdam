import json
from datetime import date
from pathlib import Path

import pytest

from cheongdam_batch import transform

SAMPLES = Path(__file__).parents[2] / "docs" / "api-samples"


def sample(name):
    return json.loads((SAMPLES / name).read_text())["data"]


def test_apt_notice_row_and_rcept_range():
    raw = sample("apt_detail_sample.json")[0]
    row = transform.notice_row("apt", raw)
    assert row["notice_type"] == "general"
    assert row["house_kind"] == "private"
    assert row["is_regulated"] is False
    assert row["move_in_ym"] == "203102"
    # P-01: 특별공급 시작일 ~ 2순위 종료일 중 가장 늦은 날
    assert row["rcept_start"] == date(2026, 10, 26)
    assert row["rcept_end"] == date(2026, 10, 28)


def test_apt_schedule_rows_skip_empty_areas():
    raw = sample("apt_detail_sample.json")[0]
    rows = {(r["stage"], r["area"]) for r in transform.schedule_rows(raw)}
    assert ("rank1", "local") in rows and ("rank1", "other") in rows
    assert ("rank1", "gyeonggi") not in rows  # 기타경기 일정이 null
    assert {("winner", "all"), ("contract", "all"), ("special", "all")} <= rows


def test_remndr_no_rank_uses_subscrpt_dates():
    raw = sample("remndr_detail_sample.json")[0]
    row = transform.notice_row("remndr", raw)
    assert row["notice_type"] == "no_rank"
    assert row["house_kind"] is None and row["is_regulated"] is None
    assert (row["rcept_start"], row["rcept_end"]) == (date(2026, 10, 20), date(2026, 10, 20))
    assert ("no_rank", "all") in {(r["stage"], r["area"]) for r in transform.schedule_rows(raw)}


def test_resupply_range_prefers_earliest_start_and_general_end():
    raw = {"SPSPLY_RCEPT_BGNDE": "2026-10-02", "GNRL_RCEPT_BGNDE": "2026-10-03", "GNRL_RCEPT_ENDDE": "2026-10-05",
           "SUBSCRPT_RCEPT_BGNDE": "2026-10-09", "SUBSCRPT_RCEPT_ENDDE": "2026-10-10"}
    assert transform.rcept_range("resupply", raw) == (date(2026, 10, 2), date(2026, 10, 5))


def test_unknown_remndr_kind_is_rejected():
    with pytest.raises(ValueError):
        transform.notice_type("remndr", {"HOUSE_SECD_NM": "기타"})


def test_apt_unit_row():
    raw = sample("apt_mdl_sample.json")[0]
    unit = transform.unit_row("apt", raw)
    assert unit["house_ty"] == "075.9453"
    assert unit["exclusive_area"] == 75.9453
    assert unit["top_price_man"] == 69280
    assert unit["sp_newlywed"] == 10 and unit["sp_etc"] == 0
    # 세대수 검증(TECH_NOTES): 유형별 합 = 특별공급 합계
    assert sum(unit[k] for k in unit if k.startswith("sp_")) == unit["special_count"]


def test_remndr_unit_row_has_no_special_breakdown_and_null_supply_area():
    unit = transform.unit_row("remndr", sample("remndr_mdl_sample.json")[0])
    assert unit["supply_area"] is None and unit["sp_newlywed"] is None and unit["sp_etc"] is None


def test_zero_or_blank_price_is_null():
    base = {"MODEL_NO": "1", "HOUSE_TY": "059.9900A", "SUPLY_HSHLDCO": 1, "SPSPLY_HSHLDCO": 0}
    assert transform.unit_row("apt", {**base, "LTTOT_TOP_AMOUNT": "0"})["top_price_man"] is None
    assert transform.unit_row("apt", {**base, "LTTOT_TOP_AMOUNT": ""})["top_price_man"] is None


def test_api_hash_ignores_key_and_unit_order_but_not_values():
    n = {"a": 1, "b": 2}
    u1, u2 = {"MODEL_NO": "1", "x": 1}, {"MODEL_NO": "2", "x": 2}
    assert transform.api_hash(n, [u1, u2]) == transform.api_hash({"b": 2, "a": 1}, [u2, u1])
    assert transform.api_hash(n, [u1, u2]) != transform.api_hash(n, [u1, {**u2, "x": 3}])


def test_diff_rows_reports_notice_and_unit_changes():
    old, new = {"A": "1", "B": "x"}, {"A": "2", "B": "x"}
    ou = [{"MODEL_NO": "1", "HOUSE_TY": "084A ", "P": "10"}]
    nu = [{"MODEL_NO": "1", "HOUSE_TY": "084A ", "P": "11"}, {"MODEL_NO": "2", "HOUSE_TY": "059B "}]
    assert transform.diff_rows(old, new, ou, nu) == [
        ("A", "1", "2"), ("주택형1.P", "10", "11"), ("주택형2", None, "059B"),
    ]
