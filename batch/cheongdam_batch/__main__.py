"""사용법: python -m cheongdam_batch [--once] [--extract]
기본은 수집 스케줄러(매일 06:00, 18:00 KST, P-10). --once는 한 번만 수집하고 끝낸다.
--extract는 수집 대신 추출 대기열을 처리한다(ANTHROPIC_API_KEY 필요). 재시도가 1시간·6시간 뒤라서 EXTRACT_POLL_SECONDS마다 대기열을 본다."""

import logging
import os
import sys
import time
from datetime import datetime, timedelta

import psycopg

from . import collect, extract
from .collect import KST
from .config import Config

SCHEDULE_HOURS = (6, 18)
EXTRACT_POLL_SECONDS = int(os.environ.get("EXTRACT_POLL_SECONDS", "300"))


def next_run(now: datetime) -> datetime:
    today = [now.replace(hour=h, minute=0, second=0, microsecond=0) for h in SCHEDULE_HOURS]
    later = [t for t in today if t > now]
    return later[0] if later else today[0] + timedelta(days=1)


def run_once(cfg: Config) -> None:
    with psycopg.connect(cfg.db_dsn) as conn:
        collect.run(cfg, conn)


def extract_once(cfg: Config) -> None:
    with psycopg.connect(cfg.db_dsn) as conn:
        extract.run_queue(cfg, conn)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    if "--extract" in sys.argv:
        if not os.environ.get("ANTHROPIC_API_KEY"):
            raise SystemExit("ANTHROPIC_API_KEY가 설정되지 않았습니다")
        cfg = Config.from_env(need_data_key=False)
        while True:
            try:
                extract_once(cfg)
            except Exception:  # DB 접속 실패 등으로 죽지 않고 다음 주기에 다시 시도한다
                logging.exception("추출 실행 실패")
            if "--once" in sys.argv:
                return
            time.sleep(EXTRACT_POLL_SECONDS)
    cfg = Config.from_env()
    if "--once" in sys.argv:
        run_once(cfg)
        return
    while True:
        target = next_run(datetime.now(KST))
        logging.info("다음 수집 %s", target.isoformat())
        time.sleep(max(0, (target - datetime.now(KST)).total_seconds()))
        try:
            run_once(cfg)
        except Exception:  # DB 접속 실패 등으로 죽지 않고 다음 시각에 다시 시도한다
            logging.exception("수집 실행 실패")


if __name__ == "__main__":
    main()
