# 수집 배치 (Python)

청약홈 분양정보 API에서 공고·주택형을 수집해 DB에 넣고, 공고문 PDF를 받아 정정을 감지한다. 담당 A. 스키마는 `backend`(Flyway)가 소유하며 이 배치는 쓰기만 한다.

## 흐름

1. `crawl_run`을 `running`으로 연다.
2. APT와 무순위·잔여세대 API에서 대상 공고를 받는다(P-11: 모집공고일 60일 이내 ∪ 계약 종료일이 지나지 않은 공고). 공고마다 주택형을 받는다.
3. 공고 raw + 주택형 raw의 해시(`raw_hash`)로 신규·정정을 판단한다.
4. 상세 페이지에서 "모집공고문 보기" 링크를 찾아 PDF를 받는다(요청 사이 1초, P-12). `%PDF`가 아니면 `not_pdf`, 링크가 없으면 `none`.
5. 신규이거나 API·PDF 해시가 바뀌면 `notice_version`을 추가한다. API가 바뀌면 `notice_change`(`api_diff`)에 이전 값 → 새 값을 남긴다. PDF가 있으면 `extraction_run`을 `pending`으로 넣는다(같은 PDF의 API 정정은 아래 "알아 둘 점" 참고).
6. 범위 안인데 API에서 사라진 공고는 `removed_at`만 기록한다(P-16). `DEV%` 샘플은 건드리지 않는다.
7. `crawl_run`을 닫는다. 목록 조회 자체가 실패하면 `failed`, 공고 몇 건만 실패하면 `success`로 닫고 `error`에 건수와 사유를 남긴다.

PDF는 `$PDF_DIR/<공고번호>/v<버전>.pdf`에 저장하고 `notice_version.pdf_path`에는 `PDF_DIR` 기준 상대 경로를 넣는다. API만 바뀌고 PDF가 같으면 새로 쓰지 않고 이전 경로를 가리킨다.

## 추출 워커

`python -m cheongdam_batch --extract`가 `extraction_run`의 `pending` 행을 한 건씩 꺼내 PDF를 Claude에 보내고(`extract.py`), 요건을 `extracted_item`·`item_evidence`에 넣는다. 수집과 따로 돌고(`EXTRACT_POLL_SECONDS`마다 대기열 확인, 기본 300초) `ANTHROPIC_API_KEY`가 필요하다. 로컬에서 한 번만 돌리려면 `./dev.sh extract`.

- 근거 인용은 PDF 쪽별 텍스트(`pypdf`)와 공백·전각 차이를 없애고 비교해 `verified`를 정한다(P-05). 인용이 없거나 확인되지 않거나 요건 숫자가 인용에 없거나 알 수 없는 대상 코드면 `needs_review`.
- `needs_review`(와 스키마에 넣을 수 없어 버린 줄)가 30%를 넘으면 `failed`로 확정하고 항목은 저장하지 않는다. 6개 분류 중 일부가 응답에 없으면 `partial`, 모두 있으면 `done`.
- 호출이 실패하거나 응답이 잘리면(`stop_reason`이 `end_turn`이 아님) 1시간 뒤, 6시간 뒤에 다시 시도하고 세 번째 실패에서 `failed`(P-13). 워커가 죽어 1시간 넘게 `running`인 행은 `pending`으로 되돌린다.
- `notice.summary_status`는 현재 버전(`current_version`)의 추출이 끝날 때만 갱신한다.
- 지시문(`SYSTEM`·`INSTRUCTION`)을 고치면 `PROMPT_VERSION`을 올린다. 실험 스크립트(`experiments/pdf_extraction/extract.py`)도 같은 지시문을 쓴다.

## 실행

| 환경변수 | 설명 |
|---|---|
| `DATA_GO_KR_KEY` | 공공데이터포털 **Decoding** 인증키 (필수) |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | 기본값 `db` `5432` `cheongdam` `cheongdam` (비밀번호만 필수). `DB_DSN`으로 한 번에 지정도 가능 |
| `PDF_DIR` | 기본 `/data/pdfs` |

```bash
python -m cheongdam_batch --once   # 한 번 수집
python -m cheongdam_batch          # 스케줄러: 매일 06:00, 18:00 KST (P-10)
```

로컬: `./dev.sh up` 후 `./dev.sh batch` (루트 `.env`에 `DATA_GO_KR_KEY`).

## 테스트

```bash
pip install -r requirements-dev.txt
pytest   # 통합 테스트는 ./dev.sh up의 DB(127.0.0.1:55432)에 임시 스키마를 만들어 쓴다. 접속 못 하면 건너뜀(TEST_DB_DSN으로 변경)
```

## 알아 둘 점

- PDF 정정은 파일 해시로만 알 수 있어, 대상 공고의 PDF를 매 수집마다 받는다(공고당 요청 2회). 범위가 넓어지면 첨부파일 번호로 건너뛰는 최적화를 검토한다.
- PDF가 같은 API 정정은 이전 버전의 끝난(`done`·`partial`) 추출 결과를 새 버전으로 복사한다(`extraction_run`, `extracted_item`, `item_evidence`). 복사본은 토큰·비용 0, `reused_from_version`에 LLM이 실제로 돈 버전을 남긴다. 이전 추출이 끝나지 않았거나 실패했으면 복사하지 않고 `pending`을 넣는다. PDF가 바뀌면 항상 다시 추출한다.
- `notice_change`의 `pdf_notice_box`(PDF 정정 안내 상자)는 추출 단계 몫이라 여기서 채우지 않는다.
