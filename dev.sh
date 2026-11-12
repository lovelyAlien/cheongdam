#!/usr/bin/env bash
# 로컬 개발 환경을 한 번에 띄운다: DB + 백엔드 + Nginx(React)
#   ./dev.sh up       빌드하고 백그라운드로 실행 → http://localhost:8000 (DEV 샘플: APP_DEV_SEED=true ./dev.sh up)
#   ./dev.sh logs     로그 보기 (예: ./dev.sh logs backend)
#   ./dev.sh batch    수집 배치를 한 번 실행(.env의 DATA_GO_KR_KEY 필요, 샘플이 켜져 있으면 그 위에 쌓인다)
#   ./dev.sh geocode  좌표 없는 공고를 한 번 변환(.env의 KAKAO_REST_KEY 필요, 기존 공고 백필용)
#   ./dev.sh extract  추출 대기열을 한 번 처리(.env의 ANTHROPIC_API_KEY 필요, Claude 호출 비용이 든다)
#   ./dev.sh alert    알림 매칭·발송을 지금 한 번 실행(스케줄은 07:30 이후라 기다리지 않는다). ./dev.sh alert 0 이면 기준 시각을 안 물린다
#   ./dev.sh stop     중지만 한다. DB 데이터는 유지된다(다시 ./dev.sh up)
#   ./dev.sh down     중지하고 DB 데이터도 삭제
set -euo pipefail
cd "$(dirname "$0")"

# 루트 .env(키·메일 계정 같은 비밀)와 .env.dev(개발 값)를 합쳐 환경변수로 올린다.
# 나중에 읽은 파일이 이긴다. compose는 셸 환경변수를 --env-file보다 먼저 쓰므로 ${VAR}가 여기서 채워진다
# (VITE_KAKAO_JS_KEY처럼 빌드 때 쓰는 값 포함). docker compose 2.2.x는 --env-file을 두 번 못 받고
# .env 값에 공백이 있을 수 있어서, source 대신 한 줄씩 읽는다.
# 파일 끝에 줄바꿈이 없어도 마지막 줄을 버리지 않는다(|| [[ -n "$key" ]]). 손으로 채우는 .env의
# 마지막 줄이 SPRING_MAIL_PASSWORD 같은 비밀이면, 버리면 SMTP 인증 실패로만 드러난다. 파일 사이도 줄바꿈으로 끊는다.
while IFS='=' read -r key value || [[ -n "$key" ]]; do
  [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] && export "$key=$value"
done < <(for f in .env .env.dev; do
  if [ -f "$f" ]; then cat "$f"; echo; fi
done)

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
    compose --profile batch run --rm batch python -m cheongdam_batch --once
    ;;
  geocode)
    compose --profile batch run --rm batch python -m cheongdam_batch --geocode
    ;;
  extract)
    compose --profile extract run --rm extractor python -m cheongdam_batch --extract --once
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
    echo "사용법: ./dev.sh up | logs [서비스] | batch | geocode | extract | alert [되돌릴_일수] | stop | down" >&2
    exit 1
    ;;
esac
