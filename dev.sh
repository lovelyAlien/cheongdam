#!/usr/bin/env bash
# 로컬 개발 환경을 한 번에 띄운다: DB + 백엔드 + Nginx(React)
#   ./dev.sh up       빌드하고 백그라운드로 실행 → http://localhost:8000 (DEV 샘플: APP_DEV_SEED=true ./dev.sh up)
#   ./dev.sh logs     로그 보기 (예: ./dev.sh logs backend)
#   ./dev.sh batch    수집 배치를 한 번 실행(.env의 DATA_GO_KR_KEY 필요, 샘플이 켜져 있으면 그 위에 쌓인다)
#   ./dev.sh alert    알림 매칭·발송을 지금 한 번 실행(스케줄은 07:30 이후라 기다리지 않는다). ./dev.sh alert 0 이면 기준 시각을 안 물린다
#   ./dev.sh stop     중지만 한다. DB 데이터는 유지된다(다시 ./dev.sh up)
#   ./dev.sh down     중지하고 DB 데이터도 삭제
set -euo pipefail
cd "$(dirname "$0")"

compose() {
  # .env.mail이 있으면 뒤에 덧씌운다(메일 계정·앱 비밀번호. 커밋하지 않는다)
  local env_files=(--env-file .env.dev)
  if [ -f .env.mail ]; then
    env_files+=(--env-file .env.mail)
  fi
  docker compose -p cheongdam-dev "${env_files[@]}" -f docker-compose.yml -f docker-compose.dev.yml "$@"
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
  alert)
    # localhost 대신 127.0.0.1: 컨테이너는 IPv4에만 묶여 있어 다른 앱이 ::1:8080을 쥐고 있으면 그쪽으로 간다
    curl -fsS -X POST "http://127.0.0.1:8080/api/dev/alerts/dispatch?rewind_days=${2:-3}"
    echo
    ;;
  stop)
    compose stop
    ;;
  down)
    compose down -v
    ;;
  *)
    echo "사용법: ./dev.sh up | logs [서비스] | batch | alert [되돌릴_일수] | stop | down" >&2
    exit 1
    ;;
esac
