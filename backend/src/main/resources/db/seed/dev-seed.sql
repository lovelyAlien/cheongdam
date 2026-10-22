-- 개발용 샘플 공고. 번호가 DEV로 시작하는 행만 지우고 다시 넣는다.
-- notice_change·extraction_run은 notice_version에 ON DELETE CASCADE가 없어 먼저 지운다
DELETE FROM notice_change WHERE house_manage_no LIKE 'DEV%';
DELETE FROM extraction_run WHERE house_manage_no LIKE 'DEV%';
DELETE FROM notice WHERE house_manage_no LIKE 'DEV%';
DELETE FROM crawl_run WHERE error = 'dev-seed';

INSERT INTO crawl_run (started_at, finished_at, status, notice_seen, notice_new, error)
VALUES (now() - interval '1 hour', now() - interval '50 minutes', 'success', 10, 2, 'dev-seed');

-- 헬퍼: notice를 간단히 넣는다 (필수 컬럼은 같은 값으로 채움)
CREATE TEMP TABLE seed_notice (no text, type text, kind text, nm text, region text, addr text, announced int,
                               rstart int, rend int, winner int, total int, corrected boolean);
INSERT INTO seed_notice VALUES
 ('DEV001','general','private','○○자이 더 센트럴','경기','경기도 수원시 장안구 ○○동 123',-3,-1, 2, 9,812,true),
 ('DEV002','general','public' ,'△△ 행복주택 1단지','서울','서울특별시 강동구 ○○동 45',-5, 3, 5,12,320,false),
 ('DEV003','general','private','□□ 푸르지오 파크','부산','부산광역시 해운대구 ○○동 7',-2, 6, 9,16,540,false),
 ('DEV004','no_rank',null,     '◇◇ 더샵 (무순위)','인천','인천광역시 연수구 ○○동 9',-1, 0, 1, 5, 30,false),
 ('DEV005','resupply',null    ,'☆☆ 파크뷰 불법행위 재공급','경기','경기도 성남시 분당구 ○○동 3',-4, 1, 3, 7, 12,false),
 ('DEV006','general','private','▽▽ 힐스테이트 에코','대구','대구광역시 수성구 ○○동 11',-30,-25,-20,-14,900,false),
 ('DEV007','general','private','◎◎ 자이 리버뷰','세종','세종특별자치시 한누리대로 1',-6, 10, 12,20,260,false),
 ('DEV008','general','public' ,'미정 일정 테스트 단지','충남','충청남도 천안시 서북구 ○○동 2',-1,null,null,null,100,false);

INSERT INTO notice (house_manage_no, notice_type, source_api, name, region, address, house_kind,
                    announced_on, rcept_start, rcept_end, winner_on, total_households, notice_url,
                    raw, raw_hash, first_collected_at, last_checked_at, last_changed_at)
SELECT no, type, CASE WHEN type = 'general' THEN 'apt' ELSE 'remndr' END, nm, region, addr, kind,
       (now() AT TIME ZONE 'Asia/Seoul')::date + announced,
       CASE WHEN rstart IS NULL THEN NULL ELSE (now() AT TIME ZONE 'Asia/Seoul')::date + rstart END,
       CASE WHEN rend   IS NULL THEN NULL ELSE (now() AT TIME ZONE 'Asia/Seoul')::date + rend   END,
       CASE WHEN winner IS NULL THEN NULL ELSE (now() AT TIME ZONE 'Asia/Seoul')::date + winner END,
       total, 'https://www.applyhome.co.kr/', '{}'::jsonb, 'dev',
       now() - interval '2 days', now() - interval '1 hour',
       CASE WHEN corrected THEN now() - interval '1 day' END
FROM seed_notice;

-- 주택형
INSERT INTO notice_unit (house_manage_no, model_no, house_ty, exclusive_area, supply_area, top_price_man,
                         general_count, special_count, sp_newlywed, sp_first_home, sp_multi_child, sp_newborn,
                         sp_elderly, sp_youth, sp_institution, sp_etc, raw) VALUES
 ('DEV001','01','059.9700A', 59.97, 80.1, 32100, 200, 120, 40, 30, 20, 10, 10, 0, 10, 0, '{}'),
 ('DEV001','02','084.8860A', 84.886,112.3, 52340, 210, 282, 100, 70, 40, 20, 20, 0, 32, 0, '{}'),
 ('DEV002','01','046.0000A', 46.00, 60.0, 21000, 100, 120, 50, 40, 0, 0, 0, 30, 0, 0, '{}'),
 ('DEV002','02','059.0000A', 59.00, 78.0, 28500,  60,  40, 20, 20, 0, 0, 0, 0, 0, 0, '{}'),
 ('DEV003','01','074.9900A', 74.99, 98.0, 61000, 250, 290, 90, 70, 50, 30, 20, 0, 30, 0, '{}'),
 ('DEV004','01','059.9900A', 59.99, NULL, 45000,  30, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '{}'),
 ('DEV005','01','084.0000A', 84.00, NULL, 38000,  12, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '{}'),
 ('DEV006','01','084.9000A', 84.90, 110.0, 99000, 600, 300, 100, 100, 50, 30, 20, 0, 0, 0, '{}'),
 ('DEV007','01','101.0000A',101.00, 130.0, 72000, 200,  60, 0, 0, 30, 0, 30, 0, 0, 0, '{}'),
 ('DEV008','01','059.0000A', 59.00, 80.0, NULL, 70, 30, 10, 10, 10, 0, 0, 0, 0, 0, '{}');

-- 일정(타임라인): DEV001은 특별공급 완료, 1순위 진행 중
CREATE TEMP TABLE seed_today AS SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS d;
INSERT INTO notice_schedule (house_manage_no, stage, area, start_on, end_on)
SELECT v.no, v.stage, v.area, t.d + v.s, t.d + v.e
FROM seed_today t, (VALUES
  ('DEV001','special','all',    -3, -3),
  ('DEV001','rank1','local',    -1, -1),
  ('DEV001','rank1','gyeonggi',  0,  0),
  ('DEV001','rank2','all',       1,  2),
  ('DEV001','winner','all',      9,  9),
  ('DEV001','contract','all',   17, 19),
  ('DEV002','special','all',     3,  3),
  ('DEV002','rank1','all',       4,  4),
  ('DEV002','rank2','all',       5,  5),
  ('DEV004','no_rank','all',     0,  1),
  ('DEV004','winner','all',      5,  5)
) AS v(no, stage, area, s, e);
DROP TABLE seed_today;

-- DEV001: 1회 정정, 추출 완료
UPDATE notice SET current_version = 2, summary_status = 'done' WHERE house_manage_no = 'DEV001';
INSERT INTO notice_version (house_manage_no, version, detected_at, api_hash, pdf_status, change_source) VALUES
  ('DEV001', 1, now() - interval '3 days', 'dev', 'ok', NULL),
  ('DEV001', 2, now() - interval '1 day',  'dev', 'ok', 'pdf');
INSERT INTO notice_change (house_manage_no, version, field, before_value, after_value, source) VALUES
  ('DEV001', 2, '1순위 접수일', '2026-10-21', '2026-10-23', 'pdf_notice_box'),
  ('DEV001', 2, '계약일', '2026-11-08', '2026-11-10', 'pdf_notice_box');
INSERT INTO extraction_run (house_manage_no, version, status, attempt) VALUES ('DEV001', 2, 'done', 1);
INSERT INTO extracted_item (house_manage_no, version, category, constraint_code, targets, requirement,
                            is_general_rule, status, sort_order) VALUES
  ('DEV001', 2, 6, 'rewin_limit',  '{전체}',  '당첨일부터 10년간 재당첨이 제한돼요', false, 'confirmed', 1),
  ('DEV001', 2, 6, 'resale_limit', '{전체}',  '전매제한 3년', false, 'confirmed', 2),
  ('DEV001', 2, 6, 'payment',      '{전체}',  '계약금 분양가의 10%, 중도금 60%, 잔금 30%(입주지정일)', false, 'confirmed', 3),
  ('DEV001', 2, 1, NULL, '{전체}',      '공고일 현재 무주택세대구성원이어야 해요', false, 'confirmed', 1),
  ('DEV001', 2, 4, NULL, '{newlywed}',  '혼인 기간이 7년 이내여야 해요', false, 'confirmed', 1),
  ('DEV001', 2, 4, NULL, '{newlywed}',  '소득이 도시근로자 월평균소득 140% 이하여야 해요', false, 'needs_review', 2),
  ('DEV001', 2, 1, NULL, '{전체}',      '세대란 세대주와 세대원 전원을 말해요', true, 'confirmed', 9);
INSERT INTO item_evidence (item_id, page, quote, verified)
SELECT id, 34, '당첨된 주택의 입주자로 선정된 날로부터 10년간 다른 분양주택의 입주자로 선정될 수 없습니다.', true
FROM extracted_item WHERE house_manage_no = 'DEV001' AND constraint_code = 'rewin_limit';
INSERT INTO item_evidence (item_id, page, quote, verified)
SELECT id, 35, '전매제한기간은 계약일로부터 3년입니다.', true
FROM extracted_item WHERE house_manage_no = 'DEV001' AND constraint_code = 'resale_limit';
INSERT INTO item_evidence (item_id, page, quote, verified)
SELECT id, 40, '계약금 10%, 중도금 60%, 잔금 30%', true
FROM extracted_item WHERE house_manage_no = 'DEV001' AND constraint_code = 'payment';
INSERT INTO item_evidence (item_id, page, quote, verified)
SELECT id, p, '공고문 해당 부분 인용', v
FROM extracted_item,
     (VALUES (14, true)) AS x(p, v)
WHERE house_manage_no = 'DEV001' AND category IN (1, 4) AND requirement NOT LIKE '소득%' AND is_general_rule = false;
INSERT INTO item_evidence (item_id, page, quote, verified)
SELECT id, 12, '월평균소득 140%', false FROM extracted_item
WHERE house_manage_no = 'DEV001' AND requirement LIKE '소득%';
INSERT INTO item_evidence (item_id, page, quote, verified)
SELECT id, 5, '세대란 세대주와 세대원 전원을 말한다', true FROM extracted_item
WHERE house_manage_no = 'DEV001' AND is_general_rule;

DROP TABLE seed_notice;
