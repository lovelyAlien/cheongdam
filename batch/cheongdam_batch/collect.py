"""수집 배치 한 번의 실행: API 조회 → 신규·정정 판단 → DB 반영 → PDF 저장."""

import logging
import os
from dataclasses import dataclass
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import psycopg
from psycopg.types.json import Jsonb

from . import api, http, pdf, transform
from .config import Config

KST = ZoneInfo("Asia/Seoul")
log = logging.getLogger("collect")

_NOTICE_COLS = [
    "notice_type", "source_api", "name", "region", "region_code", "address", "house_kind", "developer",
    "announced_on", "rcept_start", "rcept_end", "winner_on", "contract_start", "contract_end", "move_in_ym",
    "total_households", "notice_url", "homepage_url", "phone", "is_regulated",
]
_UNIT_COLS = [
    "model_no", "house_ty", "exclusive_area", "supply_area", "top_price_man", "general_count", "special_count",
    "sp_newlywed", "sp_first_home", "sp_multi_child", "sp_newborn", "sp_elderly", "sp_youth", "sp_institution",
    "sp_etc",
]


@dataclass
class Deps:
    """네트워크 접근을 바꿔 끼울 수 있게 모았다(테스트용)."""

    json_get: api.Fetch = api._json_get
    pdf_get: pdf.Get = http.get


def run(cfg: Config, conn: psycopg.Connection, now: datetime | None = None, deps: Deps | None = None) -> int:
    """crawl_run 한 건을 기록하며 수집한다. 반환값은 crawl_run.id."""
    deps = deps or Deps()
    now = now or datetime.now(KST)
    throttle = http.Throttle(cfg.request_interval)
    run_id = conn.execute(
        "INSERT INTO crawl_run (started_at, status) VALUES (%s, 'running') RETURNING id", (now,)
    ).fetchone()[0]
    conn.commit()

    try:
        listing = [
            (source, raw)
            for source in api.OPERATIONS
            for raw in api.fetch_notices(cfg.api_key, source, now.date(), cfg.window_days, deps.json_get)
        ]
    except Exception as e:  # API 조회 자체가 실패하면 알림이 나가면 안 되므로 failed(P-17, E-10)
        log.exception("공고 목록 조회 실패")
        _finish(conn, run_id, "failed", 0, 0, 0, repr(e))
        return run_id

    seen = new = changed = 0
    errors: list[str] = []
    for source, raw in listing:
        no = raw.get("HOUSE_MANAGE_NO")
        try:
            units = api.fetch_units(cfg.api_key, source, no, deps.json_get)
            with conn.transaction():
                result = _process(cfg, conn, source, raw, units, now, throttle, deps.pdf_get)
            new += result == "new"
            changed += result == "changed"
        except Exception as e:  # 공고 하나의 실패로 전체를 멈추지 않는다
            log.exception("공고 처리 실패 %s", no)
            errors.append(f"{no}: {e!r}")
        seen += 1

    if listing:  # 목록이 비면 API 이상일 수 있어 '내려간 공고' 판단을 건너뛴다
        _mark_removed(conn, cfg, {raw["HOUSE_MANAGE_NO"] for _, raw in listing}, now)
    _finish(conn, run_id, "success", seen, new, changed, "\n".join(errors) or None)
    log.info("수집 완료 seen=%d new=%d changed=%d errors=%d", seen, new, changed, len(errors))
    return run_id


def _finish(conn, run_id, status, seen, new, changed, error) -> None:
    conn.execute(
        "UPDATE crawl_run SET finished_at=now(), status=%s, notice_seen=%s, notice_new=%s, notice_changed=%s, error=%s"
        " WHERE id=%s",
        (status, seen, new, changed, error, run_id),
    )
    conn.commit()


def _mark_removed(conn, cfg: Config, seen_nos: set[str], now: datetime) -> None:
    """P-16: 조회 범위 안인데 API에서 사라진 공고는 지우지 않고 removed_at만 기록한다. DEV 샘플은 건드리지 않는다."""
    conn.execute(
        "UPDATE notice SET removed_at=%s WHERE removed_at IS NULL AND house_manage_no NOT LIKE 'DEV%%'"
        " AND NOT (house_manage_no = ANY(%s))"
        " AND (announced_on >= %s OR contract_end >= %s)",
        (now, list(seen_nos), now.date() - timedelta(days=cfg.window_days), now.date()),
    )
    conn.commit()


def _process(cfg, conn, source, raw, unit_raws, now, throttle, pdf_get) -> str:
    """공고 하나를 반영한다. 반환: new / changed / same."""
    no = raw["HOUSE_MANAGE_NO"]
    row = transform.notice_row(source, raw)
    units = [transform.unit_row(source, u) for u in unit_raws]
    new_hash = transform.api_hash(raw, unit_raws)

    existing = conn.execute(
        "SELECT raw, raw_hash, current_version FROM notice WHERE house_manage_no=%s FOR UPDATE", (no,)
    ).fetchone()
    latest = (None, "pending", None, None)  # pdf_hash, pdf_status, pdf_path, pdf_file_name
    if existing:
        latest = conn.execute(
            "SELECT pdf_hash, pdf_status, pdf_path, pdf_file_name FROM notice_version"
            " WHERE house_manage_no=%s AND version=%s",
            (no, existing[2]),
        ).fetchone() or latest
    got = pdf.fetch(row["notice_url"], throttle, pdf_get)

    api_changed = existing is not None and existing[1] != new_hash
    # 이전에 PDF를 못 받은 공고가 이제 받아진 것은 정정이 아니다
    pdf_changed = existing is not None and got.status == "ok" and latest[0] is not None and latest[0] != got.sha256

    if existing is None:
        _insert_notice(conn, row, raw, new_hash, now)
        _replace_children(conn, no, raw, units, unit_raws)
        _insert_version(conn, cfg, no, 1, new_hash, got, latest, None, now)
        return "new"

    if api_changed or pdf_changed:
        version = existing[2] + 1
        change_source = "both" if api_changed and pdf_changed else "api" if api_changed else "pdf"
        if api_changed:
            old_units = [r[0] for r in conn.execute("SELECT raw FROM notice_unit WHERE house_manage_no=%s", (no,))]
            diffs = transform.diff_rows(existing[0], raw, old_units, unit_raws)
        _update_notice(conn, row, raw, new_hash, version, now)
        if api_changed:
            _replace_children(conn, no, raw, units, unit_raws)
        _insert_version(conn, cfg, no, version, new_hash, got, latest, change_source, now)
        if api_changed:
            conn.cursor().executemany(
                "INSERT INTO notice_change (house_manage_no, version, field, before_value, after_value, source)"
                " VALUES (%s,%s,%s,%s,%s,'api_diff')",
                [(no, version, *d) for d in diffs],
            )
        return "changed"

    conn.execute("UPDATE notice SET last_checked_at=%s, removed_at=NULL WHERE house_manage_no=%s", (now, no))
    # 같은 버전 안에서 PDF 상태가 좋아진 경우(받기 실패 → 성공 등)만 채워 넣는다
    if latest[1] != "ok" and got.status not in ("failed", latest[1]):
        version = existing[2]
        fields = _pdf_fields(cfg, no, version, got, latest)
        conn.execute(
            "UPDATE notice_version SET pdf_hash=%s, pdf_file_name=%s, pdf_path=%s, pdf_status=%s"
            " WHERE house_manage_no=%s AND version=%s",
            (*fields, no, version),
        )
        _after_pdf(conn, no, version, fields[3], now)
    return "same"


def _insert_notice(conn, row, raw, new_hash, now) -> None:
    cols = ["house_manage_no", *_NOTICE_COLS]
    conn.execute(
        f"INSERT INTO notice ({', '.join(cols)}, raw, raw_hash, current_version, first_collected_at, last_checked_at)"
        f" VALUES ({', '.join(['%s'] * len(cols))}, %s, %s, 1, %s, %s)",
        [row[c] for c in cols] + [Jsonb(raw), new_hash, now, now],
    )


def _update_notice(conn, row, raw, new_hash, version, now) -> None:
    sets = ", ".join(f"{c}=%s" for c in _NOTICE_COLS)
    conn.execute(
        f"UPDATE notice SET {sets}, raw=%s, raw_hash=%s, current_version=%s, last_checked_at=%s,"
        " last_changed_at=%s, removed_at=NULL, updated_at=%s WHERE house_manage_no=%s",
        [row[c] for c in _NOTICE_COLS] + [Jsonb(raw), new_hash, version, now, now, now, row["house_manage_no"]],
    )


def _replace_children(conn, no, raw, units, unit_raws) -> None:
    conn.execute("DELETE FROM notice_schedule WHERE house_manage_no=%s", (no,))
    conn.execute("DELETE FROM notice_unit WHERE house_manage_no=%s", (no,))
    cur = conn.cursor()
    cur.executemany(
        "INSERT INTO notice_schedule (house_manage_no, stage, area, start_on, end_on) VALUES (%s,%s,%s,%s,%s)",
        [(no, s["stage"], s["area"], s["start_on"], s["end_on"]) for s in transform.schedule_rows(raw)],
    )
    cur.executemany(
        f"INSERT INTO notice_unit (house_manage_no, {', '.join(_UNIT_COLS)}, raw)"
        f" VALUES (%s, {', '.join(['%s'] * len(_UNIT_COLS))}, %s)",
        [(no, *(u[c] for c in _UNIT_COLS), Jsonb(r)) for u, r in zip(units, unit_raws)],
    )


def _insert_version(conn, cfg, no, version, new_hash, got, latest, change_source, now) -> None:
    fields = _pdf_fields(cfg, no, version, got, latest)
    conn.execute(
        "INSERT INTO notice_version (house_manage_no, version, detected_at, api_hash, pdf_hash, pdf_file_name,"
        " pdf_path, pdf_status, change_source) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        (no, version, now, new_hash, *fields, change_source),
    )
    # PDF가 이전 버전과 같으면(API만 정정) 추출 결과를 복사한다
    same_pdf = version > 1 and fields[3] == "ok" and latest[0] == fields[0]
    _after_pdf(conn, no, version, fields[3], now, reuse_from=version - 1 if same_pdf else None)


def _pdf_fields(cfg: Config, no, version, got: pdf.PdfResult, latest) -> tuple:
    """notice_version에 넣을 (pdf_hash, pdf_file_name, pdf_path, pdf_status). 새 PDF 파일이면 디스크에 쓴다."""
    if got.status == "ok":
        if latest[0] == got.sha256:  # 같은 파일이면 이전 경로를 그대로 쓴다
            return got.sha256, got.file_name, latest[2], "ok"
        key = f"{no}/v{version}.pdf"
        path = cfg.pdf_dir / key
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(got.content)
        os.replace(tmp, path)
        return got.sha256, got.file_name, key, "ok"
    if latest[1] == "ok":  # 이번에 못 받았을 뿐이면 이전 PDF 정보를 유지한다
        return latest[0], latest[3], latest[2], "ok"
    return None, got.file_name, None, got.status


def _after_pdf(conn, no, version, pdf_status, now, reuse_from=None) -> None:
    """P-06: PDF가 있으면 추출 대기열에 넣고, 없거나 PDF가 아니면 요약 '실패'로 둔다."""
    if pdf_status == "ok":
        if reuse_from and _copy_extraction(conn, no, reuse_from, version):
            return  # 이전 추출이 끝나 있으면 대기열에 넣지 않는다. summary_status도 그대로 둔다
        conn.execute(
            "INSERT INTO extraction_run (house_manage_no, version, status, next_attempt_at)"
            " VALUES (%s,%s,'pending',%s) ON CONFLICT DO NOTHING",
            (no, version, now),
        )
        conn.execute("UPDATE notice SET summary_status='pending' WHERE house_manage_no=%s", (no,))
    elif pdf_status in ("none", "not_pdf"):
        conn.execute("UPDATE notice SET summary_status='failed' WHERE house_manage_no=%s", (no,))


def _copy_extraction(conn, no, from_version, to_version) -> bool:
    """끝난(done·partial) 추출 결과를 새 버전으로 복사한다. 복사했으면 True.
    복사본은 토큰·비용을 0으로 두어 비용이 이중으로 잡히지 않게 하고, reused_from_version에 원본을 남긴다."""
    copied = conn.execute(
        "INSERT INTO extraction_run (house_manage_no, version, status, attempt, model, prompt_version, page_count,"
        " input_tokens, output_tokens, cost_usd, needs_review_ratio, started_at, finished_at, reused_from_version)"
        " SELECT house_manage_no, %s, status, attempt, model, prompt_version, page_count, 0, 0, 0,"
        " needs_review_ratio, started_at, finished_at, COALESCE(reused_from_version, version)"
        " FROM extraction_run WHERE house_manage_no=%s AND version=%s AND status IN ('done','partial')",
        (to_version, no, from_version),
    ).rowcount
    if not copied:
        return False
    items = conn.execute(
        "SELECT id, category, constraint_code, targets, requirement, criteria_table, is_general_rule, status,"
        " sort_order FROM extracted_item WHERE house_manage_no=%s AND version=%s ORDER BY id",
        (no, from_version),
    ).fetchall()
    for old_id, category, code, targets, requirement, table, general, status, order in items:
        new_id = conn.execute(
            "INSERT INTO extracted_item (house_manage_no, version, category, constraint_code, targets, requirement,"
            " criteria_table, is_general_rule, status, sort_order) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)"
            " RETURNING id",
            (no, to_version, category, code, targets, requirement, None if table is None else Jsonb(table),
             general, status, order),
        ).fetchone()[0]
        conn.execute(
            "INSERT INTO item_evidence (item_id, page, quote, verified)"
            " SELECT %s, page, quote, verified FROM item_evidence WHERE item_id=%s",
            (new_id, old_id),
        )
    return True
