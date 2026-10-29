import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Config:
    api_key: str
    db_dsn: str
    pdf_dir: Path
    window_days: int = 60  # P-11: 모집공고일이 이 기간 안이거나 계약 종료일이 지나지 않은 공고를 다시 조회한다
    request_interval: float = 1.0  # P-12: 청약홈 요청은 초당 1건 이하

    @staticmethod
    def from_env() -> "Config":
        dsn = os.environ.get("DB_DSN") or (
            f"host={os.environ.get('DB_HOST', 'db')} port={os.environ.get('DB_PORT', '5432')} "
            f"dbname={os.environ.get('DB_NAME', 'cheongdam')} user={os.environ.get('DB_USER', 'cheongdam')} "
            f"password={os.environ['DB_PASSWORD']}"
        )
        api_key = os.environ.get("DATA_GO_KR_KEY")
        if not api_key:
            raise SystemExit("DATA_GO_KR_KEY(공공데이터포털 Decoding 인증키)가 설정되지 않았습니다")
        return Config(
            api_key=api_key,
            db_dsn=dsn,
            pdf_dir=Path(os.environ.get("PDF_DIR", "/data/pdfs")),
        )
