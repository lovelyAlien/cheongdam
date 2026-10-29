#!/usr/bin/env bash
# 로컬 개발 환경을 한 번에 띄운다: DB + 백엔드(샘플 데이터) + Nginx(React)
#   ./dev.sh up       빌드하고 백그라운드로 실행 → http://localhost:8000
#   ./dev.sh logs     로그 보기 (예: ./dev.sh logs backend)
#   ./dev.sh batch    수집 배치를 한 번 실행(.env의 DATA_GO_KR_KEY 필요, DEV 샘플 위에 실제 공고가 쌓인다)
#   ./dev.sh down     중지하고 DB 데이터도 삭제(샘플은 다음 실행에서 다시 들어간다)
set -euo pipefail
cd "$(dirname "$0")"

compose() {
  docker compose -p cheongdam-dev --env-file .env.dev -f docker-compose.yml -f docker-compose.dev.yml "$@"
}

case "${1:-}" in
  up)
    compose up -d --build
    echo
    echo "화면: http://localhost:8000   API: http://localhost:8080/api/notices   DB: localhost:55432"
    ;;
  logs)
    shift
    compose logs -f "$@"
    ;;
  batch)
    compose --profile batch run --rm -e DATA_GO_KR_KEY="$(grep '^DATA_GO_KR_KEY=' .env | cut -d= -f2-)" batch python -m cheongdam_batch --once
    ;;
  down)
    compose down -v
    ;;
  *)
    echo "사용법: ./dev.sh up | logs [서비스] | batch | down" >&2
    exit 1
    ;;
esac
