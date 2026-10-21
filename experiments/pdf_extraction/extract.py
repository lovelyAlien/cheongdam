"""1주차 실험: 공고문 PDF에서 자격 요건을 원문 근거(쪽 번호·문구)와 함께 뽑을 수 있는지 확인한다.

Claude에 PDF를 그대로 넣고 citations를 켜서, 요건 문장마다 원문 인용(쪽 번호 + 문구)이 붙어 오게 한다.
citations는 structured outputs와 함께 쓸 수 없으므로, 요건은 정해진 줄 형식의 텍스트로 받는다.

PDF 위치는 data/pdfs/<공고번호>/<버전>.pdf 다(docs/spec/SCHEMA.md). 정정되면 버전이 올라간다.

사용법:
    python extract.py count data/pdfs/*/1.pdf      # 토큰 수와 예상 입력 비용만 계산 (무료)
    python extract.py run data/pdfs/2026910220/1.pdf [...]   # 추출 실행 (과금)
결과는 data/extractions/<공고번호>_v<버전>.json, .md 로 저장한다.
"""

import base64
import json
import re
import sys
import time
from pathlib import Path

import anthropic

MODEL = "claude-opus-5-5"
INPUT_PRICE_PER_MTOK = 4.00
OUTPUT_PRICE_PER_MTOK = 20.00
OUT_DIR = Path("data/extractions")

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


def notice_label(path: Path) -> str:
    """data/pdfs/<공고번호>/<버전>.pdf 에서 '<공고번호>_v<버전>'을 만든다. 다른 위치의 파일은 파일명을 쓴다."""
    if path.stem.isdigit() and path.parent.name.isdigit():
        return f"{path.parent.name}_v{path.stem}"
    return path.stem


def load_pdf(path: Path) -> dict:
    data = base64.standard_b64encode(path.read_bytes()).decode("ascii")
    return {
        "type": "document",
        "source": {"type": "base64", "media_type": "application/pdf", "data": data},
        "title": notice_label(path),
        "citations": {"enabled": True},
    }


def build_messages(path: Path) -> list:
    return [{"role": "user", "content": [load_pdf(path), {"type": "text", "text": INSTRUCTION}]}]


def count(client: anthropic.Anthropic, paths: list[Path]) -> None:
    total = 0
    for path in paths:
        n = client.messages.count_tokens(model=MODEL, system=SYSTEM, messages=build_messages(path)).input_tokens
        total += n
        print(f"{notice_label(path)}: 입력 {n:,} 토큰, 예상 입력 비용 ${n * INPUT_PRICE_PER_MTOK / 1e6:.2f}")
    print(f"합계: 입력 {total:,} 토큰, 예상 입력 비용 ${total * INPUT_PRICE_PER_MTOK / 1e6:.2f} (출력·추론 토큰 별도)")


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


def run(client: anthropic.Anthropic, paths: list[Path]) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for path in paths:
        started = time.time()
        with client.beta.messages.stream(
            model=MODEL,
            max_tokens=64000,
            system=SYSTEM,
            messages=build_messages(path),
            output_config={"effort": "high"},
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
        ) as stream:
            message = stream.get_final_message()
        elapsed = time.time() - started

        if message.stop_reason == "refusal":
            print(f"{notice_label(path)}: 거절됨 ({message.stop_details})")
            continue
        (OUT_DIR / f"{notice_label(path)}.raw.json").write_text(message.model_dump_json(indent=2), encoding="utf-8")
        records = to_records(message)
        usage = message.usage
        cost = (usage.input_tokens * INPUT_PRICE_PER_MTOK + usage.output_tokens * OUTPUT_PRICE_PER_MTOK) / 1e6
        result = {
            "notice": notice_label(path),
            "model": message.model,
            "stop_reason": message.stop_reason,
            "elapsed_sec": round(elapsed, 1),
            "usage": {"input_tokens": usage.input_tokens, "output_tokens": usage.output_tokens},
            "cost_usd": round(cost, 4),
            "records": records,
        }
        (OUT_DIR / f"{notice_label(path)}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        write_markdown(result)

        real = [r for r in records if r["text"] != "해당 없음"]
        cited = [r for r in real if r["citations"]]
        suspect = [r for r in real if r["unverified_numbers"]]
        print(
            f"{notice_label(path)}: 요건 {len(real)}개, 근거 있음 {len(cited)}개, 숫자 불일치 {len(suspect)}개, "
            f"입력 {usage.input_tokens:,} / 출력 {usage.output_tokens:,} 토큰, ${cost:.2f}, {elapsed:.0f}초, "
            f"stop={message.stop_reason}"
        )


def write_markdown(result: dict) -> None:
    lines = [f"# {result['notice']} 추출 결과", ""]
    section = None
    for r in result["records"]:
        if r["section"] != section:
            section = r["section"]
            lines += ["", f"## {section}", ""]
        flag = f" ⚠️ 인용에 없는 숫자: {', '.join(r['unverified_numbers'])}" if r.get("unverified_numbers") else ""
        unknown = f" ⚠️ 알 수 없는 대상: {', '.join(r['unknown_targets'])}" if r.get("unknown_targets") else ""
        label = f"[{','.join(r['targets'])}] " if r["targets"] else ""
        lines.append(f"- {label}{r['body']}{flag}{unknown}")
        for c in r["citations"]:
            page = f"p.{c['page']}" if c["page"] == c["end_page"] else f"p.{c['page']}-{c['end_page']}"
            quote = " ".join(c["text"].split())
            lines.append(f"  - {page}: \"{quote}\"")
    (OUT_DIR / f"{result['notice']}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    mode, files = sys.argv[1], [Path(p) for p in sys.argv[2:]]
    client = anthropic.Anthropic()
    {"count": count, "run": run}[mode](client, files)
