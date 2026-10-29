# 수집 배치 (Python)

청약홈 분양정보 API에서 공고·주택형을 수집해 DB에 넣고, 공고문 PDF를 받아 정정을 감지한다. 담당 A. 스키마는 `backend`(Flyway)가 소유하며 이 배치는 쓰기만 한다.

## 흐름

1. `crawl_run`을 `running`으로 연다.
2. APT와 무순위·잔여세대 API에서 대상 공고를 받는다(P-11: 모집공고일 60일 이내 ∪ 계약 종료일이 지나지 않은 공고). 공고마다 주택형을 받는다.
3. 공고 raw + 주택형 raw의 해시(`raw_hash`)로 신규·정정을 판단한다.
4. 상세 페이지에서 "모집공고문 보기" 링크를 찾아 PDF를 받는다(요청 사이 1초, P-12). `%PDF`가 아니면 `not_pdf`, 링크가 없으면 `none`.
5. 신규이거나 API·PDF 해시가 바뀌면 `notice_version`을 추가한다. API가 바뀌면 `notice_change`(`api_diff`)에 이전 값 → 새 값을 남긴다. PDF가 있으면 `extraction_run`을 `pending`으로 넣는다.
6. 범위 안인데 API에서 사라진 공고는 `removed_at`만 기록한다(P-16). `DEV%` 샘플은 건드리지 않는다.
7. `crawl_run`을 닫는다. 목록 조회 자체가 실패하면 `failed`, 공고 몇 건만 실패하면 `success`로 닫고 `error`에 건수와 사유를 남긴다.

PDF는 `$PDF_DIR/<공고번호>/v<버전>.pdf`에 저장하고 `notice_version.pdf_path`에는 `PDF_DIR` 기준 상대 경로를 넣는다. API만 바뀌고 PDF가 같으면 새로 쓰지 않고 이전 경로를 가리킨다.

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
- PDF가 같은 API 정정에도 새 버전의 `extraction_run`이 `pending`으로 들어간다. 추출 워커가 이전 버전 결과를 재사용할지 정한다.
- `notice_change`의 `pdf_notice_box`(PDF 정정 안내 상자)는 추출 단계 몫이라 여기서 채우지 않는다.
