-- 같은 PDF로 API만 정정된 새 버전은 LLM을 다시 돌리지 않고 이전 추출 결과를 복사한다.
-- 복사된 실행이면 실제로 LLM이 돌았던 버전 번호를 가리킨다. 직접 추출했으면 NULL.
ALTER TABLE extraction_run ADD COLUMN reused_from_version int;
COMMENT ON COLUMN extraction_run.reused_from_version IS '복사 원본 버전(LLM이 실제로 돈 버전). 복사본의 토큰·비용은 0';
