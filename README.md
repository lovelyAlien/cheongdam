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
3. `docker save | gzip | ssh … docker load`로 서버에 올린다.
4. 서버의 `/root/cheongdam-prod`에서 `docker compose -p cheongdam up -d` 후 안 쓰는 이미지를 지운다.
5. `/api/notices`가 2분 반 안에 응답하지 않으면 실패로 끝내고 백엔드 로그를 남긴다.

진행 상황은 GitHub의 Actions 탭 → Deploy에서 본다. 배포는 한 번에 하나만 돌고, 진행 중인 배포는 취소되지 않는다.

**처음 한 번 설정**: 저장소 Settings → Environments에 `production`을 만들고 아래 시크릿을 넣는다. 승인자(Required reviewers)를 지정하면 승인 뒤에 배포가 시작된다.

| 시크릿 | 값 |
|---|---|
| `SSH_HOST` | 운영 서버 공인 IP |
| `SSH_PRIVATE_KEY` | 서버 접속용 개인 키(`.pem`) 내용 |
| `SSH_KNOWN_HOSTS` | `grep '^<서버 IP> ' ~/.ssh/known_hosts` 출력 |
| `VITE_KAKAO_JS_KEY` | 카카오 JavaScript 키(웹 번들에 들어간다) |

서버에는 `/root/cheongdam-prod`에 `cheongdam-*:prod` 이미지를 쓰는 compose 파일과 `.env`가 미리 있어야 한다.

**되돌리기**: 자동 롤백은 없다. 같은 `:prod` 태그를 덮어쓰므로, 되돌릴 커밋에 새 태그를 붙여 다시 배포한다.

```bash
git tag v1.2.1 <되돌릴 커밋>
git push origin v1.2.1
```
