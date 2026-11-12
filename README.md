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
# .env의 메일 세 줄(APP_MAIL_PROVIDER, SPRING_MAIL_USERNAME, SPRING_MAIL_PASSWORD) 주석을 풀고 채운다
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

운영 서버는 메모리·디스크가 작아 서버에서 빌드하지 않는다. 아래 태그 배포를 쓴다.

### 태그 배포 (GitHub Actions)

`v`로 시작하는 태그를 push하면 [`deploy.yml`](.github/workflows/deploy.yml)이 운영 서버에 배포한다.

```bash
git checkout main && git pull
git tag v1.2.0
git push origin v1.2.0
```

1. CI(백엔드 빌드·테스트, 프론트 린트·빌드)를 돌리고, 실패하면 배포하지 않는다.
2. 러너에서 `cheongdam-{backend,batch,web}:prod` 이미지를 빌드한다.
3. 서버에서 안 쓰는 이미지·빌드 캐시·오래된 로그를 지우고, 디스크 여유가 500MB 미만이면 멈춘다. 실행 중인 앱의 이미지는 지우지 않는다.
4. `PROD_ENV` 시크릿을 서버 `.env`로 쓴다. 필수 키(`DB_PASSWORD`, `APP_BASE_URL`, `DATA_GO_KR_KEY`, `KAKAO_REST_KEY`)가 없거나 `DB_PASSWORD`가 서버 값과 다르면 멈춘다. 이전 파일은 `.env.bak`으로 남는다.
   이어서 [`deploy/docker-compose.prod.yml`](deploy/docker-compose.prod.yml)을 서버 `docker-compose.yml`로 복사한다. 서버에서 문법을 확인한 뒤 바꾸고, 이전 파일은 `docker-compose.yml.bak`으로 남는다.
5. 이미지를 받는 동안 배치 컨테이너만 잠시 멈추고, `docker save | gzip | ssh … docker load`로 서버에 올린다.
6. 서버의 `/root/cheongdam-prod`에서 `docker compose -p cheongdam up -d`(배치도 다시 뜬다) 후 옛 이미지를 지운다.
7. `/api/notices`가 2분 반 안에 응답하지 않으면 실패로 끝내고 백엔드 로그를 남긴다.

진행 상황은 GitHub의 Actions 탭 → Deploy에서 본다. 배포는 한 번에 하나만 돌고, 진행 중인 배포는 취소되지 않는다.

**처음 한 번 설정**: 저장소 Settings → Environments에 `production`을 만들고 아래 시크릿을 넣는다. 승인자(Required reviewers)를 지정하면 승인 뒤에 배포가 시작된다.

| 시크릿 | 값 |
|---|---|
| `SSH_HOST` | 운영 서버 공인 IP |
| `SSH_PRIVATE_KEY` | 서버 접속용 개인 키(`.pem`) 내용 |
| `SSH_KNOWN_HOSTS` | `grep '^<서버 IP> ' ~/.ssh/known_hosts` 출력 |
| `VITE_KAKAO_JS_KEY` | 카카오 JavaScript 키(웹 번들에 들어간다) |
| `PROD_ENV` | 운영 `.env` 파일 전체. 처음에는 서버 파일을 그대로 넣는다(아래) |

서버의 `/root/cheongdam-prod`는 배포가 쓰는 작업 폴더다. compose 파일과 `.env`를 배포 때마다 덮어쓰므로 직접 고치지 않는다.
처음 한 번은 이 폴더와 `.env`가 있어야 한다(`DB_PASSWORD`를 서버 값과 비교하기 때문). 운영 설정을 바꿀 때는 compose는 `deploy/docker-compose.prod.yml`을 PR로, 값은 `PROD_ENV` 시크릿으로 바꾼 뒤 태그를 올린다.

**운영 `.env` 관리**: 원본은 `PROD_ENV` 시크릿이다. 서버 `.env`를 직접 고치면 다음 배포 때 덮어써진다.
처음 한 번은 서버 파일을 그대로 올린다(값이 화면에 나오지 않는다).

```bash
ssh runvas 'cat /root/cheongdam-prod/.env' | gh secret set PROD_ENV -R lovelyAlien/cheongdam --env production
```

키를 추가하거나 바꿀 때는 운영 `.env` 전체를 고쳐 같은 명령으로 다시 등록한 뒤 태그를 올린다.
시크릿은 다시 꺼내 볼 수 없으므로 고칠 원본은 서버 `.env`(또는 `.env.bak`)에서 가져온다.
`DB_PASSWORD`는 바꾸지 않는다(DB는 처음 만들 때의 비밀번호를 쓴다).

**되돌리기**: 자동 롤백은 없다. 같은 `:prod` 태그를 덮어쓰므로, 되돌릴 커밋에 새 태그를 붙여 다시 배포한다.

```bash
git tag v1.2.1 <되돌릴 커밋>
git push origin v1.2.1
```
