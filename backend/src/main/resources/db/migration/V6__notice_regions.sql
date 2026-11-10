-- 여러 시도에 걸친 공고(LH 전세임대 같은 마이홈 전국 공고)가 첫 행의 시도 하나에만 잡히던 문제.
-- regions에 걸친 시도를 모두 두고 지역 필터는 regions로도 맞춘다. region은 표시용('전국' 또는 'A 외 N').
ALTER TABLE notice ADD COLUMN regions text[];

-- 마이홈 공고는 저장된 raw 행(houseSn 순)에서 다시 만든다. 이름 변환은 batch myhome.REGIONS·region()과 같다.
-- raw_hash는 그대로 두어 다음 수집에서 정정으로 잡히지 않게 한다.
UPDATE notice n SET regions = s.regions
FROM (
    SELECT house_manage_no, array_agg(short ORDER BY first_sn) AS regions
    FROM (
        SELECT n.house_manage_no, m.short, min((e ->> 'houseSn')::int) AS first_sn
        FROM notice n
        CROSS JOIN jsonb_array_elements(n.raw -> 'rows') e
        CROSS JOIN LATERAL (SELECT CASE e ->> 'brtcNm'
            WHEN '서울특별시' THEN '서울' WHEN '부산광역시' THEN '부산' WHEN '대구광역시' THEN '대구'
            WHEN '인천광역시' THEN '인천' WHEN '광주광역시' THEN '광주' WHEN '대전광역시' THEN '대전'
            WHEN '울산광역시' THEN '울산' WHEN '세종특별자치시' THEN '세종' WHEN '경기도' THEN '경기'
            WHEN '강원특별자치도' THEN '강원' WHEN '강원도' THEN '강원' WHEN '충청북도' THEN '충북'
            WHEN '충청남도' THEN '충남' WHEN '전북특별자치도' THEN '전북' WHEN '전라북도' THEN '전북'
            WHEN '전라남도' THEN '전남' WHEN '경상북도' THEN '경북' WHEN '경상남도' THEN '경남'
            WHEN '제주특별자치도' THEN '제주'
            WHEN '전남광주통합특별시' THEN CASE WHEN e ->> 'signguNm' LIKE '%구' THEN '광주' ELSE '전남' END
        END AS short) m
        WHERE n.source_api IN ('myhome_sale', 'myhome_rent') AND m.short IS NOT NULL
        GROUP BY n.house_manage_no, m.short
    ) x
    GROUP BY house_manage_no
) s
WHERE n.house_manage_no = s.house_manage_no;

UPDATE notice SET region = CASE WHEN cardinality(regions) >= 16 THEN '전국'
                               ELSE regions[1] || ' 외 ' || (cardinality(regions) - 1) END
WHERE cardinality(regions) > 1;

UPDATE notice SET regions = ARRAY[region] WHERE regions IS NULL;

CREATE INDEX notice_regions_idx ON notice USING gin (regions);
