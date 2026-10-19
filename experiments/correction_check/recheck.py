"""1주차 실험: 정정 공고가 같은 공고번호로 덮어써지는지 확인한다 (PRD 미해결 질문 1).

이전 스냅샷과 같은 조건(모집공고일 2026-09-01 이후 APT 공고)으로 API를 다시 받아,
같은 HOUSE_MANAGE_NO의 필드 값이 바뀐 공고가 있는지 비교한다.
API 값은 그대로이고 공고문 PDF만 바뀌는 경우를 잡기 위해, 상세 페이지의 PDF 링크(atchmnflSeqNo)도 함께 저장한다.

사용법:
    python experiments/correction_check/recheck.py docs/api-samples/snapshot_2026-10-17.json
결과는 docs/api-samples/snapshot_<오늘>.json 에 저장하고, 바뀐 공고를 출력한다.
"""

import datetime
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "https://api.odcloud.kr/api/ApplyhomeInfoDetailSvc/v1/getAPTLttotPblancDetail"
SINCE = "2026-09-01"
UA = {"User-Agent": "Mozilla/5.0"}
PDF_LINK = re.compile(r'href="(https://static\.applyhome\.co\.kr/ai/aia/getAtchmnfl\.do\?[^"]+)"[^>]*>\s*모집공고문 보기')


def load_key() -> str:
    for line in Path(".env").read_text().splitlines():
        if line.startswith("DATA_GO_KR_KEY="):
            return line.split("=", 1)[1].strip()
    return os.environ["DATA_GO_KR_KEY"]


def fetch_notices(key: str) -> list[dict]:
    rows, page = [], 1
    while True:
        query = urllib.parse.urlencode(
            {"serviceKey": key, "page": page, "perPage": 1000, f"cond[RCRIT_PBLANC_DE::GTE]": SINCE}
        )
        body = json.loads(urllib.request.urlopen(f"{BASE}?{query}", timeout=30).read())
        rows += body["data"]
        if page * 1000 >= body["matchCount"]:
            return rows
        page += 1


def pdf_links(url: str) -> list[str]:
    html = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read().decode("utf-8", "replace")
    return [link.replace("&amp;", "&") for link in PDF_LINK.findall(html)]


def main(old_path: Path) -> None:
    old = {r["HOUSE_MANAGE_NO"]: r for r in json.loads(old_path.read_text())}
    new_rows = fetch_notices(load_key())
    for r in new_rows:
        r["_PDF_LINKS"] = pdf_links(r["PBLANC_URL"])
        time.sleep(1)  # 청약홈 부하를 줄이려고 1초에 한 번만 요청한다
    new_rows.sort(key=lambda r: r["HOUSE_MANAGE_NO"])
    today = datetime.date.today().isoformat()
    out = old_path.with_name(f"snapshot_{today}.json")
    out.write_text(json.dumps(new_rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    new = {r["HOUSE_MANAGE_NO"]: r for r in new_rows}
    print(f"이전 {len(old)}건, 지금 {len(new)}건 → {out}")
    print(f"새로 생긴 공고: {sorted(new.keys() - old.keys())}")
    print(f"사라진 공고: {sorted(old.keys() - new.keys())}")
    for no in sorted(old.keys() & new.keys()):
        diff = {k: (old[no].get(k), new[no].get(k)) for k in old[no] if old[no].get(k) != new[no].get(k)}
        if diff:
            print(f"바뀐 공고 {no} {new[no]['HOUSE_NM']}: {diff}")
    multi = {no: r["_PDF_LINKS"] for no, r in new.items() if len(r["_PDF_LINKS"]) != 1}
    print(f"PDF 링크가 1개가 아닌 공고: {multi}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
