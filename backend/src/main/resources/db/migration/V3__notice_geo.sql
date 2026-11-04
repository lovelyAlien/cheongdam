-- 지도(F9-1). 좌표는 수집·정정 때 배치가 채우고 화면을 열 때는 변환하지 않는다.
ALTER TABLE notice
    ADD COLUMN lat          double precision,
    ADD COLUMN lng          double precision,
    ADD COLUMN geo_accuracy text CHECK (geo_accuracy IN ('exact','district','none')),
    ADD COLUMN geo_address  text;
COMMENT ON COLUMN notice.geo_accuracy IS 'exact: 주소 변환 성공 / district: 시군구 중심 / none: 위치 모름 / NULL: 아직 변환 전 (P-21)';
COMMENT ON COLUMN notice.geo_address  IS '좌표를 만든 때의 주소. address와 다르면 정정으로 보고 다시 변환한다';
