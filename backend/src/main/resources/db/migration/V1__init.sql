-- 청담 DB 스키마 V1. 설명과 결정 배경은 docs/spec/SCHEMA.md 참고.
-- 스키마 소유: B(Flyway). A(Python)의 변경 요청은 PR로.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

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
