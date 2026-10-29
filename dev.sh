#!/usr/bin/env bash
# 로컬 개발 환경을 한 번에 띄운다: DB + 백엔드 + Nginx(React)
#   ./dev.sh up       빌드하고 백그라운드로 실행 → http://localhost:8000 (DEV 샘플: APP_DEV_SEED=true ./dev.sh up)
#   ./dev.sh logs     로그 보기 (예: ./dev.sh logs backend)
#   ./dev.sh batch    수집 배치를 한 번 실행(.env의 DATA_GO_KR_KEY 필요, 샘플이 켜져 있으면 그 위에 쌓인다)
#   ./dev.sh extract  추출 대기열을 한 번 처리(.env의 ANTHROPIC_API_KEY 필요, Claude 호출 비용이 든다)
#   ./dev.sh stop     중지만 한다. DB 데이터는 유지된다(다시 ./dev.sh up)
#   ./dev.sh down     중지하고 DB 데이터도 삭제
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
  extract)
    compose --profile extract run --rm -e ANTHROPIC_API_KEY="$(grep '^ANTHROPIC_API_KEY=' .env | cut -d= -f2-)" extractor python -m cheongdam_batch --extract --once
    ;;
  stop)
    compose stop
    ;;
  down)
    compose down -v
    ;;
  *)
    echo "사용법: ./dev.sh up | logs [서비스] | batch | extract | stop | down" >&2
    exit 1
    ;;
esac
