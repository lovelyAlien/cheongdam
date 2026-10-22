# 청담

청약홈 APT 분양 공고를 모아 일정, 당첨 후 제약, 공급 유형별 자격 조건을 원문 근거와 함께 보여 주는 서비스.

- 기획: [PRD](docs/PRD.md) · [서비스 기획서](docs/SERVICE_SPEC.md) · [일정](docs/SCHEDULE.md)
- 상세 명세: [화면](docs/spec/SCREENS.md) · [데이터](docs/spec/DATA.md) · [알림](docs/spec/ALERTS.md) · [API](docs/spec/API.md) · [DB 스키마](docs/spec/SCHEMA.md)

| 폴더 | 내용 |
|---|---|
| `backend/` | Spring Boot (Java 21, Gradle). API, Flyway 마이그레이션(`db/migration`) |
| `frontend/` | React + Vite + TypeScript |
| `experiments/` | 1주차 실험 코드(PDF 추출, 정정 확인) |

## 로컬 실행

Docker만 있으면 된다.

```bash
./dev.sh up      # DB + 백엔드(샘플 공고 포함) + Nginx(React)를 빌드해서 실행
./dev.sh logs    # 로그 (./dev.sh logs backend)
./dev.sh down    # 중지, DB 데이터 삭제
```

| 주소 | 내용 |
|---|---|
| <http://localhost:8000> | 화면 |
| <http://localhost:8080/api/notices> | API (내 컴퓨터에서만 접속됨) |
| `localhost:55432` | PostgreSQL. 사용자·DB `cheongdam`, 비밀번호 `dev` |

- 백엔드가 `dev` 프로파일로 뜨면서 시작할 때마다 번호가 `DEV`로 시작하는 샘플 공고 8건을 다시 넣는다(`backend/src/main/resources/db/seed/dev-seed.sql`). 날짜는 실행한 날 기준이다. 정정·추출 결과가 있는 `/notices/DEV001`, 무순위 `/notices/DEV004`, 마감 `/notices/DEV006` 등을 볼 수 있다.
- 코드를 고친 뒤에는 `./dev.sh up`을 다시 실행하면 이미지를 다시 빌드한다. 화면을 계속 고칠 때는 아래 직접 실행이 빠르다.

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
`dev` 프로파일은 시작할 때 `DEV%` 공고를 지우고 샘플을 넣으므로 운영 DB에서 켜지 않는다.

```bash
cp .env.example .env        # DB_PASSWORD를 바꾼다
docker compose up -d --build
```
