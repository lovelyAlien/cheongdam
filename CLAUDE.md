# 청담 작업 규칙

## Git

- **기능 구현(코드 변경)은 별도 git worktree와 기능 브랜치에서 한다.** 기존 체크아웃에서 브랜치를 바꿔 가며 작업하지 않는다.
  - 새 기능을 시작하면 먼저 worktree와 브랜치를 만든다(`git worktree add <경로> -b <type>/<이름> origin/main`).
  - worktree에는 `.env`와 `node_modules`가 없다. 필요하면 복사하거나 설치한다.
  - 다른 체크아웃에서 띄운 개발 서버가 worktree가 아닌 폴더를 보고 있지 않은지 확인한다.
  - 끝나면 PR로 올리고, 병합 뒤 `git worktree remove`로 정리한다.
- 코드는 기능 브랜치 + PR 리뷰 후 main에 합친다. **문서(`docs/`)만 바꿀 때는 main에 바로 커밋한다**(D-22, [서비스 기획서](docs/SERVICE_SPEC.md)).
- 푸시와 PR 생성은 요청받았을 때만 한다.

## 개발 환경

- 로컬 실행은 `./dev.sh up`(DB + 백엔드 + 웹), 수집·추출·좌표 변환은 `./dev.sh batch` `extract` `geocode`.
- 키는 루트 `.env`에 둔다(`.env.example` 참고). `KAKAO_REST_KEY`는 서버용, `VITE_KAKAO_JS_KEY`는 빌드 때 번들에 들어간다.
- 프론트만 띄울 때는 `cd frontend && npm run dev`(루트 `.env`를 읽는다).
