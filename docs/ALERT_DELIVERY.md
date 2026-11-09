# 알림 메일 발송 설계 (ALERT_DELIVERY)

- 작성일: 2026-10-31 / 수정일: 2026-11-09
- 상태: SMTP 발송기와 커밋 후 발송, 수집 지연 대비, 로컬에서 Gmail로 보내 보는 길까지 구현. 파기 배치가 남음
- 상위 문서: [알림 기획](spec/ALERTS.md) · [DB 스키마 6장](spec/SCHEMA.md#6-알림) · [API 5장](spec/API.md#5-알림-구독) · [일정 5주차](SCHEDULE.md)
- 범위: "사용자가 저장한 조건에 맞는 공고를 모아 정해진 시각에 메일로 보낸다"를 **운영에서 실제로 동작**시키기까지의 구현 설계

> 기획(P-17~P-20, E-14~E-18)과 스키마(D-27, D-28)는 이미 확정돼 있다. 이 문서는 그 위에 발송 수단과 운영 안전장치만 다룬다. 기획을 바꿔야 하는 부분은 P-번호를 고치지 않고 [10장](#10-결정-후보-d-와-열린-질문)에 D- 후보로 적는다.

**목차**

1. [한눈에: 된 것과 남은 것](#1-한눈에-된-것과-남은-것)
2. [지금 동작하는 흐름](#2-지금-동작하는-흐름)
3. [메일 발송 어댑터 (SMTP)](#3-메일-발송-어댑터-smtp)
4. [메일을 트랜잭션 밖에서 보낸다](#4-메일을-트랜잭션-밖에서-보낸다)
5. [반송·수신 거부 (E-15)](#5-반송수신-거부-e-15)
6. [개인정보 파기 배치 (P-09, E-14)](#6-개인정보-파기-배치-p-09-e-14)
7. [발송 시각 보강 (P-17)](#7-발송-시각-보강-p-17)
8. [설정과 배포 전환](#8-설정과-배포-전환) — [8.3 로컬에서 Gmail로 보내 본다](#83-로컬에서-gmail로-보내-본다)
9. [테스트](#9-테스트)
10. [결정 후보(D-)와 열린 질문](#10-결정-후보-d-와-열린-질문)
11. [확장안: 사용자가 발송 시각을 고를 때](#11-확장안-사용자가-발송-시각을-고를-때)

---

## 1. 한눈에: 된 것과 남은 것

| 단계 | 상태 | 코드 |
|---|---|---|
| 구독 신청·확인·관리·해지 API (E-17, E-18, 요청 한도) | 됨 | `SubscriptionService`, `AlertController`, `RateLimiter` |
| 구독·해지 화면 (S-08a~d) | 됨 | `SubscribeSheet.tsx`, `AlertConfirmPage.tsx`, `AlertManagePage.tsx`, `AlertUnsubscribePage.tsx` |
| 구독·발송 대기·발송 이력 테이블 | 됨 | `V1__init.sql` |
| 대상 매칭·적재 (P-18, P-19) | 됨 | `AlertDispatchService.enqueue` |
| 발송 큐 소비·재시도·이력 기록 (P-20) | 됨 | `AlertDispatchService.sendDue` |
| 발송 시각 스케줄 (P-17) | 됨 | `AlertDispatchJob` |
| 메일 제목·HTML·텍스트 (M-01~M-04) | 됨 | `AlertMailRenderer`, `DevMailPreviewController` |
| **SMTP 발송기** | **됨** | `SmtpAlertMailer`, `MailConfig` ([3장](#3-메일-발송-어댑터-smtp)) |
| **커밋 후 발송** | **됨** | `AlertMailEvents`, `AlertMailDispatcher` ([4장](#4-메일을-트랜잭션-밖에서-보낸다)) |
| **실패 3분류·묵은 대기 행 처리** | **됨** | `TransientMailException`, `MailProviderException` ([3.3](#33-실패를-세-가지로-나눈다)) |
| **로컬에서 Gmail로 보내 보기** | **됨** | `.env.mail`, `DevAlertDispatchController`, `./dev.sh alert` ([8.3](#83-로컬에서-gmail로-보내-본다)) |
| 반송·스팸 반영 (E-15) | 수동 운영 | [5장](#5-반송수신-거부-e-15) |
| 개인정보 파기 배치 (P-09, E-14) | **남음** | [6장](#6-개인정보-파기-배치-p-09-e-14) |
| **수집 지연 대비 (P-17)** | **됨** | `AlertDispatchJob` ([7장](#7-발송-시각-보강-p-17)) |
| 발신 도메인 인증 (SPF·DKIM·DMARC) | **남음** | 도메인 등록 후. [8.2](#82-켜는-순서) |

## 2. 지금 동작하는 흐름

| 시각(KST) | 일어나는 일 | 근거 |
|---|---|---|
| 06:00 / 18:00 | A의 수집 배치가 공고·정정을 넣는다 | P-10 |
| 07:30 이후 첫 10분 틱 | 수집이 끝나 있으면 구독마다 대상을 `alert_outbox`에 한 행으로 쌓는다 | P-17, P-19 |
| 09:30 이후 | 수집이 아직 `running`이어도 더 기다리지 않고 쌓는다. 3시간 넘게 도는 수집은 그 전에 죽은 것으로 본다 | [7장](#7-발송-시각-보강-p-17) |
| 같은 틱부터 10분마다 | **오늘 날짜의** `pending` 대기 메일을 보낸다. 실패하면 1시간 뒤 한 번 더 | P-20 |

- 시각 판단은 `ClockConfig`가 `Clock.system(Asia/Seoul)`을 주고 `@Scheduled(zone="Asia/Seoul")`을 쓴다. 컨테이너 TZ가 UTC여도 07:30은 KST 07:30이다.
- 하루 한 통은 `alert_outbox`의 `UNIQUE (subscription_id, send_date)`와 적재 전 존재 검사로 두 겹으로 막혀 있다.
- 보낸 뒤 `last_sent_at`을 **적재 시각(`created_at`)**으로 올린다. 적재와 발송 사이에 들어온 공고는 다음 날로 넘어간다(누락 없음).
- 메일이 영구 실패(`failed`)하면 `last_sent_at`이 올라가지 않으므로 그 공고들은 **다음 날 다시 대상이 된다**(`alert_log`에 기록되지 않았기 때문). 복구는 자동이다.

---

## 3. 메일 발송 어댑터 (SMTP)

### 3.1 전송 수단

**표준 SMTP를 쓴다.** 개발 중에는 Gmail, 런칭 때는 호스트와 계정만 바꾼다. PRD 8.1이 정한 NCP Cloud Outbound Mailer는 쓰지 않는다([D-29](#10-결정-후보-d-와-열린-질문)).

| | |
|---|---|
| 개발 | `smtp.gmail.com:587` + 2단계 인증 + 앱 비밀번호 |
| 런칭 | 미정. Brevo 무료(300통/일, 푸터 붙음) 또는 AWS SES(6개월 $200 크레딧, 이후 1,000통당 $0.10). [D-31](#10-결정-후보-d-와-열린-질문) |
| 전환 비용 | `spring.mail.host`와 계정만 바꾼다. **코드 변경 없음** |

Gmail의 제약 두 가지를 알고 쓴다.

- **From은 계정 주소여야 한다.** `noreply@cheongdam.app`로 보내려 하면 SPF·DKIM이 `gmail.com`으로 통과해 DMARC 정렬이 깨진다. 개발 중에는 `@gmail.com` 주소를 그대로 쓴다.
- **한도는 롤링 24시간 기준 수신자 500명**이다. 구독자 100명 × 조건 3개 = 하루 최대 300통이라 들어가지만, 실제로는 조건에 맞는 새 공고가 있을 때만 나가므로 훨씬 적다.

### 3.2 구성

```
mail/
  AlertMailer.java             인터페이스 (기존)
  AlertMailRenderer.java       제목·HTML·텍스트 (기존)
  SmtpAlertMailer.java         app.mail.provider=smtp. 실제 발송
  DevLogAlertMailer.java       app.mail.provider=log. 링크를 로그로
  UnconfiguredAlertMailer.java app.mail.provider=none (기본). digest()는 던진다
  MailConfig.java              시작 시 검증
  AlertMailEvents.java         커밋 후 보낼 메일 (4장)
  AlertMailDispatcher.java     AFTER_COMMIT 리스너 (4장)
  DevMailPreviewController.java  메일 모양 미리보기 (dev 전용, 발송 안 함)
  DevAlertDispatchController.java 매칭·발송을 지금 한 번 (dev 전용, 8.3)
  TransientMailException.java  일시 실패
  MailProviderException.java   발송기 설정·한도 문제
```

- `SmtpAlertMailer`는 `AlertMailRenderer`의 `RenderedMail(subject, html, text, listUnsubscribeUrl)`을 받아 `MimeMessageHelper`로 **multipart/alternative(텍스트 + HTML, UTF-8)** 를 만든다.
- `listUnsubscribeUrl`이 있으면 `List-Unsubscribe: <주소>`와 `List-Unsubscribe-Post: List-Unsubscribe=One-Click`을 넣는다(RFC 8058). 메일 앱의 해지 버튼은 `POST /api/alerts/unsubscribe`로 가고, 그 매핑은 이미 있다.
- **JavaMail 타임아웃은 기본이 무한이므로 반드시 건다.** `sendOne`이 `FOR UPDATE` 행 잠금과 DB 커넥션을 쥔 채 발송하기 때문이다. 연결 5초, 읽기·쓰기 각 10초.
- `mail.debug`는 켜지 않는다. 본문에 토큰이 든 링크가 들어 있다.
- **`management.health.mail.enabled=false`로 JavaMail 상태 검사를 끈다.** 메일 스타터를 넣으면 Spring Boot가 상태 검사를 자동으로 붙이는데, `/api/actuator/health`를 부를 때마다 SMTP에 로그인한다. 계정이 없으면 **서비스 전체가 `DOWN`(503)** 이 되고(배포에서 트래픽이 빠질 수 있다), 계정이 있으면 헬스 폴링마다 Gmail에 로그인해 수상한 접속으로 막힐 수 있다. 발송 실패는 `alert_outbox.error`가 따로 기록하므로 상태 검사로 볼 이유가 없다.

### 3.3 실패를 세 가지로 나눈다

`RuntimeException`을 전부 똑같이 다루면 **접근 키가 틀린 하루치 메일이 통째로 `failed`가 된다.** `SmtpAlertMailer.classify`가 Spring의 메일 예외를 우리 두 타입으로 번역해, `AlertDispatchService`는 Spring 메일 타입을 보지 않는다.

| 분류 | 판단 | `attempts` | outbox 결과 |
|---|---|---|---|
| **일시 실패** `TransientMailException` | 타임아웃, 연결 실패, 그 밖의 전부 | +1 | `pending`(1시간 뒤) → 2회째 실패면 `failed` |
| **발송기 설정·한도** `MailProviderException` | `MailAuthenticationException`, 원인 사슬에 `5.7.8`·`5.7.9`·`5.4.5`·`5.3.4`·`sending limit`·`quota exceeded` | **올리지 않는다** | `pending` 유지, `next_attempt_at = +1h` |
| **수신자 영구 실패** | 없는 주소, 반송, 수신 거부 | — | SMTP는 보낸 뒤에야 알 수 있다([5장](#5-반송수신-거부-e-15)) |

설정 오류가 며칠 이어져도 **P-19(구독당 하루 한 통)를 지키려면 장치가 둘 필요하다.** 설정 오류는 `attempts`를 쓰지 않아 행이 계속 `pending`으로 남고, `last_sent_at`도 올라가지 않아 다음 날 같은 공고가 담긴 행이 또 쌓인다.

1. 적재할 때 지난 날짜의 대기 행을 접는다. 기준 시각이 안 움직였으므로 오늘 행이 그 공고를 다시 담는다.

```sql
UPDATE alert_outbox SET status = 'failed', error = 'superseded'
 WHERE subscription_id = :s AND status = 'pending' AND send_date < :today;
```

2. `sendDue`는 **오늘 날짜만** 읽는다(`send_date = :today`). 설정을 06:00에 고쳐 적재가 돌기 전이어도 쌓인 지난 행이 한꺼번에 나가지 않는다. 재시도는 같은 날 +1시간이라 정상 발송은 잃지 않는다.

### 3.4 빈 선택과 시작 시 검증

```properties
app.mail.provider=smtp   # smtp | log | none(기본)
```

서로 배타적인 `@ConditionalOnProperty`로 고른다. `none`에만 `matchIfMissing = true`를 둬서 프로퍼티가 없는 테스트에서도 빈이 하나는 있게 한다.

그리고 **발송기 없이 배치가 켜지는 것을 시작할 때 막는다.** 전에는 주석으로만 있던 안전장치다. 검증은 `MailConfig`에 두어, 발송기 작업과 배치 작업이 같은 파일을 건드리지 않게 한다.

```java
if (dispatchEnabled && provider.equals("none")) throw new IllegalStateException(...);
```

---

## 4. 메일을 트랜잭션 밖에서 보낸다

`SubscriptionService`는 클래스 전체가 `@Transactional`인데 그 안에서 메일을 보내고 있었다. 로그만 찍을 때는 무해했지만 실제 발송이 들어가면 깨진다.

**해지가 롤백된다.** `unsubscribeByLink`는 확인 단계 없이 바로 해지하는 경로다(S-08d). M-04 전송이 실패하면 트랜잭션이 되감겨 **해지가 없던 일이 된다.** 사용자는 "그만 받기"를 눌렀는데 메일이 계속 온다.

**해결:** 서비스는 `ApplicationEventPublisher`로 이벤트만 내고, `AlertMailDispatcher`가 `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`로 받아 보낸다.

- `fallbackExecution = true`: 트랜잭션 밖에서 발행된 이벤트가 조용히 버려지는 것을 막는다.
- 리스너는 예외를 삼키고 로그만 남긴다. 커밋 뒤 콜백에서 던지면 이미 끝난 요청이 500이 된다.
- **이벤트에 이메일 주소를 값으로 담는다.** 해지는 같은 트랜잭션에서 `clearEmail()`로 주소를 지우므로(P-09), 리스너가 엔티티를 다시 읽으면 주소가 없다.

| 메일 | 커밋 후 실패하면 | 복구 |
|---|---|---|
| M-01 확인 | 구독은 `pending`으로 남고 메일이 안 간다 | 다시 신청하면 E-17이 같은 토큰으로 재발송한다 |
| M-04 해지 | 해지는 이미 끝났고 완료 메일만 안 간다 | 복구하지 않는다. 상태가 사용자 의도와 맞다 |
| 관리 링크 | 메일만 안 간다 | 다시 요청한다(시간당 1회) |

알림 메일(M-02·M-03)은 대상이 아니다. `alert_outbox`가 이미 트랜잭션 아웃박스 역할을 한다.

---

## 5. 반송·수신 거부 (E-15)

**SMTP에는 발송 결과를 돌려주는 API가 없다.** 반송 메일은 발신 계정의 받은편지함으로 들어온다. MVP에서는 자동화하지 않는다.

- 운영 절차: **주 1회 발신 계정의 받은편지함을 보고**, 반송된 주소의 구독을 `suspended`로 바꾼다(E-15). 이메일은 차단용으로 남긴다.
- 자동화하려면 받은편지함을 IMAP으로 파싱하거나, 반송 웹훅이 있는 발송기(AWS SES + SNS, Brevo 웹훅)로 옮긴다. [D-30](#10-결정-후보-d-와-열린-질문).
- `noreply@cheongdam.app`로 오는 메일은 **Cloudflare Email Routing**(무료)으로 개인 메일함에 포워딩해 받는다.

---

## 6. 개인정보 파기 배치 (P-09, E-14)

SCHEMA 6장이 "이 파기 배치는 B가 하루 한 번 돌린다"고 적었으나 **아직 구현이 없다.** 지금은 사용자가 해지 버튼을 눌렀을 때만 이메일이 지워진다.

`PrivacyPurgeJob` — `@Scheduled(cron = "0 10 3 * * *", zone = "Asia/Seoul")`

| # | 대상 | 처리 | 근거 |
|---|---|---|---|
| 1 | `status='pending' AND requested_at < now()-7일` | 행 삭제(`alert_outbox`는 CASCADE) | E-14 |
| 2 | `status='unsubscribed' AND email IS NOT NULL`이고 그 이메일에 `pending`·`active`가 없음 | `email = NULL` | P-09 |
| 3 | `status='failed' AND created_at < now()-30일`인 outbox | 삭제 | 큐 청소 |

- 2번은 안전망이다. 해지 경로가 이미 지우지만, 7일 미확인 삭제나 비정상 종료로 남는 주소를 쓸어 담는다.
- `suspended`의 이메일은 지우지 않는다(차단 목록). `alert_log`는 이메일이 없으므로 그대로 둔다.
- **토글을 발송과 분리한다**: `app.alerts.purge.enabled`(기본 `true`). 메일이 꺼져 있어도 파기는 돌아야 한다.

---

## 7. 발송 시각 보강 (P-17)

`AlertDispatchJob.tick()`은 수집이 돌고 있으면 적재를 건너뛰고 다음 틱을 기다린다. 그대로 두면 **수집 배치가 죽으면서 `running` 행을 남겼을 때 알림이 영구히 멈춘다.** `crawl_run`의 `started_at`으로 한계를 둔다.

| 상수 | 값 | 뜻 |
|---|---|---|
| `FROM` | 07:30 | 적재 시작 가능 시각(P-17) |
| `STALE_CRAWL` | 3시간 | 이보다 오래된 `running`은 죽은 것으로 보고 기다리지 않는다 |
| `DEADLINE` | 09:30 | 이 시각이 지나면 수집 상태와 무관하게 보낸다 |

수집을 기다리지 않고 쌓은 날은 `log.warn`에 그 수집이 언제부터 `running`인지 남긴다.

- 기다릴지 판단은 `AlertDispatchJob.waitForCrawl(now, runningSince)` 하나로 모았다. `runningSince`는 `crawl_run`에서 **가장 최근에 시작된** `running` 행의 `started_at`이다(`findFirstByStatusOrderByStartedAtDesc`). 죽은 행이 남아 있어도 방금 시작한 수집이 있으면 그것을 기다린다.
- 발송(`sendDue`)은 이 판단과 무관하게 매 틱 돈다. 적재를 건너뛴 틱에도 어제 설정 문제로 밀린 메일이 나갈 수 있어야 한다.

---

## 8. 설정과 배포 전환

### 8.1 설정 키

| 키 | 기본 | 설명 |
|---|---|---|
| `app.mail.provider` | `none` | `smtp` / `log` / `none` |
| `app.mail.from` | (빈 값 → `spring.mail.username`) | 발신 주소 |
| `app.mail.from-name` | `청담` | 발신자 이름 |
| `spring.mail.host` | `smtp.gmail.com` | |
| `spring.mail.port` | `587` | STARTTLS |
| `spring.mail.username` / `password` | (빈 값) | **환경 변수로만 넘긴다** |
| `app.alerts.dispatch.enabled` | `false` | 매칭·발송 배치. dev는 `true` |
| `app.alerts.purge.enabled` | `true` | 파기 배치(6장, 미구현) |

`application-dev.properties`는 `app.mail.provider=${APP_MAIL_PROVIDER:log}`라 로컬에서는 기본이 로그다.

**비밀은 커밋하지 않는다.** `.env.dev`는 git에 올라가 있고 "비밀 아님"이라고 적혀 있으므로 여기에 넣지 않는다. `.env.mail`에 두면 `dev.sh`가 읽는다([8.3](#83-로컬에서-gmail로-보내-본다)). 컨테이너 없이 직접 띄울 때는 셸에서 넘긴다.

```bash
APP_MAIL_PROVIDER=smtp SPRING_MAIL_USERNAME=... SPRING_MAIL_PASSWORD=... ./gradlew bootRun
```

### 8.2 켜는 순서

1. **도메인 등록.** `cheongdam.app`은 아직 미등록이다. HTTPS·OG 태그·메일 링크가 모두 이것을 쓴다
2. 개발 중에는 **Gmail 계정 주소 그대로** 발송해 M-01이 실제로 오는지 확인한다(`APP_MAIL_PROVIDER=smtp`)
3. 런칭 발송기를 정하고([D-31](#10-결정-후보-d-와-열린-질문)) `noreply@cheongdam.app`를 인증한다. **SPF·DKIM·DMARC 셋 다** — Gmail은 2024년부터 셋을 모두 본다
4. 받은 메일을 "원본 보기"로 열어 셋이 `PASS`인지 확인한다
5. 본인 구독을 하나 만들고 `app.alerts.dispatch.enabled=true`. 다음 날 아침 M-02를 확인한다(검수 #19)

### 8.3 로컬에서 Gmail로 보내 본다

`./dev.sh up`이 읽는 `.env`와 `.env.dev`는 git에 올라가 있어 비밀을 넣을 수 없다. **`.env.mail`을 따로 두고 `dev.sh`가 있을 때만 마지막에 덧씌운다**(`.env` → `.env.dev` → `.env.mail` 순으로 셸 환경변수에 올린다. compose 2.2.x가 `--env-file`을 두 번 못 받기 때문이다). `.gitignore`의 `.env.*`가 이 파일을 무시한다(템플릿 `.env.mail.example`만 예외로 추적한다).

```bash
cp .env.mail.example .env.mail     # APP_MAIL_PROVIDER=smtp, SPRING_MAIL_USERNAME, SPRING_MAIL_PASSWORD
APP_DEV_SEED=true ./dev.sh up
```

`docker-compose.dev.yml`이 이 셋을 백엔드 컨테이너로 넘긴다. **기본값을 반드시 둔다**: `${APP_MAIL_PROVIDER:-log}`가 아니라 `${APP_MAIL_PROVIDER:-}`로 넘기면 빈 문자열이 가고, Spring은 그것을 "값이 있음"으로 보아 `app.mail.provider=""`가 된다. 그러면 `matchIfMissing`이 걸리는 `none`도 포함해 발송기 빈이 **하나도 뜨지 않아 기동이 실패한다.** 호스트·포트는 넘기지 않는다(`application.properties`의 Gmail 기본값을 쓴다).

**발송 시각을 기다리지 않는다.** 스케줄은 07:30 이후 10분 틱이라(P-17) 그대로는 확인이 어렵다. `DevAlertDispatchController`(dev 프로파일 전용)가 `enqueue` → `sendDue`를 지금 한 번 돌린다.

```bash
./dev.sh alert       # POST /api/dev/alerts/dispatch?rewind_days=3
# {"active_subscriptions":1,"rewound":1,"queued":1,"sent":1,"pending":0,"failed":0,"last_error":null}
```

**`rewind_days`가 없으면 0통이 나간다.** 새 공고 매칭은 `first_collected_at > last_sent_at ?? confirmed_at`인데, 샘플 시드는 `first_collected_at = now() - 2일`로 넣고 방금 확인한 구독은 `confirmed_at = now()`다. 기준 시각이 공고보다 뒤라 아무것도 안 잡히고, **오류 없이 조용히 0통**이 된다. 그래서 매칭 전에 활성 구독의 기준 시각(`confirmed_at`, `last_sent_at`)을 그만큼 **뒤로만** 물린다. 앞으로 당기지 않으므로 여러 번 불러도 안전하다.

응답을 읽는 법:

| 상황 | 보이는 것 | 뜻 |
|---|---|---|
| 정상 | `queued=1, sent=1` | 메일이 나갔다 |
| 같은 날 두 번째 호출 | `queued=0, sent=0` | **정상이다.** 구독당 하루 한 통(P-19)과 `alert_log` 중복 방지가 막는다. 다시 받으려면 `APP_DEV_SEED=true ./dev.sh up`으로 샘플을 다시 넣는다 |
| 앱 비밀번호가 틀림 | `sent=0, pending=1`, `last_error`에 `5.7.8` | 설정 문제라 `attempts`를 쓰지 않는다(3.3). 고치고 다시 부르면 그대로 나간다 |
| 조건에 맞는 공고가 없음 | `queued=0`, `last_error=null` | 조건을 넓히거나 `APP_DEV_SEED=true`로 샘플을 넣는다 |

M-01 확인 메일의 링크는 `app.base-url`인 `http://localhost:8000`을 가리키므로 **그 컴퓨터에서 열어야** 확인이 된다.

**첫 메일은 스팸함에 갈 수 있다.** 발신 계정이 아닌 다른 주소로 보내면 받는 쪽에서 처음 보는 발신자라 걸러질 수 있다. 받은편지함에 없으면 스팸함을 먼저 본다. 런칭 때 발신 도메인을 인증하면(SPF·DKIM·DMARC, [8.2](#82-켜는-순서)) 해결되는 문제다.

> **확인 기록 (2026-11-04)** — 개인 Gmail 계정(2단계 인증 + 앱 비밀번호)에서 네이버 주소로 M-01과 M-02 **두 통 모두 수신**됐다. 발신 도메인 인증이 없는 상태였고 `alert_outbox`는 `sent`, `attempts=1`, 오류 없음. **Gmail SMTP 경로는 이대로 쓸 수 있다.** 런칭 전 남은 일은 도메인 인증과 발송기 결정([D-31](#10-결정-후보-d-와-열린-질문))뿐이다.

---

## 9. 테스트

| 대상 | 방법 | 확인 |
|---|---|---|
| SMTP 어댑터 | `SmtpAlertMailerTest` — `JavaMailSenderImpl`를 상속해 `MimeMessage`를 붙잡는다. 의존성 추가 없음 | From(주소·이름), 수신자, UTF-8 제목, `List-Unsubscribe` 두 헤더, 텍스트·HTML 두 벌 |
| 실패 분류 | 같은 테스트에서 `classify`에 Spring 예외를 넣는다 | 인증 실패·한도 → `MailProviderException`, 타임아웃 → `TransientMailException` |
| 해지 트랜잭션 | `AlertUnsubscribeMailFailureTest` — 항상 던지는 `@Primary` 메일러 | 메일이 실패해도 `unsubscribed`가 커밋되고 응답은 200, 이메일도 지워진다 |
| 설정 오류·묵은 대기 | `AlertDispatchServiceTest` — 설정 오류 뒤 날짜를 어제로 돌리고 다시 적재 | `attempts`가 0, 지난 행은 `superseded`, 메일은 **한 통만** |
| 수집 대기 한계 | `AlertDispatchJobTest` — 고정 `Clock`과 가짜 `crawl_run` | 도는 수집은 기다리고, 3시간 넘은 `running`과 09:30 이후에는 쌓는다. 07:30 전에는 발송만, 적재는 하루 한 번 |
| 기존 동작 | 기존 테스트 유지 | 일시 실패 재시도, 10건 제한, 해지된 구독 제외 |
| 실제 Gmail 발송 | 자동 테스트로 덮지 않는다. [8.3](#83-로컬에서-gmail로-보내-본다)의 수동 절차로 확인한다 — **2026-11-04 확인함** | M-01과 M-02가 오는지, 발신자 이름, 해지 버튼 |

---

## 10. 결정 후보 (D-)와 열린 질문

[SERVICE_SPEC 7장](SERVICE_SPEC.md#7-결정-사항)에 넣을 후보다. 아직 확정이 아니다.

| ID | 질문 | 제안 | 영향 |
|---|---|---|---|
| **D-29** | 메일 발송을 무엇으로 하나 | **NCP Cloud Outbound Mailer 대신 표준 SMTP.** NCP는 커스텀 헤더를 받지 않아 `List-Unsubscribe`(RFC 8058)를 넣을 수 없고, 자체 REST 서명 구현이 필요했다. SMTP는 둘 다 해결한다 | PRD 8.1의 "이메일 발송" 행을 고쳐야 한다. API.md 5장의 `List-Unsubscribe` 요구는 **그대로 지킬 수 있다** |
| **D-30** | 반송·스팸을 어떻게 안다 | **MVP는 수동.** 주 1회 발신 계정 받은편지함 확인. 자동화가 필요해지면 반송 웹훅이 있는 발송기로 옮긴다 | [5장](#5-반송수신-거부-e-15) |
| **D-31** | 런칭 발송기를 무엇으로 하나 | **미정.** Brevo 무료(0원, 300통/일, 메일에 Brevo 푸터) 또는 AWS SES(6개월 $200 크레딧, 이후 월 $1 미만, 푸터 없음, 반송 웹훅) | 코드 변경 없음. `spring.mail.host`와 계정만 바꾼다. 도메인 등록 후 결정 |
| **D-32** | 발송 시각을 사용자가 고르게 하나 | **MVP는 07:30 고정**(P-17). [11장](#11-확장안-사용자가-발송-시각을-고를-때) | 고정이면 추가 작업 없음 |

### 열린 질문

| # | 질문 | 정할 때 |
|---|---|---|
| 1 | Brevo 무료 플랜 푸터가 실제로 어떻게 보이나 | D-31 결정 전. 가입해 테스트 메일 한 통으로 확인 |
| 2 | 광고성 정보 표시 의무가 있나 | 공고 알림은 사용자가 요청한 정보성 메일로 본다. 런칭 전 확인 |
| 3 | Gmail 개발 발송의 일 한도에 실제로 닿나 | 구독자가 늘면. 롤링 24시간 수신자 500명 |

---

## 11. 확장안: 사용자가 발송 시각을 고를 때

"특정 시간"을 구독마다 다르게 하려면 이렇게 바꾼다. 지금 결정할 필요는 없다.

| 바꾸는 곳 | 내용 |
|---|---|
| 스키마 | `subscription`에 `send_hour smallint NOT NULL DEFAULT 7 CHECK (send_hour BETWEEN 0 AND 23)` |
| 적재 | `AlertDispatchJob`이 10분 틱마다 지금 시각대의 구독만 적재한다. `enqueuedOn` 메모리 플래그를 없애고 `UNIQUE (subscription_id, send_date)`에만 의존한다 |
| 수집 대기 | 06:00 수집 전 시각대(0~7시)를 고른 구독은 전날 수집분만 받는다. 선택지를 07:00·12:00·19:00 셋으로 줄이면 피할 수 있다 |
| 화면·API | S-08a에 시각 선택, 구독 API에 `send_hour` 추가 |
| 그대로인 것 | 하루 한 통 제약, `alert_log` 중복 방지, 메일 내용, 해지 흐름 |

"출근길에 확인"이라는 P-17의 근거가 약해지고 구독 시트가 복잡해진다. 사용자 인터뷰(PRD 4장) 후에 정하는 쪽을 권한다.

---

## 작업 순서

| PR | 내용 | 상태 |
|---|---|---|
| 1 | 커밋 후 발송(4장) + 실패 분류(3.3) + `app.mail.provider` 빈 선택(3.4) | **완료** |
| 2 | SMTP 어댑터(3.2) + 설정 + 시작 시 검증 + 테스트(9장) | **완료** |
| 2-1 | 로컬에서 Gmail로 보내 보는 길(8.3): `.env.mail`, compose 전달, 즉시 발송 트리거 | **완료** |
| 3 | 파기 배치(6장) + 수집 지연 한계(7장) | 남음 |
| — | 도메인 등록, 런칭 발송기 결정(D-31), 발신 도메인 인증(8.2) | 남음 |

PR 1·2는 한 변경으로 들어갔다. PR 3은 발송기와 독립이라 언제든 할 수 있다.
