# DB 스키마 (SCHEMA)

- 작성일: 2026-10-21
- 상태: 초안 (미정 사항 6건 결정, A·B 리뷰 전)
- 상위 문서: [서비스 기획서](../SERVICE_SPEC.md)
- 입력 문서: [데이터 1~2장](DATA.md), [알림 3장](ALERTS.md#3-구독-데이터), [기술 노트 1장](../TECH_NOTES.md#1-데이터-출처-조사-결과), 샘플 `docs/api-samples/`

> A(Python)와 B(Spring)가 DB로만 주고받기 위한 계약서다. PostgreSQL 기준이고, 아래 DDL은 마이그레이션 첫 버전의 출발점이다.

## 0. 설계 원칙

1. **쓰는 쪽은 테이블마다 하나다.** A는 공고·추출·발송 대기를 쓰고, B는 구독·발송 이력을 쓴다. 상대 쪽 테이블은 읽기만 한다.
2. **API 값과 가공 값을 모두 둔다.** 응답 원문은 `raw` jsonb로 남기고(해시 비교, 재가공), 화면·필터에 쓰는 값은 숫자·날짜 컬럼으로 따로 둔다.
3. **접수 상태는 저장하지 않는다.** P-01: 조회 시점에 계산한다. 접수 시작·종료일만 저장한다.
4. **"API에 없음"과 "0"을 구분한다.** 무순위 공고의 특별공급 유형별 세대수는 `NULL`, APT는 `0` 이상이다(P-04). 필터가 `NULL` 공고를 제외·안내하는 기준이다.
5. **정정은 덮어쓰지 않고 버전을 쌓는다.** 공고 행은 최신 값, 이력은 `notice_version`·`notice_change`에 둔다(P-08).
6. **개인정보는 `subscription.email` 하나뿐이다.** 해지·미확인 시 바로 지운다(P-09).

## 1. 테이블 한눈에

| 영역 | 테이블 | 쓰는 쪽 | 근거 |
|---|---|---|---|
| 수집 | `crawl_run` | A | P-10, P-15 |
| 공고 | `notice`, `notice_schedule`, `notice_unit` | A | DATA 1장, P-01, P-02 |
| 버전 | `notice_version`, `notice_change` | A | P-08, P-11 |
| 추출 | `extraction_run`, `extracted_item`, `item_evidence` | A | DATA 2장, P-05, P-06, P-13 |
| 알림 | `subscription` | B | ALERTS 3장 |
| 알림 | `alert_outbox`, `alert_outbox_notice` | A 적재, B 상태 갱신 | P-17~P-19 |
| 알림 | `alert_log` | B | P-19 중복 방지 |

```
notice 1─N notice_schedule
       1─N notice_unit
       1─N notice_version 1─N notice_change
                          1─1 extraction_run 1─N extracted_item 1─N item_evidence
subscription 1─N alert_outbox 1─N alert_outbox_notice ──▶ (notice, version)
subscription 1─N alert_log
```

## 2. 수집

```sql
CREATE TABLE crawl_run (
    id            bigserial PRIMARY KEY,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz,
    status        text NOT NULL CHECK (status IN ('running','success','failed')),
    notice_seen   int NOT NULL DEFAULT 0,
    notice_new    int NOT NULL DEFAULT 0,
    notice_changed int NOT NULL DEFAULT 0,
    error         text
);
-- P-15: 마지막 success의 finished_at이 24시간을 넘으면 S-01 경고. P-17: 알림은 success 이후에만.
CREATE INDEX crawl_run_success_idx ON crawl_run (finished_at DESC) WHERE status = 'success';
```

## 3. 공고

```sql
CREATE TABLE notice (
    house_manage_no   text PRIMARY KEY,               -- HOUSE_MANAGE_NO (= PBLANC_NO). APT·무순위 번호 대역이 겹치지 않아 한 테이블
    notice_type       text NOT NULL CHECK (notice_type IN ('general','no_rank','resupply')),  -- P-02
    source_api        text NOT NULL CHECK (source_api IN ('apt','remndr')),
    name              text NOT NULL,                  -- HOUSE_NM
    region            text NOT NULL,                  -- SUBSCRPT_AREA_CODE_NM (예: 부산)
    region_code       text,                           -- SUBSCRPT_AREA_CODE
    address           text,                           -- HSSPLY_ADRES
    house_kind        text CHECK (house_kind IN ('private','public')),  -- HOUSE_DTL_SECD_NM 민영/국민. 무순위는 NULL
    developer         text,                           -- BSNS_MBY_NM
    announced_on      date NOT NULL,                  -- RCRIT_PBLANC_DE
    rcept_start       date,                           -- P-01로 계산해 저장. 둘 다 NULL이면 '일정 미정'(E-05)
    rcept_end         date,
    winner_on         date,                           -- PRZWNER_PRESNATN_DE
    contract_start    date,                           -- CNTRCT_CNCLS_BGNDE
    contract_end      date,                           -- CNTRCT_CNCLS_ENDDE
    move_in_ym        text CHECK (move_in_ym ~ '^\d{6}$'),  -- MVN_PREARNGE_YM
    total_households  int,                            -- TOT_SUPLY_HSHLDCO
    notice_url        text NOT NULL,                  -- PBLANC_URL (청약홈 상세, PDF 아님)
    homepage_url      text,                           -- HMPG_ADRES
    phone             text,                           -- MDHS_TELNO
    is_regulated      boolean,                        -- SPECLT_RDN_EARTH_AT 등. 의미 추정이라 D-03 동안 화면에 쓰지 않음. 무순위 NULL
    raw               jsonb NOT NULL,                 -- API 응답 원문(공고)
    raw_hash          text NOT NULL,                  -- raw + 주택형 raw의 해시 (정정 감지)
    -- 버전·상태
    current_version   int NOT NULL DEFAULT 1,
    first_collected_at timestamptz NOT NULL,          -- P-18 새 공고 판단
    last_checked_at   timestamptz NOT NULL,           -- S-03 '마지막 확인'
    last_changed_at   timestamptz,                    -- 정정 감지 시각. 정정 배지 7일 판단
    removed_at        timestamptz,                    -- P-16 '청약홈에서 내려간 공고'(E-08)
    summary_status    text NOT NULL DEFAULT 'pending'
                      CHECK (summary_status IN ('pending','done','partial','failed')),  -- P-06
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notice_rcept_idx    ON notice (rcept_end, rcept_start);
CREATE INDEX notice_region_idx   ON notice (region);
CREATE INDEX notice_first_idx    ON notice (first_collected_at);       -- 새 공고 알림
CREATE INDEX notice_changed_idx  ON notice (last_changed_at) WHERE last_changed_at IS NOT NULL;
CREATE INDEX notice_name_trgm    ON notice USING gin (name gin_trgm_ops);  -- 검색(F3-8). CREATE EXTENSION pg_trgm 필요

-- 단계별 접수·일정. 캘린더 이벤트(DATA 1장)와 S-03 타임라인의 원천
CREATE TABLE notice_schedule (
    house_manage_no text NOT NULL REFERENCES notice ON DELETE CASCADE,
    stage           text NOT NULL CHECK (stage IN ('special','rank1','rank2','no_rank','general','winner','contract')),
    area            text NOT NULL DEFAULT 'all' CHECK (area IN ('all','local','gyeonggi','other')),  -- 해당지역·기타경기·기타지역
    start_on        date NOT NULL,
    end_on          date NOT NULL,
    PRIMARY KEY (house_manage_no, stage, area)
);
-- 매핑: SPSPLY_RCEPT_*→special/all, GNRL_RNK1_CRSPAREA→rank1/local, _ETC_GG→gyeonggi, _ETC_AREA→other,
--       GNRL_RNK2_* 동일, SUBSCRPT_RCEPT_*→no_rank/all, GNRL_RCEPT_*→general/all,
--       PRZWNER_PRESNATN_DE→winner(시작=종료), CNTRCT_CNCLS_*→contract

CREATE TABLE notice_unit (                                -- 주택형
    house_manage_no text NOT NULL REFERENCES notice ON DELETE CASCADE,
    model_no        text NOT NULL,                        -- MODEL_NO
    house_ty        text NOT NULL,                        -- HOUSE_TY 앞뒤 공백 제거 (예: 084.8860A)
    exclusive_area  numeric(8,4) NOT NULL,                -- 전용면적(숫자). house_ty 앞 숫자
    supply_area     numeric(8,4),                         -- SUPLY_AR. 무순위는 NULL
    top_price_man   int,                                  -- LTTOT_TOP_AMOUNT 만원. 0·빈 값은 NULL(E-06)
    general_count   int,                                  -- SUPLY_HSHLDCO
    special_count   int,                                  -- SPSPLY_HSHLDCO
    -- 특별공급 유형별 세대수. APT는 0 이상, 무순위·재공급은 NULL(P-04)
    sp_newlywed     int,                                  -- NWWDS_HSHLDCO
    sp_first_home   int,                                  -- LFE_FRST_HSHLDCO
    sp_multi_child  int,                                  -- MNYCH_HSHLDCO
    sp_newborn      int,                                  -- NWBB_HSHLDCO
    sp_elderly      int,                                  -- OLD_PARNTS_SUPORT_HSHLDCO
    sp_youth        int,                                  -- YGMN_HSHLDCO
    sp_institution  int,                                  -- INSTT_RECOMEND_HSHLDCO
    sp_etc          int,                                  -- ETC_HSHLDCO + TRANSR_INSTT_ENFSN_HSHLDCO
    raw             jsonb NOT NULL,
    PRIMARY KEY (house_manage_no, model_no)
);
CREATE INDEX notice_unit_area_idx  ON notice_unit (exclusive_area);
CREATE INDEX notice_unit_price_idx ON notice_unit (top_price_man);
```

**메모**

- `rcept_start`·`rcept_end`는 P-01 규칙을 A가 수집 때 계산해 넣는다. 규칙이 바뀌면 `notice_schedule`에서 다시 계산할 수 있다.
- 분양가 범위, 전용면적 범위, 특별공급 유형 목록(DATA 1장)은 `notice_unit` 집계로 만든다. 컬럼으로 두지 않고, 목록 성능이 문제가 되면 뷰나 머티리얼라이즈드 뷰로 올린다.
- 세대수 검증(E-11)은 `notice_unit` 합과 `total_households`를 비교해 운영 로그에만 남긴다. 제약으로 막지 않는다.
- 접수 상태 필터는 `rcept_start`·`rcept_end`와 오늘(KST) 비교로 쿼리한다.

## 4. 버전과 정정

```sql
CREATE TABLE notice_version (
    house_manage_no text NOT NULL REFERENCES notice ON DELETE CASCADE,
    version         int  NOT NULL,                        -- 1부터
    detected_at     timestamptz NOT NULL,
    api_hash        text NOT NULL,                        -- notice.raw_hash 스냅샷
    pdf_hash        text,                                 -- PDF 파일 해시(P-11). PDF 없으면 NULL
    pdf_file_name   text,                                 -- Content-Disposition 파일명. '(2026.10.19.정정)' 보조 신호
    pdf_path        text,                                 -- 저장 위치(VM 디스크 경로 또는 오브젝트 키)
    pdf_status      text NOT NULL DEFAULT 'pending'
                    CHECK (pdf_status IN ('pending','ok','none','not_pdf','failed')),  -- none=링크 없음(E-01), not_pdf=E-03
    change_source   text CHECK (change_source IN ('api','pdf','both')),  -- 정정이 어디서 감지됐는가. version=1은 NULL
    PRIMARY KEY (house_manage_no, version)
);

CREATE TABLE notice_change (                              -- 블록 D / M-03의 '이전 값 → 새 값'
    id              bigserial PRIMARY KEY,
    house_manage_no text NOT NULL,
    version         int  NOT NULL,                        -- 바뀐 뒤의 버전
    field           text NOT NULL,                        -- 항목 이름(화면 표시용 또는 코드)
    before_value    text,
    after_value     text,
    source          text NOT NULL CHECK (source IN ('api_diff','pdf_notice_box')),  -- 정정 안내 상자에서 뽑았는가
    FOREIGN KEY (house_manage_no, version) REFERENCES notice_version
);
CREATE INDEX notice_change_idx ON notice_change (house_manage_no, version);
```

- 바뀐 항목을 못 뽑으면 `notice_change` 행 없이 `notice_version`만 만든다(M-03: `공고문이 새로 올라왔어요.`).
- `notice.current_version` = 가장 큰 `notice_version.version`. 정정 감지 시 A가 한 트랜잭션에서 `notice` 갱신 + `notice_version` 추가 + `extraction_run` 생성을 한다.

## 5. PDF 추출

```sql
CREATE TABLE extraction_run (                             -- 공고 버전당 한 번(재시도는 attempt 증가)
    house_manage_no text NOT NULL,
    version         int  NOT NULL,
    status          text NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','running','done','partial','failed')),
    attempt         int  NOT NULL DEFAULT 0,              -- P-13: 최대 3회(최초 + 1h + 6h)
    next_attempt_at timestamptz,
    model           text,                                 -- 예: claude-opus-5-5
    prompt_version  text,                                 -- 지시문 버전(D-15 수정 추적)
    page_count      int,
    input_tokens    int,
    output_tokens   int,
    cost_usd        numeric(8,4),
    needs_review_ratio numeric(4,3),                      -- '확인 필요' 비율. 0.30 초과 시 failed(P-05)
    error           text,
    started_at      timestamptz,
    finished_at     timestamptz,
    PRIMARY KEY (house_manage_no, version),
    FOREIGN KEY (house_manage_no, version) REFERENCES notice_version
);
CREATE INDEX extraction_run_queue_idx ON extraction_run (next_attempt_at) WHERE status IN ('pending','running');

CREATE TABLE extracted_item (
    id              bigserial PRIMARY KEY,
    house_manage_no text NOT NULL,
    version         int  NOT NULL,
    category        smallint NOT NULL CHECK (category BETWEEN 1 AND 6),   -- DATA 2장 분류
    constraint_code text CHECK (constraint_code IN
        ('rewin_limit','apply_restriction','resale_limit','ineligible_limit','duplicate_apply','residence_duty','payment')),
        -- 분류 6일 때만. SCREENS.md 블록 B의 7개 코드와 같다
    targets         text[] NOT NULL DEFAULT '{전체}',     -- '전체' 또는 공급 유형 코드(general_1st … resupply)
    requirement     text NOT NULL,                        -- 요건 문장
    criteria_table  jsonb,                                -- 소득·자산 기준 표(선택)
    is_general_rule boolean NOT NULL DEFAULT false,       -- 일반 규정이면 블록 C 맨 아래에 접음
    status          text NOT NULL CHECK (status IN ('confirmed','needs_review')),  -- P-05
    sort_order      int NOT NULL DEFAULT 0,
    FOREIGN KEY (house_manage_no, version) REFERENCES extraction_run ON DELETE CASCADE,
    CHECK ((category = 6) = (constraint_code IS NOT NULL))
);
CREATE INDEX extracted_item_idx ON extracted_item (house_manage_no, version, category);

CREATE TABLE item_evidence (                              -- 항목당 1개 이상
    id         bigserial PRIMARY KEY,
    item_id    bigint NOT NULL REFERENCES extracted_item ON DELETE CASCADE,
    page       int  NOT NULL,                             -- 쪽 번호(정정 안내 상자로 밀린 뒤의 번호)
    quote      text NOT NULL,                             -- 인용 문구 (화면은 300자까지)
    verified   boolean NOT NULL                           -- 정규화 후 해당 쪽 원문에 문자열로 있는가
);
CREATE INDEX item_evidence_idx ON item_evidence (item_id);
```

- 화면은 항상 `notice.current_version`의 추출 결과를 읽는다. 정정 직후 재추출이 끝나기 전에는 새 버전의 `extraction_run`이 `pending`이므로, 직전 버전의 `done` 결과를 보여 주고 P-08 안내문을 띄운다(B가 `current_version - 1` 이하에서 가장 최근 `done`을 찾는다).
- 정정되면 이전 버전의 근거를 재사용하지 않고 새 버전으로 처음부터 추출한다(F1-6). 그래서 항목을 버전별로 따로 둔다.
- `notice.summary_status`는 최신 `extraction_run`에서 파생한 캐시다. A가 추출 종료 때 함께 갱신한다. 상태 값 대응: `pdf_status` ∈ (`none`,`not_pdf`,`failed`) 또는 `extraction_run.status='failed'` → `failed`.

## 6. 알림

```sql
CREATE TABLE subscription (
    id              bigserial PRIMARY KEY,
    email           text,                                 -- 해지·미확인·반송 처리 후 NULL. 소문자 정규화
    conditions      jsonb NOT NULL,                       -- S-01 쿼리와 같은 형식. 접수 상태·검색어 제외
    conditions_hash text NOT NULL,                        -- 정렬한 conditions의 해시. 같은 조건 중복(E-17) 판단
    status          text NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','active','unsubscribed','suspended')),  -- 확인 대기/구독 중/해지/발송 중지(E-15)
    confirm_token   text NOT NULL UNIQUE,                 -- 7일 유효
    manage_token    text NOT NULL UNIQUE,                 -- 관리·해지 링크
    consent_at      timestamptz NOT NULL,                 -- 개인정보 수집·이용 동의 시각
    requested_at    timestamptz NOT NULL DEFAULT now(),
    confirmed_at    timestamptz,
    unsubscribed_at timestamptz,
    last_sent_at    timestamptz,
    CHECK (status = 'unsubscribed' OR email IS NOT NULL OR status = 'suspended')
);
CREATE UNIQUE INDEX subscription_dedup_idx ON subscription (email, conditions_hash) WHERE status IN ('pending','active');
CREATE INDEX subscription_email_idx  ON subscription (email) WHERE email IS NOT NULL;      -- 이메일당 3개 제한(E-18)
CREATE INDEX subscription_active_idx ON subscription (status) WHERE status = 'active';
CREATE INDEX subscription_pending_idx ON subscription (requested_at) WHERE status = 'pending';  -- 7일 후 삭제(E-14)

CREATE TABLE alert_outbox (                               -- 발송 대기. A가 적재, B가 읽고 상태를 갱신
    id              bigserial PRIMARY KEY,
    subscription_id bigint NOT NULL REFERENCES subscription ON DELETE CASCADE,
    send_date       date NOT NULL,                        -- 발송 예정일(KST)
    mail_type       text NOT NULL CHECK (mail_type IN ('new','correction','combined')),  -- M-02 / M-03 / 합본(P-19)
    status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','sent','failed')),
    attempts        int  NOT NULL DEFAULT 0,              -- P-20: 일시 실패 1회 재시도
    next_attempt_at timestamptz,
    error           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    UNIQUE (subscription_id, send_date)                   -- P-19: 구독당 하루 한 통
);
CREATE INDEX alert_outbox_queue_idx ON alert_outbox (send_date) WHERE status = 'pending';

CREATE TABLE alert_outbox_notice (                        -- 한 통에 담을 공고
    outbox_id       bigint NOT NULL REFERENCES alert_outbox ON DELETE CASCADE,
    house_manage_no text NOT NULL REFERENCES notice,
    version         int  NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('new','correction')),
    PRIMARY KEY (outbox_id, house_manage_no, version, kind)
);

CREATE TABLE alert_log (                                  -- 발송 이력. 이메일 없음
    id              bigserial PRIMARY KEY,
    subscription_id bigint REFERENCES subscription ON DELETE SET NULL,   -- 구독이 지워져도 집계용으로 남김(P-09)
    house_manage_no text NOT NULL,
    version         int  NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('new','correction')),
    sent_at         timestamptz NOT NULL DEFAULT now()
);
-- 같은 공고·버전·종류는 구독당 한 번만 (P-19)
CREATE UNIQUE INDEX alert_log_dedup_idx ON alert_log (subscription_id, house_manage_no, version, kind)
    WHERE subscription_id IS NOT NULL;
```

- **적재(A, P-17 07:30 직전):** `status='active'` 구독마다 `conditions`를 만족하는 공고를 찾는다. 새 공고는 `first_collected_at > 직전 발송` + 접수 예정·접수 중(P-18), 정정은 `last_changed_at > 직전 발송` + `contract_end`가 지나지 않음. `alert_log`에 있는 (공고, 버전, 종류)는 뺀다. 남은 게 있으면 `alert_outbox` 한 행과 `alert_outbox_notice` 여러 행을 한 트랜잭션으로 넣는다.
- **발송(B):** `pending`을 읽어 렌더링·발송하고, 성공하면 `alert_log` 행을 넣고 `status='sent'`로 바꾼다. 둘을 한 트랜잭션으로 묶는다.
- **필터 조건 매칭:** 키와 의미는 8장 표를 따른다. A가 Python으로 구현하고, B의 목록 쿼리와 결과가 같은지 검수 시나리오 #2~#5와 #19로 확인한다.
- **개인정보 파기(P-09):**
  - 해지: `unsubscribed_at` 기록 후, 그 이메일의 모든 구독이 `pending`·`active`가 아니면 `email = NULL`로 지운다. 해지 메일(M-04)은 지우기 전에 보낸다.
  - 미확인 7일: `pending`이고 `requested_at`이 7일 지난 행은 삭제(E-14).
  - 이 파기 배치는 B가 하루 한 번 돌린다.
  - 반송·스팸 신고(E-15): 그 이메일의 구독을 `suspended`로 바꾸고 이메일은 차단용으로 유지한다(재구독 시 확인 메일부터).

## 7. 읽기 규칙 요약

| 화면 | 읽는 곳 |
|---|---|
| S-01 목록·S-02 필터 | `notice` + `notice_unit` 집계 + `crawl_run`(경고) |
| S-03 블록 A | `notice`, `notice_schedule`, `notice_unit` |
| S-03 블록 B·C | 최신 `done`·`partial` 버전의 `extracted_item` + `item_evidence` |
| S-03 블록 D·M-03 | `notice_version`, `notice_change` |
| M-02 | `alert_outbox` → `alert_outbox_notice` → `notice` |

## 8. 결정 사항 (2026-10-21)

| # | 질문 | 결정 |
|---|---|---|
| 1 | `constraint_code` 코드명 | SCREENS.md 블록 B와 같은 7개 코드(`rewin_limit`, `apply_restriction`, `ineligible_limit`, `resale_limit`, `residence_duty`, `payment`, `duplicate_apply`)로 확정. `apply_restriction`(1순위·가점제 제한)은 2주차 추출 검증(2026-10-22)에서 추가: 당첨 시 5년간 1순위 접수 제한, 2년간 가점제 제한이 재당첨 제한과 섞여 나왔다 |
| 2 | PDF 보관 | VM 디스크 `data/pdfs/<번호>/<버전>.pdf`. 루트(`data/pdfs`)는 설정값 `PDF_STORAGE_DIR`로 두고, DB의 `pdf_path`에는 루트를 뺀 상대 경로(`2026000437/1.pdf`)만 저장한다. 1주차 실험 파일도 같은 구조로 옮겼다(2026-10-21). 컷 순서 1번(원문 뷰어 축소)이 적용돼도 해시 비교와 재추출을 위해 보관은 유지 |
| 3 | 구독 `conditions` 키 | 아래 표로 확정. `"v": 1` 버전 필드를 넣고, 매칭 코드는 `v`별로 해석 |
| 4 | 마지막 구독 해지로 이메일이 지워진 뒤 `[다시 구독하기]` | 조건이 채워진 구독 시트(S-08a)로 이동해 이메일을 다시 입력받는다. 확인 메일부터 다시 시작 |
| 5 | 스키마 소유 | B가 Flyway로 소유(`V1__init.sql` …). A는 변경이 필요하면 B에게 PR로 요청 |
| 6 | 무순위 공고의 `house_kind`·`is_regulated`가 `NULL`일 때 필터 | P-04 그대로. 해당 필터를 쓰면 제외하고 안내 배너 |
| 7 | 추출 결과 `targets`(대상) | 자유 문장이 아니라 공급 유형 코드 목록. 추출 지시문이 [DATA.md 2장](DATA.md#2-추출-결과)의 코드 중에서 고르게 하고, 파서가 코드에 없는 값을 `unknown_targets`로 표시한다. 코드에 없는 유형은 `기타`로 받아 검토한다 |

**`conditions` 키 (v1)**

| 필터 | 키 | 값 | 비고 |
|---|---|---|---|
| 지역 | `region` | `["서울","경기"]` | `notice.region`과 같은 시·도 이름 |
| 특별공급 유형 | `supply` | `["newlywed","first_home","multi_child","newborn","elderly_parent","youth","institution"]` 중 | `notice_unit.sp_*` 컬럼에 대응. 값이 있으면 무순위·재공급(`NULL`) 제외 |
| 분양가 | `price_min`, `price_max` | 만원 정수 | `notice_unit.top_price_man` 범위. `15억 이상`은 `price_max` 없음 |
| 전용면적 | `area` | `["le60","60to85","gt85"]` | 경계: ≤60, 60<x≤85, >85 (`exclusive_area`) |
| 공고 유형 | `notice_type` | `["general","no_rank","resupply"]` | `notice.notice_type` |
| 주택 구분 | `house_kind` | `["private","public"]` | 값이 있으면 `NULL`(무순위) 제외 |

- 접수 상태(`status`), 검색어(`q`), 정렬(`sort`)은 넣지 않는다.
- 필터 사이는 AND, 한 필터 안의 값은 OR. `supply`·`price_*`·`area`는 **한 주택형이 모두 만족**해야 한다(S-02 조건 결합): `EXISTS (SELECT 1 FROM notice_unit u WHERE u.house_manage_no = n.house_manage_no AND ...)`.
- `conditions_hash`는 키를 정렬하고 값 배열을 정렬한 JSON의 해시다. 같은 조건이 순서만 달라 중복 저장되는 것을 막는다(E-17).
