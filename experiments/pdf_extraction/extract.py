"""1주차 실험: 공고문 PDF에서 자격 요건을 원문 근거(쪽 번호·문구)와 함께 뽑을 수 있는지 확인한다.

Claude에 PDF를 그대로 넣고 citations를 켜서, 요건 문장마다 원문 인용(쪽 번호 + 문구)이 붙어 오게 한다.
citations는 structured outputs와 함께 쓸 수 없으므로, 요건은 정해진 줄 형식의 텍스트로 받는다.

PDF 위치는 data/pdfs/<공고번호>/<버전>.pdf 다(docs/spec/SCHEMA.md). 정정되면 버전이 올라간다.

사용법:
    python extract.py count data/pdfs/*/1.pdf      # 토큰 수와 예상 입력 비용만 계산 (무료)
    python extract.py run data/pdfs/2026910220/1.pdf [...]   # 추출 실행 (과금)
결과는 data/extractions/<공고번호>_v<버전>.json, .md 로 저장한다.
"""

import json
import sys
import time
from pathlib import Path

import anthropic

OUT_DIR = Path("data/extractions")

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "batch"))  # 프롬프트와 파서는 배치 모듈 것을 그대로 쓴다
from cheongdam_batch.extract import (  # noqa: E402
    INPUT_PRICE_PER_MTOK, MODEL, OUTPUT_PRICE_PER_MTOK, SYSTEM, build_messages, to_records,
)


def notice_label(path: Path) -> str:
    """data/pdfs/<공고번호>/<버전>.pdf 에서 '<공고번호>_v<버전>'을 만든다. 다른 위치의 파일은 파일명을 쓴다."""
    if path.stem.isdigit() and path.parent.name.isdigit():
        return f"{path.parent.name}_v{path.stem}"
    return path.stem


def count(client: anthropic.Anthropic, paths: list[Path]) -> None:
    total = 0
    for path in paths:
        n = client.messages.count_tokens(model=MODEL, system=SYSTEM, messages=build_messages(path.read_bytes(), notice_label(path))).input_tokens
        total += n
        print(f"{notice_label(path)}: 입력 {n:,} 토큰, 예상 입력 비용 ${n * INPUT_PRICE_PER_MTOK / 1e6:.2f}")
    print(f"합계: 입력 {total:,} 토큰, 예상 입력 비용 ${total * INPUT_PRICE_PER_MTOK / 1e6:.2f} (출력·추론 토큰 별도)")


def run(client: anthropic.Anthropic, paths: list[Path]) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for path in paths:
        started = time.time()
        with client.beta.messages.stream(
            model=MODEL,
            max_tokens=64000,
            system=SYSTEM,
            messages=build_messages(path.read_bytes(), notice_label(path)),
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
