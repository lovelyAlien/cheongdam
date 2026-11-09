"""공고 주소 → 좌표(카카오 로컬 API). 수집·정정 때만 변환해 저장한다(P-21).
정확도: exact(주소 변환 성공) / district(시군구 중심) / none(시군구도 모름)."""

import json
import logging
import urllib.parse
import urllib.request
from typing import Callable

import psycopg

from .config import Config
from .http import Throttle

log = logging.getLogger("geocode")
URL = "https://dapi.kakao.com/v2/local/search/address.json"

Get = Callable[[str, str], bytes]  # (url, rest_key) -> body


def _kakao_get(url: str, key: str) -> bytes:
    req = urllib.request.Request(url, headers={"Authorization": f"KakaoAK {key}"})
    with urllib.request.urlopen(req, timeout=30) as res:
        return res.read()


def district(address: str | None) -> str | None:
    """backend util District.of와 같은 규칙: 시도 뒤의 시·군·구 토큰을 최대 2개."""
    out: list[str] = []
    for t in (address or "").split()[1:]:
        if len(out) < 2 and t.endswith(("시", "군", "구")):
            out.append(t)
        else:
            break
    return " ".join(out) or None


def _search(query: str, cfg: Config, throttle: Throttle, get: Get) -> dict | None:
    throttle.wait()
    docs = json.loads(get(f"{URL}?{urllib.parse.urlencode({'query': query})}", cfg.kakao_key)).get("documents")
    return docs[0] if docs else None


def _decide(address, cfg, throttle, get) -> tuple[float | None, float | None, str]:
    dist = district(address)
    if not dist:
        return None, None, "none"
    doc = _search(address, cfg, throttle, get)
    if doc:
        place = doc.get("road_address") or doc.get("address") or {}
        # "일원" 같은 서술형 주소는 엉뚱한 곳으로 변환될 수 있어 시군구 밖이면 믿지 않는다
        if dist.split()[0] in (place.get("region_2depth_name") or ""):
            return float(doc["y"]), float(doc["x"]), "exact"
    center = _search(f"{address.split()[0]} {dist}", cfg, throttle, get)
    if center:
        return float(center["y"]), float(center["x"]), "district"
    return None, None, "none"


def run(cfg: Config, conn: psycopg.Connection, get: Get = _kakao_get) -> int:
    """좌표가 없거나 주소가 바뀐 공고를 변환한다. 호출이 실패한 공고는 그대로 둬 다음 실행에서 다시 시도한다."""
    if not cfg.kakao_key:
        log.info("KAKAO_REST_KEY가 없어 좌표 변환을 건너뜀")
        return 0
    rows = conn.execute(
        "SELECT house_manage_no, address FROM notice WHERE removed_at IS NULL"
        " AND (geo_accuracy IS NULL OR geo_address IS DISTINCT FROM address)"
    ).fetchall()
    throttle = Throttle(0.1)  # shortcut: 고정 간격, 카카오 쿼터에 걸리면 조정
    done = 0
    for no, address in rows:
        try:
            lat, lng, accuracy = _decide(address, cfg, throttle, get)
        except Exception:
            log.exception("좌표 변환 실패 %s", no)
            continue
        conn.execute(
            "UPDATE notice SET lat=%s, lng=%s, geo_accuracy=%s, geo_address=%s WHERE house_manage_no=%s",
            (lat, lng, accuracy, address, no),
        )
        conn.commit()
        done += 1
    log.info("좌표 변환 %d/%d건", done, len(rows))
    return done
