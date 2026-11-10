"""국토교통부 마이홈포털 공공주택 모집공고 API(공공분양·공공임대). 인증키는 청약홈과 같은 DATA_GO_KR_KEY(두 API를 같은 계정에서 활용신청).

한 공고(pblancId)에 단지(houseSn)가 여러 행으로 온다. 공고 하나를 notice 한 건으로 만들고 단지 행은 raw에 둔다.
공고문 PDF와 주택형 정보는 응답에 없어서 만들지 않는다(원문 링크만 보여 준다).
"""

import hashlib
import json
import urllib.parse
from datetime import date, datetime

from . import http

BASE = "https://apis.data.go.kr/1613000/HWSPR02"
OPERATIONS = {"sale": "ltRsdtRcritNtcList", "rent": "rsdtRcritNtcList"}  # 공공분양, 공공임대
PREFIX = {"sale": "MHS", "rent": "MHR"}  # notice.house_manage_no 접두어. 청약홈 번호와 겹치지 않는다
PER_PAGE = 100

# 광역시도명 → 청약홈 SUBSCRPT_AREA_CODE_NM과 같은 약칭(필터 REGIONS와 맞춘다)
REGIONS = {
    "서울특별시": "서울", "부산광역시": "부산", "대구광역시": "대구", "인천광역시": "인천", "광주광역시": "광주",
    "대전광역시": "대전", "울산광역시": "울산", "세종특별자치시": "세종", "경기도": "경기",
    "강원특별자치도": "강원", "강원도": "강원", "충청북도": "충북", "충청남도": "충남",
    "전북특별자치도": "전북", "전라북도": "전북", "전라남도": "전남", "경상북도": "경북", "경상남도": "경남",
    "제주특별자치도": "제주",
}


def _json_get(url: str) -> bytes:
    return http.get(url)[0]


def fetch_rows(key: str, kind: str, fetch=_json_get) -> list[dict]:
    rows, page = [], 1
    while True:
        query = urllib.parse.urlencode({"serviceKey": key, "numOfRows": PER_PAGE, "pageNo": page})
        body = json.loads(fetch(f"{BASE}/{OPERATIONS[kind]}?{query}"))["response"]
        if body["header"]["resultCode"] != "00":
            raise RuntimeError(f"마이홈 API 오류 {body['header']}")
        body = body["body"]
        items = body.get("item") or []
        rows += [items] if isinstance(items, dict) else items
        if page * PER_PAGE >= int(body["totalCount"]):
            return rows
        page += 1


def group(kind: str, rows: list[dict]) -> list[dict]:
    """공고ID별로 묶는다. 반환: [{"kind", "rows": [단지 행, houseSn 순]}]"""
    by_id: dict[str, list[dict]] = {}
    for r in rows:
        by_id.setdefault(r["pblancId"], []).append(r)
    return [{"kind": kind, "rows": sorted(rs, key=lambda r: int(r["houseSn"]))} for rs in by_id.values()]


def fetch_notices(key: str, fetch=_json_get) -> list[dict]:
    return [n for kind in OPERATIONS for n in group(kind, fetch_rows(key, kind, fetch))]


def _date(value) -> date | None:
    return datetime.strptime(value, "%Y%m%d").date() if value else None


def _int(value) -> int | None:
    return int(value) if value not in (None, "") else None


def region(row: dict) -> str:
    name = row["brtcNm"]
    if name == "전남광주통합특별시":  # 광주와 전남이 합쳐진 이름. 시군구가 '구'면 광주, 아니면 전남
        return "광주" if row["signguNm"].endswith("구") else "전남"
    if name not in REGIONS:
        raise ValueError(f"알 수 없는 광역시도: {name!r}")
    return REGIONS[name]


def display_region(regions: list[str]) -> str:
    """목록·메일에 보일 지역. 시도 16곳(전남광주 통합 뒤 전부)이면 전국, 여러 곳이면 'A 외 N'."""
    if len(regions) == 1:
        return regions[0]
    return "전국" if len(regions) >= 16 else f"{regions[0]} 외 {len(regions) - 1}"


def house_manage_no(raw: dict) -> str:
    return f"{PREFIX[raw['kind']]}{raw['rows'][0]['pblancId']}"


def notice_row(raw: dict) -> dict:
    rows, first = raw["rows"], raw["rows"][0]
    starts = [d for d in (_date(r["beginDe"]) for r in rows) if d]
    ends = [d for d in (_date(r["endDe"]) for r in rows) if d]
    regions = list(dict.fromkeys(region(r) for r in rows))  # LH 전세임대 같은 전국 공고는 시도마다 행이 온다
    counts = [c for c in (_int(r.get("sumSuplyCo")) for r in rows) if c is not None]
    return {
        "house_manage_no": house_manage_no(raw),
        "notice_type": "public_sale" if raw["kind"] == "sale" else "public_rent",
        "source_api": f"myhome_{raw['kind']}",
        "name": first["pblancNm"],
        "region": display_region(regions),
        "regions": regions,
        "region_code": None,
        "address": first.get("fullAdres") or None,
        "house_kind": None,  # 청약 분양의 민영/국민 구분은 공공 공고에 쓰지 않는다
        "developer": first.get("suplyInsttNm") or None,
        "announced_on": _date(first["rcritPblancDe"]),
        "rcept_start": min(starts) if starts else None,
        "rcept_end": max(ends) if ends else None,
        "winner_on": _date(first.get("przwnerPresnatnDe")),
        "contract_start": None,
        "contract_end": None,
        "move_in_ym": None,
        "total_households": sum(counts) if counts else None,
        "notice_url": first["url"],
        "homepage_url": first.get("pcUrl") or None,
        "phone": first.get("refrnc") or None,
        "is_regulated": None,
        "supply_kind": first.get("suplyTyNm") or None,
        "before_notice_no": PREFIX[raw["kind"]] + first["beforePblancId"] if first.get("beforePblancId") else None,
    }


def schedule_rows(row: dict) -> list[dict]:
    out = []
    if row["rcept_start"] and row["rcept_end"]:
        out.append({"stage": "general", "area": "all", "start_on": row["rcept_start"], "end_on": row["rcept_end"]})
    if row["winner_on"]:
        out.append({"stage": "winner", "area": "all", "start_on": row["winner_on"], "end_on": row["winner_on"]})
    return out


def api_hash(raw: dict) -> str:
    return hashlib.sha256(json.dumps(raw, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
