# 서비스 기획서: API (API)

- 작성일: 2026-10-22
- 상태: 초안 (목록·상세·구독 엔드포인트. 구현하며 고친다)
- 상위 문서: [서비스 기획서](../SERVICE_SPEC.md) — 공통 정책(P-01~P-09), 결정 사항(D-)은 상위 문서에 있다.
- 관련 문서: [화면](SCREENS.md), [데이터](DATA.md), [알림](ALERTS.md), [DB 스키마](SCHEMA.md)

> 담당 B 혼자 쓰는 문서다. 프론트(React)와 백엔드(Spring) 사이의 JSON API를 정한다. 청약홈 API는 여기서 다루지 않는다(Python 배치가 수집해 DB에 넣는다). 구현이 끝나면 springdoc(OpenAPI)이 만든 명세를 기준으로 삼고, 이 문서는 정책과 결정 이유만 남긴다.

**목차**

1. [공통 규칙](#1-공통-규칙)
2. [공고 목록: GET /api/notices](#2-공고-목록)
3. [공고 상세: GET /api/notices/{no}](#3-공고-상세)
4. [캘린더·원문 PDF](#4-캘린더원문-pdf)
5. [알림 구독](#5-알림-구독)
6. [API 밖에서 Spring이 하는 일](#6-api-밖에서-spring이-하는-일)
7. [열린 질문](#7-열린-질문)

---

## 1. 공통 규칙

| 항목 | 규칙 | 이유 |
|---|---|---|
| 경로 | 모두 `/api` 아래. Nginx가 `/api/`만 Spring으로 넘긴다 | 정적 파일과 구분 |
| 형식 | 요청·응답 JSON(UTF-8). 날짜 `YYYY-MM-DD`, 시각 ISO 8601 + 오프셋(`2026-10-22T06:00:00+09:00`) | |
| 필드 이름 | **snake_case.** 쿼리 파라미터, 응답, 구독 `conditions`(SCHEMA 8장)가 같은 이름을 쓴다 | 필터 URL = 알림 조건 = API 쿼리가 한 형식이어야 변환 코드가 없다 |
| 서버가 계산하는 것 | 접수 상태·D-day(P-01), 정정 배지(P-08), 일정 단계의 `완료/오늘/예정`, 분양가·면적 범위, 공고 유형 | 한국 시간 기준 날짜 판단을 클라이언트 시계에 맡기지 않는다 |
| 클라이언트가 하는 것 | 표기(`5억 2,340만 원`, `10.21(수)`, `84.89㎡`)와 문구(P-03), 필터 일치 행 강조, 블록 C 유형별 걸러내기 | 표기 규칙은 화면 문제다. API는 숫자·날짜 원값을 준다 |
| 금액 | 만원 정수(`top_price_man: 52340`) | DB와 같은 단위 |
| `null` | 값이 없으면 필드를 생략하지 않고 `null`로 준다 | 클라이언트가 "없음"(P-04)과 "아직 모름"을 구분하려면 키가 항상 있어야 한다 |
| 캐시 | 목록·상세는 상태가 자정(KST)에 바뀌므로 자정을 넘겨 캐시하지 않는다(P-01). 구독 API는 `Cache-Control: no-store` | |
| 인증 | 없다(P-09). 구독 관리 API는 메일 속 토큰이 권한이다 | |

**오류 형식**

```json
{ "error": { "code": "invalid_param", "message": "price_min이 price_max보다 커요.", "field": "price_min" } }
```

| HTTP | `code` | 언제 |
|---|---|---|
| 400 | `invalid_param` | 쿼리·본문 형식 오류, 모르는 필터 값. `field`에 이름 |
| 404 | `not_found` | 없는 공고 번호, 없는 토큰·구독(E-16) |
| 409 | `already_subscribed` / `subscription_limit` | E-17 / E-18 |
| 410 | `token_expired` | 확인 토큰 7일 경과(E-14) |
| 429 | `rate_limited` | 구독·관리 메일 요청 과다 |
| 500 | `internal` | 그 외. 화면은 S-09 오류 |

- `message`는 사용자에게 그대로 보여 줘도 되는 해요체 문장이다. 클라이언트는 `code`로 분기하고 문구는 화면 쪽에서 정한다.
- 목록 API 오류는 S-01에서 `공고를 불러오지 못했어요.`(SCREENS 1장)로 보인다.

---

## 2. 공고 목록

`GET /api/notices` — S-01 목록과 S-02 필터. 화면 URL 쿼리와 **같은 이름·형식**이다(SCREENS 1장).

**쿼리**

| 이름 | 값 | 기본값 | 비고 |
|---|---|---|---|
| `q` | 단지명 검색어 | 없음 | `notice.name`에 부분 일치(pg_trgm). 공백·괄호 표기를 무시(F3-8) |
| `status` | `upcoming,open,closed` 중 쉼표로 | `upcoming,open` | 접수 상태(P-01). 계산 결과로 거른다. `closed`는 접수 종료일 1년 이내만(D-02). 일정 미정 공고는 `upcoming` 뒤에 붙는다(E-05) |
| `region` | `서울,경기` | 전체 | 시·도 이름. `notice.region` |
| `supply` | `newlywed,first_home,multi_child,newborn,elderly_parent,youth,institution` 중 | 없음 | 한 주택형 조건(아래) |
| `price_min`, `price_max` | 만원 정수 | 없음 | 한 주택형 조건. `15억 이상`은 `price_max` 없음 |
| `area` | `le60,60to85,gt85` 중 | 없음 | 한 주택형 조건 |
| `notice_type` | `general,no_rank,resupply` 중 | 전체 | |
| `house_kind` | `private,public` 중 | 전체 | 값이 있으면 무순위·재공급 제외(P-04) |
| `sort` | `deadline`, `latest` | `deadline` | 정렬 규칙은 SCREENS 1장(F3-10) |
| `limit` | 0~100 | 20 | `0`이면 `total`만 센다(S-02 `공고 N건 보기`) |
| `offset` | 0 이상 | 0 | `더 보기`는 `offset += 20`. 상세에서 돌아올 때는 `limit = 20 × 더 보기 횟수`로 한 번에 불러 위치를 복원한다 |

- 결합: 서로 다른 필터는 AND, 한 필터 안은 OR. `supply`, `price_*`, `area`는 **한 주택형이 모두 만족**해야 한다(SCHEMA 8장의 `EXISTS` 쿼리).
- 값 목록은 쉼표 구분 한 파라미터다(`region=서울,경기`). 같은 이름을 반복하는 방식은 쓰지 않는다. URL 공유 형식과 같게 하기 위해서다.
- 마감 공고는 `status`에 `closed`가 있을 때만 나오고, `removed_at`이 있는 공고(E-08)는 어느 경우에도 목록에서 뺀다.

**응답 200**

```json
{
  "total": 24,
  "offset": 0,
  "items": [
    {
      "house_manage_no": "2026000514",
      "name": "○○자이 더 센트럴",
      "notice_type": "general",
      "house_kind": "private",
      "region": "경기",
      "district": "수원시 장안구",
      "status": "open",
      "d_day": 2,
      "rcept_start": "2026-10-20",
      "rcept_end": "2026-10-23",
      "winner_on": "2026-10-30",
      "is_corrected": true,
      "price_min_man": 32100,
      "price_max_man": 52340,
      "area_min": 59.97,
      "area_max": 84.89,
      "total_households": 812,
      "supply_tags": [
        { "code": "newlywed", "households": 120 },
        { "code": "first_home", "households": 80 }
      ]
    }
  ],
  "no_info_excluded": 5,
  "relaxations": [],
  "last_crawl": { "finished_at": "2026-10-22T06:03:11+09:00", "stale": false }
}
```

| 필드 | 설명 |
|---|---|
| `status` | `upcoming` / `open` / `closed` / `unknown`(일정 확인 필요, E-05). P-01 |
| `d_day` | `upcoming`은 시작일까지, `open`은 종료일까지(당일 `0`). 그 외 `null` |
| `district` | `address`의 시·군·구까지. 못 잘라내면 `null`이고 화면은 `region`만 쓴다 |
| `is_corrected` | `last_changed_at`이 7일 이내(P-08) |
| `price_*_man`, `area_*` | 주택형 값의 min~max. 분양가는 `null` 주택형을 빼고(E-06), 전부 없으면 `null`(화면 `공고문 확인`). 주택형이 0건이면 모두 `null`(E-07) |
| `supply_tags` | 세대수 1 이상인 특별공급 유형을 세대수 내림차순으로 **전부**. 3개까지 보이고 `+N`을 붙이는 것, 필터에서 고른 유형의 강조는 클라이언트가 한다. 무순위·재공급은 빈 배열 |
| `no_info_excluded` | `supply` 또는 `house_kind`가 있어 빠진 무순위·재공급 공고 수(P-04). 해당 없으면 `0` |
| `relaxations` | `total`이 0일 때만 채운다. 조건을 하나씩 뺐을 때의 건수 중 큰 순 최대 2개: `[{ "drop": "status", "total": 8 }, { "drop": "price", "total": 3 }]`. `drop`은 `status`/`region`/`supply`/`price`/`area`/`notice_type`/`house_kind`. 버튼 문구는 클라이언트가 만든다 |
| `last_crawl` | 마지막 `success`인 수집 실행의 종료 시각. 24시간을 넘으면 `stale: true`(P-15, E-10) |

- `[포함해서 보기]`(P-04)는 별도 파라미터 없이 클라이언트가 URL에서 `supply`와 `house_kind`를 빼고 다시 부른다.
- 정렬이 같으면 공고번호 내림차순으로 고정해 `offset` 페이지가 겹치지 않게 한다.

---

## 3. 공고 상세

`GET /api/notices/{house_manage_no}` — S-03. 한 번에 모든 블록을 준다. 블록 B·C는 추출 상태(P-06)에 따라 비어 있을 수 있다.

**응답 200** (생략 표기: `…`)

```json
{
  "header": {
    "house_manage_no": "2026000514",
    "name": "○○자이 더 센트럴",
    "notice_type": "general",
    "house_kind": "private",
    "region": "경기",
    "address": "경기도 수원시 장안구 ○○동 123",
    "status": "open",
    "d_day": 2,
    "is_corrected": true,
    "correction_count": 1,
    "last_checked_at": "2026-10-22T06:00:00+09:00",
    "removed": false,
    "notice_url": "https://www.applyhome.co.kr/…",
    "homepage_url": null,
    "phone": "031-000-0000"
  },
  "summary": {
    "total_households": 812,
    "special_households": 402,
    "general_households": 410,
    "price_min_man": 32100,
    "price_max_man": 52340,
    "move_in_ym": "202903"
  },
  "schedule": [
    { "stage": "special", "area": "all",   "start_on": "2026-10-20", "end_on": "2026-10-20", "state": "done" },
    { "stage": "rank1",   "area": "local", "start_on": "2026-10-21", "end_on": "2026-10-21", "state": "today" },
    { "stage": "winner",  "area": "all",   "start_on": "2026-10-30", "end_on": "2026-10-30", "state": "upcoming" }
  ],
  "units": [
    {
      "model_no": "01", "house_ty": "084.8860A", "exclusive_area": 84.886, "supply_area": 112.34,
      "top_price_man": 52340, "general_count": 100, "special_count": 180,
      "special_by_type": { "newlywed": 60, "first_home": 40, "multi_child": 20, "newborn": 10, "elderly_parent": 10, "youth": 0, "institution": 20, "etc": 20 }
    }
  ],
  "extraction": {
    "status": "done",
    "reprocessing": false,
    "constraints": { "items": [ … ], "missing_codes": ["residence_duty"] },
    "conditions": { "supply_types": [ { "code": "general_1st", "households": 410 } ], "items": [ … ] }
  },
  "changes": [
    { "version": 2, "detected_at": "2026-10-18T06:02:00+09:00", "items": [ { "field": "1순위 접수일", "before": "2026-10-21", "after": "2026-10-23" } ] }
  ]
}
```

**헤더·블록 A**

| 필드 | 설명 |
|---|---|
| `header.removed` | `removed_at`이 있으면 `true`. 화면이 E-08 배너를 띄운다. 이 경우에도 마지막 정보를 그대로 준다(404 아님) |
| `header.correction_count` | `current_version - 1`. `notice_url`은 청약홈 상세(PDF 아님) |
| `schedule[].state` | 서버가 오늘(KST)과 비교: `done`(종료일 < 오늘) / `today`(시작일 ≤ 오늘 ≤ 종료일) / `upcoming`. 값 없는 단계는 행을 만들지 않는다. 단계 순서는 `special → rank1 → rank2 → no_rank/general → winner → contract`이고 같은 단계는 지역(`all/local/gyeonggi/other`) 순 |
| `units[].special_by_type` | 무순위·재공급은 `null`(P-04). 화면이 `이 공고 유형은 … 제공하지 않아요`를 띄우는 근거 |
| 규제 지역 | **내려주지 않는다**(D-03). `is_regulated`는 응답에 넣지 않는다 |
| 필터 일치 행 | 서버는 모른다. 클라이언트가 URL의 필터와 `units`로 계산한다 |

**블록 B·C (`extraction`)**

| 필드 | 설명 |
|---|---|
| `status` | `pending`(준비 중) / `done` / `partial` / `failed`. `notice.summary_status`(P-06) |
| `reprocessing` | 정정 직후 재추출 중이면 `true`. 화면이 이전 내용 위에 안내를 띄운다(P-08). 이때 `items`는 **이전 버전** 추출 결과다 |
| `constraints.items[]` | 블록 B 행. 순서는 SCREENS 블록 B(`rewin_limit` → `apply_restriction` → `ineligible_limit` → `resale_limit` → `residence_duty` → `payment` → `duplicate_apply`) |
| `constraints.missing_codes` | 추출되지 않은 코드. 화면이 `이 공고문에서 찾지 못한 항목`에 이름만 나열한다 |
| `conditions.supply_types[]` | 블록 C 유형 칩. 세대수 1 이상인 특별공급 유형 + `general_1st`, `general_2nd`. 무순위·재공급은 `no_rank`/`resupply` 하나. `households`는 칩에 쓰는 세대수 |
| `conditions.items[]` | 블록 C 조건 전체. 유형별로 거르는 것은 클라이언트가 한다(`targets`에 `전체` 또는 선택한 유형 코드가 있는 것) |

항목 하나(블록 B·C 공통):

```json
{
  "id": 9812,
  "category": 4,
  "code": null,
  "targets": ["newlywed"],
  "text": "혼인 기간이 7년 이내여야 해요",
  "is_general_rule": false,
  "status": "confirmed",
  "evidence": [ { "page": 11, "quote": "혼인기간 7년 이내…", "verified": true } ]
}
```

- `category`는 1~6(DATA 2장). `code`는 분류 6(블록 B)일 때만 7개 코드 중 하나, 그 밖에는 `null`.
- `status`가 `needs_review`면 화면이 회색 `원문 확인 필요`로 보이고 체크박스를 달지 않는다(P-05). 서버는 이 항목도 그대로 준다.
- `evidence[].quote`는 서버가 300자까지만 자르고 넘으면 `…`를 붙인다. 근거가 여러 쪽이면 배열 순서가 칩에 쓰는 순서다.
- 조건표 체크 상태는 **서버로 보내지도 받지도 않는다**(P-09, D-18). 이 API에는 체크 관련 필드가 없다.
- 블록 B·C는 `current_version`의 `extraction_run`에서 읽는다. 재추출 중(`reprocessing: true`)에는 직전 완료 버전의 항목을 준다.

**정정 이력 (`changes`)**

- `version` 내림차순. 정정이 없으면 빈 배열이고 화면은 블록 D를 숨긴다.
- `items`가 비어 있으면(정정 안내 상자를 못 뽑았을 때) 화면이 `공고문이 새로 올라왔어요. 내용을 다시 정리했어요.`를 보인다.
- `before`·`after`는 DB 문자열 그대로다. 날짜 표기 변환은 클라이언트가 하지 않는다(일정이 아닌 항목도 섞여 있다).

**오류:** 없는 번호 `404 not_found`.

---

## 4. 캘린더·원문 PDF

### 캘린더 `GET /api/notices/{no}/calendar.ics`

- `Content-Type: text/calendar; charset=utf-8`, `Content-Disposition: attachment; filename="cheongdam-{no}.ics"`.
- 이벤트 구성, 설명 문구, 하루 전 알림(VALARM)은 [DATA.md 1장](DATA.md#1-공고주택형-가공-필드)을 따른다. 이미 지난 일정은 넣지 않는다. 남은 일정이 하나도 없으면 `404 not_found`(화면은 마감 공고에서 버튼을 숨긴다).
- UID는 `{공고번호}-{단계}-{지역}@cheongdam.app`로 고정한다.
- 메일(M-02, M-03)의 `캘린더에 추가` 링크도 이 URL이다.

### 원문 PDF `GET /api/notices/{no}/pdf`

- 저장한 PDF 사본을 `application/pdf`로 스트리밍하고 **HTTP Range 요청을 지원**한다(PDF.js가 필요한 쪽만 받는다). `?version=N`으로 이전 버전도 받을 수 있고 기본은 `current_version`이다.
- PDF가 없으면(`pdf_status`가 `none`·`not_pdf`·`failed`) `404 not_found`. 화면은 `청약홈에서 보기`로 대체한다.
- **D-04(자체 뷰어 가능 여부)가 확정되기 전에는 구현하지 않는다.** 불가로 결론 나면 이 엔드포인트와 S-06을 지우고 컷 순서 1번을 적용한다.

---

## 5. 알림 구독

로그인이 없으므로 **메일 속 토큰이 곧 권한**이다([ALERTS.md 3장](ALERTS.md#3-구독-데이터)). 토큰은 URL 쿼리(`?token=`)로 오고, 서버는 이를 로그에 남기지 않는다(액세스 로그에서 `token` 값을 가린다).

- **토큰:** `SecureRandom` 32바이트를 base64url로 인코딩한 43자. 구독 한 건에 `confirm_token`(M-01 링크)과 `manage_token`(M-02~M-04의 `알림 설정`·`알림 그만 받기`)이 하나씩 있고, DB에는 값 그대로 저장해 조회 키로 쓴다(D-27).
- **이메일:** 앞뒤 공백을 지우고 소문자로 바꾼다. 254자 이하, `@`와 도메인이 있는 형식만 받는다. `+태그`는 따로 합치지 않는다.

| 화면 | 엔드포인트 |
|---|---|
| S-08a 구독 시트 | `POST /api/alerts/subscriptions` |
| S-08b 구독 완료 | `POST /api/alerts/confirm` |
| S-08c 구독 관리 | `GET /api/alerts/manage`, `PUT·DELETE /api/alerts/manage/subscriptions/{id}`, `POST /api/alerts/manage/unsubscribe-all`, `POST /api/alerts/manage-link` |
| S-08d 해지 | `POST /api/alerts/unsubscribe` |

**구독 신청 `POST /api/alerts/subscriptions`**

```json
{ "email": "a@example.com", "consent": true,
  "conditions": { "v": 1, "region": ["서울", "경기"], "supply": ["newlywed"], "price_max": 50000 } }
```

- `conditions`는 SCHEMA 8장의 v1 키만 받는다. `status`, `q`, `sort`가 오면 `400 invalid_param`. 서버가 키와 값 배열을 정렬해 `conditions_hash`를 만든다.
- `consent`가 `true`가 아니면 `400`. 이메일은 소문자로 정규화한다.
- `202`: 확인 메일(M-01)을 보냈다. 응답 본문은 `{ "status": "confirmation_sent" }`.
- E-17: 이미 확인된 같은 조건이면 `409 already_subscribed`. 확인 대기 중이면 같은 확인 토큰으로 확인 메일을 다시 보내고 `requested_at`을 지금으로 갱신해(7일을 다시 센다) `202`.
- E-18: 이메일에 이미 3개(확인 대기·구독 중)이면 `409 subscription_limit`. 화면이 `[알림 설정 메일 받기]`를 보인다. `suspended`·`unsubscribed`는 세지 않는다. 개수 확인과 저장 사이에 같은 이메일의 요청이 끼어들지 않도록 한 트랜잭션에서 `pg_advisory_xact_lock(hashtext(email))`로 직렬화한다.
- 발송 중지(E-15)된 이메일의 재신청은 새 `pending` 구독으로 받는다(확인 메일부터 다시).
- 조건이 비어 있어도(`{ "v": 1 }`) 받는다. 모든 새 공고를 받는 구독이다.
- **요청 한도(429 `rate_limited`):** 구독 신청은 같은 IP 시간당 10회, 같은 이메일 시간당 3회. `manage-link`는 같은 이메일 시간당 1회, 같은 IP 시간당 10회. 서버 메모리의 고정 시간 창 카운터로 센다. `// shortcut: VM 한 대·재시작하면 초기화, 서버를 늘리거나 남용이 보이면 DB·Redis로`.

**구독 확인 `POST /api/alerts/confirm`** `{ "token": "…" }`

- `200`: `{ "conditions": {…}, "match_count": 7 }`. `match_count`는 지금 조건에 맞는 접수 예정·접수 중 공고 수(S-08b).
- 이미 확인한 토큰이면 `200`으로 같은 응답(멱등). 없는 토큰 `404`, 7일이 지난 확인 토큰 `410 token_expired`(E-14).
- 만료는 `requested_at`으로부터 7일이다. 7일이 지난 `pending` 행은 하루 한 번 도는 파기 배치가 삭제하므로([SCHEMA.md 6장](SCHEMA.md#6-알림)), 삭제된 뒤에는 `404`가 나온다. 확인 화면은 `410`과 `404`를 모두 E-14 문구(`확인 기간이 지났어요. 다시 구독해 주세요.`)로 보인다. 확인 토큰은 확인 전에는 만료나 오타 말고 `404`가 될 이유가 없기 때문이다.
- GET이 아니라 POST인 이유: 메일 보안 검사기가 링크를 미리 열어 구독이 저절로 확정되는 것을 막는다. 링크는 SPA 화면(`/alerts/confirm?token=`)을 열고, 화면이 이 API를 부른다.

**구독 관리 `GET /api/alerts/manage?token=…`** (S-08c)

```json
{ "email_masked": "ab***@gmail.com",
  "subscriptions": [ { "id": 31, "conditions": {…}, "status": "active" } ] }
```

- 토큰이 가리키는 구독의 이메일에 속한 구독(확인 대기·구독 중)을 최대 3개 준다. 이메일 전체는 내려주지 않는다.
- `id`는 이 응답을 받은 토큰의 이메일 범위 안에서만 쓸 수 있다. 서버는 매 요청마다 `id`가 토큰의 이메일 소유인지 확인한다.

**조건 바꾸기 `PUT /api/alerts/manage/subscriptions/{id}?token=…`** `{ "conditions": {…} }` → `200` 새 조건. 다음 알림부터 적용(검수 #21). 같은 이메일에 같은 조건이 이미 있으면 `409 already_subscribed`.

**조건 해지 `DELETE /api/alerts/manage/subscriptions/{id}?token=…`** → `204`. 구독을 `unsubscribed`로 바꾸고 M-04를 보낸다. 그 이메일의 마지막 구독이면 이메일을 지운다(P-09).

**모두 해지 `POST /api/alerts/manage/unsubscribe-all?token=…`** → `204`. 위와 같다.

**메일 링크 해지 `POST /api/alerts/unsubscribe`** `{ "token": "…" }` (S-08d)

- 확인 단계 없이 그 메일이 속한 조건 **하나**를 해지한다. `200`: `{ "conditions": {…} }`. 화면이 `[다시 구독하기]`에서 이 `conditions`로 구독 시트를 채워 연다(D-24: 이메일은 이미 지워졌을 수 있으므로 새로 입력받는다).
- 이미 해지된 토큰은 `404 not_found`(E-16).
- 메일 헤더 `List-Unsubscribe`에는 `https://cheongdam.app/api/alerts/unsubscribe?token=…`을 넣고 `List-Unsubscribe-Post: List-Unsubscribe=One-Click`을 함께 보낸다(RFC 8058). 메일 앱의 해지 버튼은 이 URL로 `application/x-www-form-urlencoded` POST를 보내므로, **쿼리의 `token`도 같은 엔드포인트가 받는다.**

**관리 메일 다시 받기 `POST /api/alerts/manage-link`** `{ "email": "a@example.com" }` (E-18)

- 구독이 있든 없든 항상 `202`로 답한다(이메일이 있는지 알려 주지 않는다). 있으면 관리 링크 메일을 보낸다. 같은 이메일은 시간당 한 번으로 제한한다.
- 메일에 넣는 링크는 그 이메일의 구독 중 가장 오래된 `active` 구독의 `manage_token`이다. `active`가 없으면 가장 오래된 `pending`의 것을 쓴다. 관리 화면은 토큰이 가리키는 이메일의 구독을 모두 보여 주므로 어느 것이든 같다.
- 구독 신청의 `409 already_subscribed`는 E-17이 정한 대로 존재를 알려 주지만, 그 외 경로에서는 이메일 존재 여부를 드러내지 않는다.

---

## 6. API 밖에서 Spring이 하는 일

| 경로 | 동작 | 근거 |
|---|---|---|
| `/notices/{no}` (화면 URL) | `index.html`에 공고별 `<title>`, 설명, OG 태그를 넣어 돌려준다. 본문은 React가 그린다 | PRD 8.1. 공유 미리보기 |
| `/api/actuator/health` | 헬스 체크. 외부에 노출하는 actuator는 이것 하나 | 골격 PR |
| 알림 매칭·발송 | 하루 한 번 07:30 직전에 구독 조건으로 대상을 찾아 `alert_outbox`에 쌓고(D-28), 읽어 M-02, M-03 렌더링·발송 | [ALERTS.md 4장](ALERTS.md#4-알림-정책) |

- 두 번째 행 때문에 Nginx 설정이 바뀐다. 지금은 `/api/`만 Spring으로 넘기는데, `/notices/*`도 넘겨야 한다(정적 파일 `index.html`이 아니라 Spring이 만든 HTML이 나가야 한다). 배포 설정 PR에서 함께 고친다.

---

## 7. 열린 질문

| # | 질문 | 영향 | 정할 때 |
|---|---|---|---|
| 1 | `district`(시·군·구)를 서버가 `address`에서 자르면 `세종특별자치시`·`제주` 같은 예외가 생긴다. 규칙으로 충분한가, 수집 때 A가 컬럼으로 저장하는가 | 목록 카드 | 목록 구현 중. 실제 주소 샘플로 확인 |
| 2 | 목록 `limit`·`offset`은 수집이 겹치면 페이지 사이에서 공고가 밀린다. MVP는 하루 2회 수집이라 감수하는가 | 더 보기 | 목록 구현 후 |
| 3 | 상세가 한 번에 모든 블록을 준다. 항목이 130개인 공고(공공분양 93쪽)도 응답이 수백 KB가 되지 않는지 | 상세 성능 | 상세 구현 후 실측. 크면 `extraction`을 `/api/notices/{no}/extraction`으로 분리 |
| 4 | 정정 직후 재추출 중일 때 직전 버전 항목을 준다. 이 버전의 `evidence.page`가 새 PDF와 쪽이 달라 S-06이 잘못된 쪽을 열 수 있다 | P-08, S-06 | 4주차 정정 구현 때. `evidence`에 `version`을 함께 줄지 |
| 5 | ~~구독 신청 남용 방지(메일 폭탄)의 한도 값~~ | 구독 API | 확정(D-27): 위 5장의 요청 한도. 운영하며 조정 |
