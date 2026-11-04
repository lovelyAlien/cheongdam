-- 마이홈포털 공공분양·공공임대 수집(D-30). 공공분양은 general+public(국민주택)으로 두고, 공공임대만 새 유형을 쓴다.
ALTER TABLE notice DROP CONSTRAINT notice_notice_type_check;
ALTER TABLE notice ADD CONSTRAINT notice_notice_type_check
    CHECK (notice_type IN ('general','no_rank','resupply','public_rent'));
ALTER TABLE notice DROP CONSTRAINT notice_source_api_check;
ALTER TABLE notice ADD CONSTRAINT notice_source_api_check
    CHECK (source_api IN ('apt','remndr','myhome_sale','myhome_rent'));
