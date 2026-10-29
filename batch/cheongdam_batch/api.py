"""청약홈 분양정보 API(odcloud). 인증키는 Decoding 키를 URL 인코딩해 serviceKey로 넘긴다."""

import json
import urllib.parse
from datetime import date, timedelta
from typing import Callable

from . import http

BASE = "https://api.odcloud.kr/api/ApplyhomeInfoDetailSvc/v1"
# 소스별 (공고 오퍼레이션, 주택형 오퍼레이션)
OPERATIONS = {
    "apt": ("getAPTLttotPblancDetail", "getAPTLttotPblancMdl"),
    "remndr": ("getRemndrLttotPblancDetail", "getRemndrLttotPblancMdl"),
}
PER_PAGE = 1000

Fetch = Callable[[str], bytes]


def _json_get(url: str) -> bytes:
    return http.get(url)[0]


def _query(key: str, operation: str, conds: dict[str, str], fetch: Fetch) -> list[dict]:
    rows, page = [], 1
    while True:
        params = {"serviceKey": key, "page": page, "perPage": PER_PAGE}
        params.update({f"cond[{field}]": value for field, value in conds.items()})
        body = json.loads(fetch(f"{BASE}/{operation}?{urllib.parse.urlencode(params)}"))
        rows += body["data"]
        if page * PER_PAGE >= body["matchCount"]:
            return rows
        page += 1


def fetch_notices(key: str, source: str, today: date, window_days: int, fetch: Fetch = _json_get) -> list[dict]:
    """P-11 대상: 모집공고일이 window_days 안이거나 계약 종료일이 지나지 않은 공고(합집합)."""
    operation = OPERATIONS[source][0]
    since = (today - timedelta(days=window_days)).isoformat()
    recent = _query(key, operation, {"RCRIT_PBLANC_DE::GTE": since}, fetch)
    open_contract = _query(key, operation, {"CNTRCT_CNCLS_ENDDE::GTE": today.isoformat()}, fetch)
    return list({r["HOUSE_MANAGE_NO"]: r for r in recent + open_contract}.values())


def fetch_units(key: str, source: str, house_manage_no: str, fetch: Fetch = _json_get) -> list[dict]:
    return _query(key, OPERATIONS[source][1], {"HOUSE_MANAGE_NO::EQ": house_manage_no}, fetch)
