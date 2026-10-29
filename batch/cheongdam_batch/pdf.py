"""공고문 PDF 받기: 상세 페이지(HTML)에서 '모집공고문 보기' 링크를 찾아 내려받는다."""

import hashlib
import re
import urllib.parse
from dataclasses import dataclass
from typing import Callable

from . import http

PDF_LINK = re.compile(r'href="(https://static\.applyhome\.co\.kr/ai/aia/getAtchmnfl\.do\?[^"]+)"[^>]*>\s*모집공고문 보기')

Get = Callable[[str], tuple[bytes, dict[str, str]]]


@dataclass
class PdfResult:
    status: str  # notice_version.pdf_status: ok / none / not_pdf / failed
    sha256: str | None = None
    file_name: str | None = None
    content: bytes | None = None


def find_link(html: str) -> str | None:
    links = PDF_LINK.findall(html)
    return links[0].replace("&amp;", "&") if links else None


def file_name(headers: dict[str, str]) -> str | None:
    disposition = headers.get("content-disposition", "")
    if m := re.search(r"filename\*=UTF-8''([^;]+)", disposition, re.I):
        return urllib.parse.unquote(m.group(1))
    if m := re.search(r'filename="?([^";]+)"?', disposition, re.I):
        raw = urllib.parse.unquote(m.group(1), encoding="latin-1")
        try:  # 청약홈은 EUC-KR 파일명을 헤더에 그대로 싣는다
            return raw.encode("latin-1").decode("euc-kr")
        except (UnicodeEncodeError, UnicodeDecodeError):
            return raw
    return None


def fetch(notice_url: str, throttle: http.Throttle, get: Get = http.get) -> PdfResult:
    try:
        throttle.wait()
        html = get(notice_url)[0].decode("utf-8", "replace")
        link = find_link(html)
        if link is None:
            return PdfResult("none")  # E-01
        throttle.wait()
        content, headers = get(link)
    except Exception:
        return PdfResult("failed")
    if not content.startswith(b"%PDF"):
        return PdfResult("not_pdf", file_name=file_name(headers))  # E-03
    return PdfResult("ok", hashlib.sha256(content).hexdigest(), file_name(headers), content)
