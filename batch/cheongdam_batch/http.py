import time
import urllib.request

UA = {"User-Agent": "Mozilla/5.0"}


class Throttle:
    """P-12: 청약홈 요청 사이에 최소 간격을 둔다."""

    def __init__(self, interval: float):
        self.interval = interval
        self._last = 0.0

    def wait(self) -> None:
        delay = self._last + self.interval - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        self._last = time.monotonic()


def get(url: str, timeout: int = 60) -> tuple[bytes, dict[str, str]]:
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as res:
        return res.read(), {k.lower(): v for k, v in res.headers.items()}
