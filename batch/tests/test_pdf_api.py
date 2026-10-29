import json
import urllib.parse

from cheongdam_batch import api, http, pdf

HTML = ('<a href="https://static.applyhome.co.kr/ai/aia/getAtchmnfl.do?houseManageNo=1&amp;pblancNo=1'
        '&amp;atchmnflSeqNo=9&amp;atchmnflSn=1" class="x">\n 모집공고문 보기</a>')
NO_WAIT = http.Throttle(0)


def test_find_link_unescapes_amp():
    assert pdf.find_link(HTML).endswith("houseManageNo=1&pblancNo=1&atchmnflSeqNo=9&atchmnflSn=1")
    assert pdf.find_link("<html>LH청약플러스</html>") is None


def fake_get(content, headers=None):
    def get(url):
        return (HTML.encode(), {}) if "selectAPT" in url else (content, headers or {})
    return get


def test_fetch_ok_hashes_pdf():
    r = pdf.fetch("https://x/selectAPTLttot", NO_WAIT, fake_get(b"%PDF-1.4 body"))
    assert r.status == "ok" and len(r.sha256) == 64 and r.content.startswith(b"%PDF")


def test_fetch_not_pdf_and_none_and_failed():
    assert pdf.fetch("https://x/selectAPT", NO_WAIT, fake_get(b"HWP")).status == "not_pdf"
    assert pdf.fetch("https://x/selectAPT", NO_WAIT, lambda u: (b"no link", {})).status == "none"

    def boom(url):
        raise OSError("timeout")
    assert pdf.fetch("https://x/selectAPT", NO_WAIT, boom).status == "failed"


def test_file_name_utf8_and_euckr():
    assert pdf.file_name({"content-disposition": "attachment; filename*=UTF-8''%EA%B3%B5%EA%B3%A0.pdf"}) == "공고.pdf"
    euckr = urllib.parse.quote("(정정)공고.pdf".encode("euc-kr"))
    assert pdf.file_name({"content-disposition": f'attachment; filename="{euckr}"'}) == "(정정)공고.pdf"


def test_fetch_notices_merges_queries_and_paginates(monkeypatch):
    monkeypatch.setattr(api, "PER_PAGE", 2)
    pages = {
        "RCRIT_PBLANC_DE": [[{"HOUSE_MANAGE_NO": "1"}, {"HOUSE_MANAGE_NO": "2"}], [{"HOUSE_MANAGE_NO": "3"}]],
        "CNTRCT_CNCLS_ENDDE": [[{"HOUSE_MANAGE_NO": "3"}, {"HOUSE_MANAGE_NO": "4"}]],
    }
    calls = []

    def fetch(url):
        calls.append(url)
        kind = "RCRIT_PBLANC_DE" if "RCRIT_PBLANC_DE" in url else "CNTRCT_CNCLS_ENDDE"
        page = int(urllib.parse.parse_qs(urllib.parse.urlparse(url).query)["page"][0])
        total = sum(len(p) for p in pages[kind])
        return json.dumps({"data": pages[kind][page - 1], "matchCount": total}).encode()

    from datetime import date
    rows = api.fetch_notices("k", "apt", date(2026, 10, 29), 60, fetch)
    assert sorted(r["HOUSE_MANAGE_NO"] for r in rows) == ["1", "2", "3", "4"]
    assert len(calls) == 3
    assert "cond%5BRCRIT_PBLANC_DE%3A%3AGTE%5D=2026-08-30" in calls[0]
