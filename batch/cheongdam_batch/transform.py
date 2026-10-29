"""API 응답을 DB 행으로 바꾸는 순수 함수. DB·네트워크에 접근하지 않는다."""

import hashlib
import json
import re
from datetime import date

# (stage, area, 시작 필드, 종료 필드). SCHEMA.md의 notice_schedule 매핑
_SCHEDULE_FIELDS = [
    ("special", "all", "SPSPLY_RCEPT_BGNDE", "SPSPLY_RCEPT_ENDDE"),
    ("rank1", "local", "GNRL_RNK1_CRSPAREA_RCPTDE", "GNRL_RNK1_CRSPAREA_ENDDE"),
    ("rank1", "gyeonggi", "GNRL_RNK1_ETC_GG_RCPTDE", "GNRL_RNK1_ETC_GG_ENDDE"),
    ("rank1", "other", "GNRL_RNK1_ETC_AREA_RCPTDE", "GNRL_RNK1_ETC_AREA_ENDDE"),
    ("rank2", "local", "GNRL_RNK2_CRSPAREA_RCPTDE", "GNRL_RNK2_CRSPAREA_ENDDE"),
    ("rank2", "gyeonggi", "GNRL_RNK2_ETC_GG_RCPTDE", "GNRL_RNK2_ETC_GG_ENDDE"),
    ("rank2", "other", "GNRL_RNK2_ETC_AREA_RCPTDE", "GNRL_RNK2_ETC_AREA_ENDDE"),
    ("no_rank", "all", "SUBSCRPT_RCEPT_BGNDE", "SUBSCRPT_RCEPT_ENDDE"),
    ("general", "all", "GNRL_RCEPT_BGNDE", "GNRL_RCEPT_ENDDE"),
    ("contract", "all", "CNTRCT_CNCLS_BGNDE", "CNTRCT_CNCLS_ENDDE"),
]
_SPECIAL_COUNTS = {
    "sp_newlywed": "NWWDS_HSHLDCO",
    "sp_first_home": "LFE_FRST_HSHLDCO",
    "sp_multi_child": "MNYCH_HSHLDCO",
    "sp_newborn": "NWBB_HSHLDCO",
    "sp_elderly": "OLD_PARNTS_SUPORT_HSHLDCO",
    "sp_youth": "YGMN_HSHLDCO",
    "sp_institution": "INSTT_RECOMEND_HSHLDCO",
}


def _date(value) -> date | None:
    return date.fromisoformat(value) if value else None


def _int(value) -> int | None:
    return int(value) if value not in (None, "") else None


def _num(value) -> float | None:
    return float(value) if value not in (None, "") else None


def notice_type(source_api: str, raw: dict) -> str:
    """P-02. 알 수 없는 값은 잘못 분류하지 않고 오류로 올린다."""
    if source_api == "apt":
        return "general"
    kind = raw.get("HOUSE_SECD_NM")
    if kind == "무순위":
        return "no_rank"
    if kind == "불법행위 재공급":
        return "resupply"
    raise ValueError(f"알 수 없는 무순위·잔여세대 공고 구분: {kind!r}")


def rcept_range(ntype: str, raw: dict) -> tuple[date | None, date | None]:
    """P-01 접수 기간 계산."""
    d = {k: _date(raw.get(k)) for k in raw if k.endswith(("BGNDE", "ENDDE", "RCPTDE"))}

    def pick(fn, *keys):
        vals = [d[k] for k in keys if d.get(k)]
        return fn(vals) if vals else None

    rank1_start = ("GNRL_RNK1_CRSPAREA_RCPTDE", "GNRL_RNK1_ETC_GG_RCPTDE", "GNRL_RNK1_ETC_AREA_RCPTDE")
    rank1_end = ("GNRL_RNK1_CRSPAREA_ENDDE", "GNRL_RNK1_ETC_GG_ENDDE", "GNRL_RNK1_ETC_AREA_ENDDE")
    rank2_end = ("GNRL_RNK2_CRSPAREA_ENDDE", "GNRL_RNK2_ETC_GG_ENDDE", "GNRL_RNK2_ETC_AREA_ENDDE")
    if ntype == "general":
        start = d.get("SPSPLY_RCEPT_BGNDE") or pick(min, *rank1_start)
        end = pick(max, *rank2_end) or pick(max, *rank1_end)
    elif ntype == "no_rank":
        start, end = d.get("SUBSCRPT_RCEPT_BGNDE"), d.get("SUBSCRPT_RCEPT_ENDDE")
    else:
        start = pick(min, "SPSPLY_RCEPT_BGNDE", "GNRL_RCEPT_BGNDE") or d.get("SUBSCRPT_RCEPT_BGNDE")
        end = d.get("GNRL_RCEPT_ENDDE") or d.get("SUBSCRPT_RCEPT_ENDDE")
    return start, end


def schedule_rows(raw: dict) -> list[dict]:
    rows = []
    for stage, area, start_key, end_key in _SCHEDULE_FIELDS:
        start, end = _date(raw.get(start_key)), _date(raw.get(end_key))
        if start or end:
            rows.append({"stage": stage, "area": area, "start_on": start or end, "end_on": end or start})
    winner = _date(raw.get("PRZWNER_PRESNATN_DE"))
    if winner:
        rows.append({"stage": "winner", "area": "all", "start_on": winner, "end_on": winner})
    return rows


def notice_row(source_api: str, raw: dict) -> dict:
    ntype = notice_type(source_api, raw)
    start, end = rcept_range(ntype, raw)
    move_in = raw.get("MVN_PREARNGE_YM")
    regulated = raw.get("SPECLT_RDN_EARTH_AT")
    return {
        "house_manage_no": raw["HOUSE_MANAGE_NO"],
        "notice_type": ntype,
        "source_api": source_api,
        "name": raw["HOUSE_NM"],
        "region": raw["SUBSCRPT_AREA_CODE_NM"],
        "region_code": raw.get("SUBSCRPT_AREA_CODE"),
        "address": raw.get("HSSPLY_ADRES"),
        "house_kind": {"민영": "private", "국민": "public"}.get(raw.get("HOUSE_DTL_SECD_NM")),
        "developer": raw.get("BSNS_MBY_NM"),
        "announced_on": _date(raw["RCRIT_PBLANC_DE"]),
        "rcept_start": start,
        "rcept_end": end,
        "winner_on": _date(raw.get("PRZWNER_PRESNATN_DE")),
        "contract_start": _date(raw.get("CNTRCT_CNCLS_BGNDE")),
        "contract_end": _date(raw.get("CNTRCT_CNCLS_ENDDE")),
        "move_in_ym": move_in if move_in and re.fullmatch(r"\d{6}", move_in) else None,
        "total_households": _int(raw.get("TOT_SUPLY_HSHLDCO")),
        "notice_url": raw["PBLANC_URL"],
        "homepage_url": raw.get("HMPG_ADRES"),
        "phone": raw.get("MDHS_TELNO"),
        "is_regulated": {"Y": True, "N": False}.get(regulated) if source_api == "apt" else None,
    }


def unit_row(source_api: str, raw: dict) -> dict:
    house_ty = raw["HOUSE_TY"].strip()
    match = re.match(r"\d+(?:\.\d+)?", house_ty)
    if not match:
        raise ValueError(f"전용면적을 읽을 수 없는 주택형: {house_ty!r}")
    price = _int(raw.get("LTTOT_TOP_AMOUNT"))
    row = {
        "model_no": raw["MODEL_NO"],
        "house_ty": house_ty,
        "exclusive_area": float(match.group()),
        "supply_area": _num(raw.get("SUPLY_AR")),
        "top_price_man": price or None,  # E-06: 0과 빈 값은 NULL
        "general_count": _int(raw.get("SUPLY_HSHLDCO")),
        "special_count": _int(raw.get("SPSPLY_HSHLDCO")),
    }
    if source_api == "apt":  # 무순위·재공급은 유형별 세대수가 없다(P-04)
        row.update({col: _int(raw.get(key)) or 0 for col, key in _SPECIAL_COUNTS.items()})
        row["sp_etc"] = (_int(raw.get("ETC_HSHLDCO")) or 0) + (_int(raw.get("TRANSR_INSTT_ENFSN_HSHLDCO")) or 0)
    else:
        row.update({col: None for col in (*_SPECIAL_COUNTS, "sp_etc")})
    return row


def api_hash(raw: dict, unit_raws: list[dict]) -> str:
    """공고 raw와 주택형 raw를 합친 해시. 키 순서·주택형 순서에 흔들리지 않는다."""
    units = sorted(unit_raws, key=lambda u: u["MODEL_NO"])
    payload = json.dumps({"notice": raw, "units": units}, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(payload.encode()).hexdigest()


def diff_rows(old: dict, new: dict, old_units: list[dict], new_units: list[dict]) -> list[tuple[str, str | None, str | None]]:
    """notice_change용 (항목, 이전 값, 새 값). 항목 이름은 API 필드명, 주택형은 `주택형<번호>.<필드>`."""
    out = []
    for key in sorted(old.keys() | new.keys()):
        if old.get(key) != new.get(key):
            out.append((key, _text(old.get(key)), _text(new.get(key))))
    old_by = {u["MODEL_NO"]: u for u in old_units}
    new_by = {u["MODEL_NO"]: u for u in new_units}
    for model in sorted(old_by.keys() | new_by.keys()):
        o, n = old_by.get(model), new_by.get(model)
        if o is None or n is None:
            out.append((f"주택형{model}", _text(o and o["HOUSE_TY"].strip()), _text(n and n["HOUSE_TY"].strip())))
            continue
        for key in sorted(o.keys() | n.keys()):
            if o.get(key) != n.get(key):
                out.append((f"주택형{model}.{key}", _text(o.get(key)), _text(n.get(key))))
    return out


def _text(value) -> str | None:
    return None if value is None else str(value)
