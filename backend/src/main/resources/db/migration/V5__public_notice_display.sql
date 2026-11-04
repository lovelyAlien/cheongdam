-- 공공 공고 구분 표시(D-30, SCREENS S-01·S-03). V4가 이미 배포되어 있어 V4를 고치지 않고 따로 변경한다.
-- 공공분양을 청약홈 일반 분양(general)과 구분하는 유형을 추가한다.
ALTER TABLE notice DROP CONSTRAINT notice_notice_type_check;
ALTER TABLE notice ADD CONSTRAINT notice_notice_type_check
    CHECK (notice_type IN ('general','no_rank','resupply','public_sale','public_rent'));

ALTER TABLE notice ADD COLUMN supply_kind text;        -- 공급유형(행복주택, 국민임대 등). 공공분양·청약홈은 NULL
ALTER TABLE notice ADD COLUMN before_notice_no text;   -- 정정 공고가 가리키는 이전 공고 번호. 청약홈은 정정이 같은 번호라 NULL
ALTER TABLE notice ADD COLUMN successor_no text;       -- 이 공고를 대체한 정정 공고 번호. 값이 있으면 목록·알림에서 제외(E-23)

-- V4 시점에 공공분양은 general+public으로 저장됐다. 새 유형으로 옮기고 주택 구분은 비운다.
UPDATE notice SET notice_type = 'public_sale', house_kind = NULL WHERE source_api = 'myhome_sale';
UPDATE notice SET house_kind = NULL WHERE source_api = 'myhome_rent';
