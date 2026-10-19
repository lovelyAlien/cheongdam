"""1주차 실험: 공고문 PDF에서 자격 요건을 원문 근거(쪽 번호·문구)와 함께 뽑을 수 있는지 확인한다.

Claude에 PDF를 그대로 넣고 citations를 켜서, 요건 문장마다 원문 인용(쪽 번호 + 문구)이 붙어 오게 한다.
citations는 structured outputs와 함께 쓸 수 없으므로, 요건은 정해진 줄 형식의 텍스트로 받는다.

사용법:
    python extract.py count data/pdfs/*.pdf      # 토큰 수와 예상 입력 비용만 계산 (무료)
    python extract.py run data/pdfs/2026910220.pdf [...]   # 추출 실행 (과금)
결과는 data/extractions/<공고번호>.json, .md 로 저장한다.
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
5. 소득·자산 기준 (유형별 소득 비율, 자산·자동차 기준)
6. 제한 사항 (재당첨 제한, 전매 제한, 부적격 당첨 제한, 중복 청약)

작성 규칙:
- 분류 제목은 "## 1. 공통 자격"처럼 씁니다.
- 요건은 한 줄에 하나씩 "- [대상] 요건" 형식으로 씁니다. [대상]은 그 요건이 적용되는 공급 유형입니다(예: [전체], [일반공급 1순위], [신혼부부 특별공급]).
- 요건 문장은 한 문장, 80자 안팎으로 짧고 쉽게 씁니다. 공고문 문장을 통째로 옮기지 않습니다. 숫자·기간·비율은 공고문 그대로 씁니다.
- 「주택공급에 관한 규칙」을 옮겨 적은 일반 정의와 예외 목록(세대의 정의, 무주택으로 보는 경우, 주택 처분 기준일 등)은 항목마다 나열하지 말고 "[전체] 무주택으로 보는 예외가 있음(소형·저가주택, 60세 이상 직계존속 소유 등)"처럼 한 줄로 요약합니다.
- 이 공고에만 해당하는 조건(거주 지역, 판단 기준일, 소득 비율, 재당첨 제한 기간 등)은 빠짐없이 씁니다.
- 각 요건은 반드시 그 요건의 숫자와 조건이 직접 적힌 공고문 부분을 인용해서 뒷받침합니다.
- 서론이나 맺음말은 쓰지 않습니다."""


def load_pdf(path: Path) -> dict:
    data = base64.standard_b64encode(path.read_bytes()).decode("ascii")
    return {
        "type": "document",
        "source": {"type": "base64", "media_type": "application/pdf", "data": data},
        "title": path.stem,
        "citations": {"enabled": True},
    }


def build_messages(path: Path) -> list:
    return [{"role": "user", "content": [load_pdf(path), {"type": "text", "text": INSTRUCTION}]}]


def count(client: anthropic.Anthropic, paths: list[Path]) -> None:
    total = 0
    for path in paths:
        n = client.messages.count_tokens(model=MODEL, system=SYSTEM, messages=build_messages(path)).input_tokens
        total += n
        print(f"{path.stem}: 입력 {n:,} 토큰, 예상 입력 비용 ${n * INPUT_PRICE_PER_MTOK / 1e6:.2f}")
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
        r["unverified_numbers"] = unverified_numbers(r)
    return records


def unverified_numbers(record: dict) -> list[str]:
    """요건 문장의 숫자가 인용 문구에 없으면 근거가 요건을 뒷받침하지 않을 가능성이 있다."""
    cited = re.sub(r"\s", "", "".join(c["text"] for c in record["citations"]))
    return [n for n in dict.fromkeys(re.findall(r"\d+(?:[.,]\d+)?", record["text"])) if n not in cited]


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
            print(f"{path.stem}: 거절됨 ({message.stop_details})")
            continue
        (OUT_DIR / f"{path.stem}.raw.json").write_text(message.model_dump_json(indent=2), encoding="utf-8")
        records = to_records(message)
        usage = message.usage
        cost = (usage.input_tokens * INPUT_PRICE_PER_MTOK + usage.output_tokens * OUTPUT_PRICE_PER_MTOK) / 1e6
        result = {
            "notice": path.stem,
            "model": message.model,
            "stop_reason": message.stop_reason,
            "elapsed_sec": round(elapsed, 1),
            "usage": {"input_tokens": usage.input_tokens, "output_tokens": usage.output_tokens},
            "cost_usd": round(cost, 4),
            "records": records,
        }
        (OUT_DIR / f"{path.stem}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        write_markdown(result)

        real = [r for r in records if r["text"] != "해당 없음"]
        cited = [r for r in real if r["citations"]]
        suspect = [r for r in real if r["unverified_numbers"]]
        print(
            f"{path.stem}: 요건 {len(real)}개, 근거 있음 {len(cited)}개, 숫자 불일치 {len(suspect)}개, "
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
        lines.append(f"- {r['text']}{flag}")
        for c in r["citations"]:
            page = f"p.{c['page']}" if c["page"] == c["end_page"] else f"p.{c['page']}-{c['end_page']}"
            quote = " ".join(c["text"].split())
            lines.append(f"  - {page}: \"{quote}\"")
    (OUT_DIR / f"{result['notice']}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    mode, files = sys.argv[1], [Path(p) for p in sys.argv[2:]]
    client = anthropic.Anthropic()
    {"count": count, "run": run}[mode](client, files)
