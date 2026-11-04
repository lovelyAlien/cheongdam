import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Config:
    api_key: str
    db_dsn: str
    pdf_dir: Path
    kakao_key: str = ""  # 주소 → 좌표(REST 키). 없으면 좌표 변환을 건너뛴다
    myhome_key: str = ""  # 마이홈포털 인증키. 없으면 공공분양·공공임대 수집을 건너뛴다
    window_days: int = 60  # P-11: 모집공고일이 이 기간 안이거나 계약 종료일이 지나지 않은 공고를 다시 조회한다
    request_interval: float = 1.0  # P-12: 청약홈 요청은 초당 1건 이하

    @staticmethod
    def from_env(need_data_key: bool = True) -> "Config":
        dsn = os.environ.get("DB_DSN") or (
            f"host={os.environ.get('DB_HOST', 'db')} port={os.environ.get('DB_PORT', '5432')} "
            f"dbname={os.environ.get('DB_NAME', 'cheongdam')} user={os.environ.get('DB_USER', 'cheongdam')} "
            f"password={os.environ['DB_PASSWORD']}"
        )
        api_key = os.environ.get("DATA_GO_KR_KEY", "")
        if need_data_key and not api_key:
            raise SystemExit("DATA_GO_KR_KEY(공공데이터포털 Decoding 인증키)가 설정되지 않았습니다")
        return Config(
            api_key=api_key,
            db_dsn=dsn,
            kakao_key=os.environ.get("KAKAO_REST_KEY", ""),
            myhome_key=os.environ.get("MYHOME_API_KEY", ""),
            pdf_dir=Path(os.environ.get("PDF_DIR", "/data/pdfs")),
        )
