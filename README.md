# 청담

청약홈 APT 분양 공고를 모아 일정, 당첨 후 제약, 공급 유형별 자격 조건을 원문 근거와 함께 보여 주는 서비스.

- 기획: [PRD](docs/PRD.md) · [서비스 기획서](docs/SERVICE_SPEC.md) · [일정](docs/SCHEDULE.md)
- 상세 명세: [화면](docs/spec/SCREENS.md) · [데이터](docs/spec/DATA.md) · [알림](docs/spec/ALERTS.md) · [API](docs/spec/API.md) · [DB 스키마](docs/spec/SCHEMA.md)
- 구현 설계: [알림 메일 발송](docs/ALERT_DELIVERY.md)

| 폴더 | 내용 |
|---|---|
| `backend/` | Spring Boot (Java 21, Gradle). API, Flyway 마이그레이션(`db/migration`) |
| `frontend/` | React + Vite + TypeScript |
| `experiments/` | 1주차 실험 코드(PDF 추출, 정정 확인) |

## 로컬 실행

Docker만 있으면 된다.

```bash
./dev.sh up      # DB + 백엔드 + Nginx(React)를 빌드해서 실행
./dev.sh logs    # 로그 (./dev.sh logs backend)
./dev.sh stop    # 중지, DB 데이터 유지
./dev.sh down    # 중지, DB 데이터 삭제
```

| 주소 | 내용 |
|---|---|
| <http://localhost:8000> | 화면 |
| <http://localhost:8080/api/notices> | API (내 컴퓨터에서만 접속됨) |
| `localhost:55432` | PostgreSQL. 사용자·DB `cheongdam`, 비밀번호 `dev` |

- 샘플 공고는 기본으로 넣지 않는다(실제 수집 데이터만 보인다). `APP_DEV_SEED=true ./dev.sh up`으로 띄우면 시작할 때마다 번호가 `DEV`로 시작하는 샘플 공고 8건을 다시 넣는다(`backend/src/main/resources/db/seed/dev-seed.sql`). 날짜는 실행한 날 기준이다. 정정·추출 결과가 있는 `/notices/DEV001`, 무순위 `/notices/DEV004`, 마감 `/notices/DEV006` 등을 볼 수 있다.
- 코드를 고친 뒤에는 `./dev.sh up`을 다시 실행하면 이미지를 다시 빌드한다. 화면을 계속 고칠 때는 아래 직접 실행이 빠르다.

### 알림 메일을 실제로 받아 보기

기본값은 **메일을 보내지 않고 로그만 찍는다**(`./dev.sh logs backend`에 확인 링크가 나온다). 조건에 맞는 공고를 Gmail로 실제로 받아 보려면:

```bash
cp .env.mail.example .env.mail     # Gmail 계정과 앱 비밀번호를 채운다 (.gitignore가 무시한다)
APP_DEV_SEED=true ./dev.sh up      # 샘플 공고 8건을 넣고 SMTP 발송기로 뜬다
```

앱 비밀번호는 Google 계정 → 보안 → 2단계 인증을 켠 뒤 "앱 비밀번호"에서 16자리를 발급받는다. Gmail은 발신 주소가 계정 주소여야 해서 보내는 사람은 그 계정 그대로 나간다.

1. <http://localhost:8000>에서 조건을 고르고 메일 주소를 넣는다 → **확인 메일(M-01)** 이 온다
2. 메일의 "알림 시작하기"를 누른다. 링크가 `localhost:8000`이라 **같은 컴퓨터에서** 열어야 한다
3. `./dev.sh alert` → 매칭과 발송을 지금 한 번 돌린다 → **새 공고 메일(M-02)** 이 온다

```json
{"active_subscriptions":1,"rewound":1,"queued":1,"sent":1,"pending":0,"failed":0,"last_error":null}
```

- 평소에는 매일 07:30 이후 10분 틱에 자동으로 나간다(P-17). `./dev.sh alert`는 그것을 기다리지 않을 때만 쓰는 dev 전용 길이다.
- 샘플 공고는 "이틀 전에 수집된" 것이라 방금 확인한 구독의 기준 시각보다 앞선다. 그래서 `alert`는 기준 시각을 3일 뒤로 물린 다음 매칭한다. 물리지 않으려면 `./dev.sh alert 0`.
- 한 번 보낸 공고는 다시 보내지 않는다(구독당 하루 한 통, P-19). **같은 메일을 다시 받아 보려면** `APP_DEV_SEED=true ./dev.sh up`으로 샘플을 다시 넣는다(`DEV` 공고의 발송 이력이 함께 지워진다).
- `sent`가 0인데 `failed`나 `pending`이 1이면 `last_error`에 이유가 있다(앱 비밀번호가 틀리면 SMTP 인증 실패).
- 메일 모양만 보려면 발송 없이 <http://127.0.0.1:8080/api/dev/mail/new>(`new-many`, `correction`, `combined`, `confirm`, `unsubscribed`, `manage-link`, `?format=text`).
- 다른 앱이 8080을 쓰고 있으면 `localhost:8080`이 그쪽으로 갈 수 있다. 컨테이너는 IPv4에만 묶여 있으므로 `127.0.0.1:8080`을 쓴다.

### 직접 실행 (코드 수정이 바로 반영됨)

```bash
docker run -d --rm --name cheongdam-dev-db -e POSTGRES_DB=cheongdam -e POSTGRES_USER=cheongdam \
  -e POSTGRES_PASSWORD=cheongdam -p 55432:5432 postgres:17
cd backend && DB_URL=jdbc:postgresql://localhost:55432/cheongdam SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
cd frontend && npm install && npm run dev      # http://localhost:5173, /api는 localhost:8080으로 프록시
```

## 테스트

```bash
cd backend && ./gradlew test               # Testcontainers가 PostgreSQL을 띄우므로 Docker가 필요
cd frontend && npm run lint && npm run build
```

## 배포 구성

`docker-compose.yml`이 배포용이다(PostgreSQL, Spring, Nginx). 서버에서는 `docker-compose.dev.yml`, `.env.dev`, `dev.sh`를 쓰지 않는다.
`dev` 프로파일과 `APP_DEV_SEED=true`는 시작할 때 `DEV%` 공고를 지우고 샘플을 넣으므로 운영 DB에서 켜지 않는다.

```bash
cp .env.example .env        # DB_PASSWORD를 바꾼다
docker compose up -d --build
```
