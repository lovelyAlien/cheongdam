-- 마이홈포털 공공분양·공공임대 수집(D-30). 청약홈 공고와 구분하는 유형과 표시용 컬럼을 추가한다.
ALTER TABLE notice DROP CONSTRAINT notice_notice_type_check;
ALTER TABLE notice ADD CONSTRAINT notice_notice_type_check
    CHECK (notice_type IN ('general','no_rank','resupply','public_sale','public_rent'));
ALTER TABLE notice DROP CONSTRAINT notice_source_api_check;
ALTER TABLE notice ADD CONSTRAINT notice_source_api_check
    CHECK (source_api IN ('apt','remndr','myhome_sale','myhome_rent'));

ALTER TABLE notice ADD COLUMN supply_kind text;        -- 공급유형(행복주택, 국민임대 등). 공공분양·청약홈은 NULL
ALTER TABLE notice ADD COLUMN before_notice_no text;   -- 정정 공고가 가리키는 이전 공고 번호. 청약홈은 정정이 같은 번호라 NULL
ALTER TABLE notice ADD COLUMN successor_no text;       -- 이 공고를 대체한 정정 공고 번호. 값이 있으면 목록·알림에서 제외(E-23)
