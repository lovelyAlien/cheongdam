"""추출 워커: extraction_run 대기열에서 한 건씩 꺼내 공고문 PDF를 Claude에 보내고, 결과를 DB에 넣는다.

PDF를 그대로 넣고 citations를 켜서 요건마다 쪽 번호와 인용 문구를 받는다(docs/TECH_NOTES.md).
citations는 structured outputs와 함께 쓸 수 없어서 요건은 정해진 줄 형식의 텍스트로 받는다.
"""

import base64
import io
import logging
import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime, timedelta

import psycopg
from pypdf import PdfReader

from .collect import KST
from .config import Config

log = logging.getLogger("extract")

MODEL = "claude-opus-5-5"
PROMPT_VERSION = "1"  # SYSTEM·INSTRUCTION을 고치면 올린다(D-15)
INPUT_PRICE_PER_MTOK = 4.00
OUTPUT_PRICE_PER_MTOK = 20.00
RETRY_DELAYS = (timedelta(hours=1), timedelta(hours=6))  # P-13: 최초 + 1시간 뒤 + 6시간 뒤
MAX_REVIEW_RATIO = 0.30  # P-05: '확인 필요'가 이보다 많으면 공고 전체를 실패로 본다
STALE_RUNNING = timedelta(hours=1)  # 이보다 오래 running이면 워커가 죽은 것으로 보고 되돌린다

SYSTEM = """당신은 한국 아파트 청약 입주자모집공고문에서 신청 자격 요건을 정확히 뽑아내는 분석가입니다.
뽑은 내용은 일반인이 자기 자격을 스스로 점검하는 체크리스트의 재료가 됩니다.
공고문에 적힌 내용만 쓰고, 법령 일반 지식으로 채우거나 추측하지 마세요."""

INSTRUCTION = """첨부한 입주자모집공고문에서 청약 신청 자격 요건을 뽑아 주세요.

다음 분류 순서로 정리합니다. 공고에 해당 내용이 없으면 그 분류 아래에 "- 해당 없음" 한 줄만 씁니다.
1. 공통 자격 (거주지역·거주기간, 연령, 세대주 여부, 무주택 요건, 주택 소유 범위)
2. 청약통장 요건 (가입 기간, 납입 횟수, 예치금)
3. 일반공급 순위 요건 (1순위·2순위 조건, 지역 우선공급)
4. 특별공급 유형별 요건 (유형마다 따로: 기관추천, 다자녀, 신혼부부, 생애최초, 노부모부양, 신생아, 청년 등 공고에 있는 것만)
5. 소득·자산 기준 (유형별 소득 비율, 자산·자동차 기준. 가구원수별 월평균소득 금액표는 옮기지 않습니다)
6. 당첨 후 제약 (아래 7개 항목만. 공고에 없는 항목은 아예 쓰지 않습니다)
   재당첨 제한, 1순위·가점제 제한, 부적격 당첨 제한, 전매 제한, 실거주 의무, 계약금·중도금·잔금(비율과 납부 시점), 중복 청약 제한
   - 재당첨 제한: 당첨되면 일정 기간 다른 분양주택의 입주자로 선정될 수 없는 제한, 또는 그 제한을 적용받지 않는다는 안내
   - 1순위·가점제 제한: 당첨되면 일정 기간 투기과열지구·청약과열지역의 1순위 접수나 가점제 청약이 제한되는 경우(예: 5년간 1순위 접수 제한, 2년간 가점제 제한)

작성 규칙:
- 분류 제목은 "## 1. 공통 자격"처럼 씁니다.
- 요건은 한 줄에 하나씩 "- [대상] 요건" 형식으로 씁니다. [대상]은 아래 공급 유형 코드를 쉼표로 이어 쓰고(예: [general_1st], [newlywed,newborn]), 모든 공급 유형에 적용되면 [전체]라고 씁니다.
  general_1st=일반공급 1순위, general_2nd=일반공급 2순위, newlywed=신혼부부 특별공급, first_home=생애최초 특별공급, multi_child=다자녀가구 특별공급, newborn=신생아 특별공급, elderly_parent=노부모부양 특별공급, youth=청년 특별공급, institution=기관추천 특별공급(국가유공자·장애인·철거민·지역균형발전 등 포함), no_rank=무순위, resupply=불법행위 재공급
  특별공급 전체에 공통인 요건은 이 공고에 있는 특별공급 유형 코드를 모두 나열하고, 일반공급 1·2순위 공통이면 general_1st,general_2nd로 씁니다. 출산특례·혼인특례·우선공급 같은 단계나 특례는 대상이 아니라 요건 문장에 쓰고, 대상은 그 요건이 적용되는 공급 유형 코드로 씁니다. 위 코드에 없는 유형이면 [기타]라고 쓰고 요건 문장에 유형 이름을 씁니다.
- 분류 6의 요건은 "- [대상] 항목명: 내용" 형식으로 쓰고, 항목명은 위 7개 중 하나를 그대로 씁니다. 기간·대상·비율·납부 시점은 공고문 그대로 씁니다.
- 소득 기준은 "월평균소득 100% 이하"처럼 비율과 조건만 씁니다. 가구원수별 소득 금액표(3인 이하, 4인, 5인 … 열)는 숫자를 옮기지 말고, 그 표를 인용해 "가구원수별 소득 금액은 공고문의 표에서 확인해야 함" 한 줄로만 씁니다.
- 요건 문장은 한 문장, 80자 안팎으로 짧고 쉽게 씁니다. 공고문 문장을 통째로 옮기지 않습니다. 숫자·기간·비율은 공고문 그대로 씁니다.
- 「주택공급에 관한 규칙」을 옮겨 적은 일반 정의와 예외 목록(세대의 정의, 무주택으로 보는 경우, 주택 처분 기준일, 소형·저가주택 기준 등)은 뽑지 않습니다. 모든 공고에 같은 내용이라 서비스가 고정 설명으로 보여 줍니다. 이 공고에서 그 규정을 별도로 달리 정한 부분만 씁니다.
- 하나의 목록(예: 청약 신청 불가 대상)은 요건 하나로 묶어 씁니다. 표를 문장으로 옮길 때는 열 이름(예: 수도권/비수도권)을 함께 씁니다.
- 이 공고에만 해당하는 조건(거주 지역, 판단 기준일, 소득 비율, 재당첨 제한 기간 등)은 빠짐없이 씁니다.
- 각 요건은 반드시 그 요건의 숫자와 조건이 직접 적힌 공고문 부분을 인용해서 뒷받침합니다.
- 서론이나 맺음말은 쓰지 않습니다."""


TARGET_CODES = {
    "전체", "general_1st", "general_2nd", "newlywed", "first_home", "multi_child",
    "newborn", "elderly_parent", "youth", "institution", "no_rank", "resupply",
}
CONSTRAINT_CODES = {  # 분류 6 항목명 -> constraint_code (docs/spec/SCHEMA.md)
    "재당첨 제한": "rewin_limit",
    "1순위·가점제 제한": "apply_restriction",
    "부적격 당첨 제한": "ineligible_limit",
    "전매 제한": "resale_limit",
    "실거주 의무": "residence_duty",
    "계약금·중도금·잔금": "payment",
    "중복 청약 제한": "duplicate_apply",
}


def parse_requirement(text: str, section: str | None) -> dict:
    """'[대상] 항목명: 내용'을 대상 코드 목록, 제약 코드, 본문으로 나눈다. 인용 경계에서 사라진 공백도 고친다."""
    text = " ".join(text.split())
    m = re.match(r"^\[([^\]]*)\]\s*(.*)$", text)
    targets, body = ([t.strip() for t in m.group(1).split(",") if t.strip()], m.group(2)) if m else ([], text)
    constraint = None
    if section and section.startswith("6."):
        cm = re.match(r"^([^:：]+?)\s*[:：]\s*(.*)$", body)
        if cm and cm.group(1).strip() in CONSTRAINT_CODES:
            constraint, body = CONSTRAINT_CODES[cm.group(1).strip()], cm.group(2)
    return {
        "targets": targets,
        "unknown_targets": [t for t in targets if t not in TARGET_CODES],
        "constraint_code": constraint,
        "body": body,
    }



def build_messages(pdf: bytes, title: str) -> list:
    document = {
        "type": "document",
        "source": {"type": "base64", "media_type": "application/pdf", "data": base64.standard_b64encode(pdf).decode("ascii")},
        "title": title,
        "citations": {"enabled": True},
    }
    return [{"role": "user", "content": [document, {"type": "text", "text": INSTRUCTION}]}]


def to_records(message) -> list[dict]:
    """응답 text 블록들을 줄 단위 요건 레코드로 묶는다. 한 줄이 여러 블록으로 쪼개져 올 수 있다."""
    records, current = [], None
    section = None
    for block in message.content:
        if block.type != "text":
            continue
        cites = [
            {"page": c.start_page_number, "end_page": c.end_page_number - 1, "text": c.cited_text}
            for c in (block.citations or [])
            if c.type == "page_location"
        ]
        parts = block.text.split("\n")
        for i, part in enumerate(parts):
            if i > 0:  # 줄바꿈이 있으면 현재 줄을 닫는다
                current = None
            line = part.strip()
            if line.startswith("## "):
                section = line[3:].strip()
                current = None
                continue
            if line.startswith("- "):
                current = {"section": section, "text": line[2:].strip(), "citations": []}
                records.append(current)
            elif line and current is not None:
                # 인용 경계에서 블록이 나뉘므로 원래 공백을 살려 이어 붙인다
                current["text"] += part
        if current is not None:
            current["citations"].extend(cites)
    for r in records:
        r["text"] = " ".join(r["text"].split())
        r.update(parse_requirement(r["text"], r["section"]))
        r["unverified_numbers"] = unverified_numbers(r)
    return records


def unverified_numbers(record: dict) -> list[str]:
    """요건 본문의 숫자가 인용 문구에 없으면 근거가 요건을 뒷받침하지 않을 가능성이 있다. 대상 코드의 숫자는 세지 않는다."""
    cited = re.sub(r"\s", "", "".join(c["text"] for c in record["citations"]))
    return [n for n in dict.fromkeys(re.findall(r"\d+(?:[.,]\d+)?", record["body"])) if n not in cited]



class ExtractError(Exception):
    pass


def ask_claude(pdf: bytes, title: str):
    """공고문 PDF 한 건을 Claude에 보내고 최종 응답을 받는다. 키는 ANTHROPIC_API_KEY 환경변수에서 읽는다."""
    import anthropic

    with anthropic.Anthropic().beta.messages.stream(
        model=MODEL,
        max_tokens=64000,
        system=SYSTEM,
        messages=build_messages(pdf, title),
        output_config={"effort": "high"},
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
    ) as stream:
        return stream.get_final_message()


def _norm(text: str) -> str:
    """공백·줄바꿈·전각/반각 차이를 없애 비교한다(P-05)."""
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", text))


def page_texts(pdf: bytes) -> list[str]:
    return [_norm(p.extract_text() or "") for p in PdfReader(io.BytesIO(pdf)).pages]


def _is_verified(citation: dict, pages: list[str]) -> bool:
    return _norm(citation["text"]) in "".join(pages[citation["page"] - 1 : citation["end_page"]])


@dataclass
class Outcome:
    status: str  # done / partial / failed
    items: list[dict]
    review_ratio: float
    page_count: int
    error: str | None = None


def interpret(message, pdf: bytes) -> Outcome:
    """응답을 DB에 넣을 항목으로 바꾸고 근거를 검증해 상태를 정한다."""
    if message.stop_reason != "end_turn":  # refusal, max_tokens: 잘린 결과를 완료로 저장하면 안 된다
        raise ExtractError(f"stop_reason={message.stop_reason}")
    pages = page_texts(pdf)
    records = to_records(message)
    present = {int(r["section"][0]) for r in records if r["section"] and r["section"][0] in "123456"}
    real = [r for r in records if r["text"] != "해당 없음"]

    items, dropped = [], 0
    for r in real:
        category = int(r["section"][0]) if r["section"] and r["section"][0] in "123456" else None
        # 분류 6은 제약 코드가 있어야 하고 나머지 분류는 없어야 한다(스키마 CHECK). 맞지 않는 줄은 넣을 수 없다
        if category is None or (category == 6) != (r["constraint_code"] is not None):
            dropped += 1
            continue
        for c in r["citations"]:
            c["verified"] = _is_verified(c, pages)
        needs_review = (
            not r["citations"] or bool(r["unverified_numbers"]) or bool(r["unknown_targets"])
            or not all(c["verified"] for c in r["citations"])
        )
        items.append({
            "category": category,
            "constraint_code": r["constraint_code"],
            "targets": [t if t in TARGET_CODES else "기타" for t in r["targets"]] or ["전체"],
            "requirement": r["body"],
            "status": "needs_review" if needs_review else "confirmed",
            "sort_order": len(items),
            "evidence": [{"page": c["page"], "quote": " ".join(c["text"].split()), "verified": c["verified"]} for c in r["citations"]],
        })

    total = len(items) + dropped
    ratio = (dropped + sum(i["status"] == "needs_review" for i in items)) / total if total else 1.0
    if not items:
        return Outcome("failed", [], ratio, len(pages), "추출된 요건이 없음")
    if ratio > MAX_REVIEW_RATIO:
        return Outcome("failed", [], ratio, len(pages), f"확인 필요 비율 {ratio:.2f} > {MAX_REVIEW_RATIO}")
    return Outcome("done" if present == {1, 2, 3, 4, 5, 6} else "partial", items, ratio, len(pages))


def _set_summary(conn, no, version, status) -> None:
    """notice.summary_status는 현재 버전의 추출 상태를 따라간다. 직전 버전이 늦게 끝나도 덮어쓰지 않는다."""
    conn.execute("UPDATE notice SET summary_status=%s WHERE house_manage_no=%s AND current_version=%s", (status, no, version))


def _save(conn, no, version, outcome: Outcome, message, now) -> None:
    for item in outcome.items:
        item_id = conn.execute(
            "INSERT INTO extracted_item (house_manage_no, version, category, constraint_code, targets, requirement,"
            " status, sort_order) VALUES (%s,%s,%s,%s,%s,%s,%s,%s) RETURNING id",
            (no, version, item["category"], item["constraint_code"], item["targets"], item["requirement"],
             item["status"], item["sort_order"]),
        ).fetchone()[0]
        for e in item["evidence"]:
            conn.execute(
                "INSERT INTO item_evidence (item_id, page, quote, verified) VALUES (%s,%s,%s,%s)",
                (item_id, e["page"], e["quote"], e["verified"]),
            )
    usage = message.usage
    cost = (usage.input_tokens * INPUT_PRICE_PER_MTOK + usage.output_tokens * OUTPUT_PRICE_PER_MTOK) / 1e6
    conn.execute(
        "UPDATE extraction_run SET status=%s, model=%s, prompt_version=%s, page_count=%s, input_tokens=%s,"
        " output_tokens=%s, cost_usd=%s, needs_review_ratio=%s, error=%s, finished_at=%s"
        " WHERE house_manage_no=%s AND version=%s",
        (outcome.status, message.model, PROMPT_VERSION, outcome.page_count, usage.input_tokens, usage.output_tokens,
         round(cost, 4), round(outcome.review_ratio, 3), outcome.error, now, no, version),
    )
    _set_summary(conn, no, version, outcome.status)


def _fail(conn, no, version, attempt, error, now) -> None:
    """P-13: 1시간 뒤, 6시간 뒤에 다시 시도하고 그래도 안 되면 실패로 확정한다."""
    if attempt <= len(RETRY_DELAYS):
        conn.execute(
            "UPDATE extraction_run SET status='pending', next_attempt_at=%s, error=%s WHERE house_manage_no=%s AND version=%s",
            (now + RETRY_DELAYS[attempt - 1], error, no, version),
        )
    else:
        conn.execute(
            "UPDATE extraction_run SET status='failed', error=%s, finished_at=%s WHERE house_manage_no=%s AND version=%s",
            (error, now, no, version),
        )
        _set_summary(conn, no, version, "failed")


def _claim(conn, now):
    """기한이 된 pending 한 건을 running으로 바꿔 가져온다. 워커가 둘이어도 같은 건을 집지 않는다."""
    row = conn.execute(
        "UPDATE extraction_run SET status='running', attempt=attempt+1, started_at=%s, error=NULL"
        " WHERE (house_manage_no, version) = (SELECT house_manage_no, version FROM extraction_run"
        "   WHERE status='pending' AND next_attempt_at <= %s ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED)"
        " RETURNING house_manage_no, version, attempt",
        (now, now),
    ).fetchone()
    conn.commit()
    return row


def run_queue(cfg: Config, conn: psycopg.Connection, ask=ask_claude, now: datetime | None = None) -> int:
    """대기열이 빌 때까지 처리하고 처리한 건수를 돌려준다."""
    now = now or datetime.now(KST)
    # 워커가 죽어 running으로 남은 건을 되살린다. 시도 횟수는 이미 올라가 있으니 한도는 그대로 지킨다
    conn.execute(
        "UPDATE extraction_run SET status='pending', next_attempt_at=%s, error='워커 중단'"
        " WHERE status='running' AND started_at < %s",
        (now, now - STALE_RUNNING),
    )
    conn.commit()
    done = 0
    while row := _claim(conn, now):
        no, version, attempt = row
        title = f"{no}_v{version}"
        try:
            path = conn.execute(
                "SELECT pdf_path FROM notice_version WHERE house_manage_no=%s AND version=%s", (no, version)
            ).fetchone()[0]
            pdf = (cfg.pdf_dir / path).read_bytes()
            message = ask(pdf, title)
            outcome = interpret(message, pdf)
        except Exception as e:  # 한 건의 실패가 큐 전체를 멈추지 않게 한다
            log.exception("추출 실패 %s (시도 %d)", title, attempt)
            with conn.transaction():
                _fail(conn, no, version, attempt, repr(e), now)
            done += 1
            continue
        with conn.transaction():
            _save(conn, no, version, outcome, message, now)
        log.info("추출 %s: %s 항목 %d개 확인필요 %.2f", title, outcome.status, len(outcome.items), outcome.review_ratio)
        done += 1
    return done
