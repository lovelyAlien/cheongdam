from datetime import datetime, timedelta
from types import SimpleNamespace

import pytest

from cheongdam_batch import extract
from cheongdam_batch.config import Config
from .test_collect import KST, World, go, one

NOW = datetime(2026, 10, 29, 7, 0, tzinfo=KST)
PAGES = ["무주택세대구성원이어야합니다", "전매제한3년", "소득기준100%이하"]  # page_texts를 대신한다(공백 제거 형태)


def block(text, *quotes):
    cites = [SimpleNamespace(type="page_location", start_page_number=p, end_page_number=p + 1, cited_text=q)
             for p, q in quotes]
    return SimpleNamespace(type="text", text=text, citations=cites)


def message(lines, stop_reason="end_turn"):
    """lines: (분류 제목 | (요건 줄, 쪽, 인용문))의 목록을 Claude 응답 모양으로 만든다."""
    content = []
    for line in lines:
        if isinstance(line, str):
            content.append(block(f"## {line}\n"))
        else:
            text, page, quote = line
            content += [block(f"- {text}", (page, quote)), block("\n")]
    return SimpleNamespace(stop_reason=stop_reason, model="claude-opus-5-5", content=content,
                           usage=SimpleNamespace(input_tokens=1000, output_tokens=500))


GOOD = [
    "1. 공통 자격", ("[전체] 무주택 세대구성원이어야 해요", 1, "무주택 세대구성원이어야 합니다"),
    "2. 청약통장 요건", ("해당 없음", 1, "무주택 세대구성원이어야 합니다"),
    "3. 일반공급 순위 요건", ("해당 없음", 1, "무주택 세대구성원이어야 합니다"),
    "4. 특별공급 유형별 요건", ("해당 없음", 1, "무주택 세대구성원이어야 합니다"),
    "5. 소득·자산 기준", ("[newlywed] 월평균소득 100% 이하여야 해요", 3, "소득기준 100% 이하"),
    "6. 당첨 후 제약", ("[전체] 전매 제한: 3년", 2, "전매제한 3년"),
]


@pytest.fixture
def queued(conn, tmp_path, monkeypatch):
    """수집을 한 번 돌려 추출 대기열에 공고 한 건이 있는 상태를 만든다."""
    go(conn, tmp_path, World())
    monkeypatch.setattr(extract, "page_texts", lambda pdf: PAGES)
    return Config("", "", tmp_path)


def run(conn, cfg, msg, now=NOW):
    return extract.run_queue(cfg, conn, ask=lambda pdf, title: msg, now=now)


def test_success_saves_items_with_verified_evidence(conn, queued):
    assert run(conn, queued, message(GOOD)) == 1
    assert one(conn, "SELECT status, model, prompt_version, page_count, input_tokens, cost_usd::float,"
                     " needs_review_ratio::float FROM extraction_run") == ("done", "claude-opus-5-5", "1", 3, 1000, 0.014, 0)
    assert one(conn, "SELECT summary_status FROM notice") == ("done",)
    items = conn.execute("SELECT category, constraint_code, targets, requirement, status FROM extracted_item"
                         " ORDER BY sort_order").fetchall()
    assert items == [(1, None, ["전체"], "무주택 세대구성원이어야 해요", "confirmed"),
                     (5, None, ["newlywed"], "월평균소득 100% 이하여야 해요", "confirmed"),
                     (6, "resale_limit", ["전체"], "3년", "confirmed")]
    assert conn.execute("SELECT page, verified FROM item_evidence ORDER BY item_id").fetchall() == [(1, True), (3, True), (2, True)]


def test_unverified_quote_marks_item_needs_review(conn, queued):
    more = [("[전체] 무주택 세대구성원이어야 해요", 1, "무주택 세대구성원이어야 합니다")] * 4  # 비율이 한도를 넘지 않게
    lines = GOOD[:2] + more + GOOD[2:-1] + [("[전체] 전매 제한: 3년", 2, "공고문에 없는 문구 3")]
    run(conn, queued, message(lines))
    assert one(conn, "SELECT status FROM extracted_item WHERE category=6") == ("needs_review",)
    assert one(conn, "SELECT verified FROM item_evidence ORDER BY id DESC LIMIT 1") == (False,)
    assert one(conn, "SELECT status, needs_review_ratio::float FROM extraction_run") == ("done", 0.143)


def test_missing_category_is_partial(conn, queued):
    run(conn, queued, message(GOOD[:-2]))
    assert one(conn, "SELECT status FROM extraction_run") == ("partial",)
    assert one(conn, "SELECT summary_status FROM notice") == ("partial",)


def test_too_many_needs_review_fails_without_items_or_retry(conn, queued):
    bad = [("[전체] 무주택 세대구성원이어야 해요", 1, "없는 문구")] * 2
    run(conn, queued, message(["1. 공통 자격", *bad]))
    assert one(conn, "SELECT status, attempt, error FROM extraction_run")[:2] == ("failed", 1)
    assert one(conn, "SELECT count(*) FROM extracted_item") == (0,)
    assert one(conn, "SELECT summary_status FROM notice") == ("failed",)


def test_category_6_line_without_known_item_name_is_dropped_and_counted(conn, queued):
    lines = GOOD[:-1] + [("[전체] 알 수 없는 항목: 3년", 2, "전매제한 3년")]
    run(conn, queued, message(lines))
    assert one(conn, "SELECT count(*) FROM extracted_item WHERE category=6") == (0,)
    assert one(conn, "SELECT needs_review_ratio::float FROM extraction_run") == (0.333,)  # 3개 중 버려진 1개


def test_failure_retries_after_1h_then_6h_then_fails(conn, queued):
    def boom(pdf, title):
        raise OSError("api down")

    now = NOW
    for attempt, delay in ((1, timedelta(hours=1)), (2, timedelta(hours=6))):
        extract.run_queue(queued, conn, ask=boom, now=now)
        assert one(conn, "SELECT status, attempt, next_attempt_at FROM extraction_run") == ("pending", attempt, now + delay)
        assert extract.run_queue(queued, conn, ask=boom, now=now) == 0  # 아직 때가 아니다
        now += delay
    extract.run_queue(queued, conn, ask=boom, now=now)
    assert one(conn, "SELECT status, attempt FROM extraction_run") == ("failed", 3)
    assert one(conn, "SELECT summary_status FROM notice") == ("failed",)


def test_truncated_response_is_a_failed_attempt(conn, queued):
    run(conn, queued, message(GOOD, stop_reason="max_tokens"))
    assert one(conn, "SELECT status, error FROM extraction_run")[0] == "pending"
    assert one(conn, "SELECT count(*) FROM extracted_item") == (0,)


def test_stale_running_run_is_picked_up_again(conn, queued):
    conn.execute("UPDATE extraction_run SET status='running', attempt=1, started_at=%s", (NOW - timedelta(hours=2),))
    conn.commit()
    assert run(conn, queued, message(GOOD)) == 1
    assert one(conn, "SELECT status, attempt FROM extraction_run") == ("done", 2)
