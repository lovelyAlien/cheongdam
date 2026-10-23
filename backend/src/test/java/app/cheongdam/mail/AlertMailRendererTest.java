package app.cheongdam.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cheongdam.mail.AlertMailRenderer.RenderedMail;
import app.cheongdam.mail.DigestMail.Change;
import app.cheongdam.mail.DigestMail.CorrectionEntry;
import app.cheongdam.mail.DigestMail.NewEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 메일 제목·본문·링크(ALERTS.md 2장). 오늘은 2026-10-22(목)로 고정한다. */
class AlertMailRendererTest {

	static final String BASE = "https://cheongdam.app";
	static final Map<String, Object> CONDITIONS = Map.of("v", 1, "region", List.of("서울", "경기"), "supply",
			List.of("newlywed"), "price_max", 50000);

	final AlertMailRenderer renderer = new AlertMailRenderer(BASE + "/",
			Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("Asia/Seoul")));

	static NewEntry notice(String no, String name) {
		return new NewEntry(no, name, "general", "private", "경기", "경기도 수원시 장안구 ○○동 1", "upcoming",
				LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 27), "special", 32100,
				52340, 59.97, 84.89);
	}

	static DigestMail digest(List<CorrectionEntry> corrections, List<NewEntry> news, int more) {
		return new DigestMail("[청담] 제목", CONDITIONS, "서울·경기 신혼부부 5억 이하", corrections, news, more, "MT");
	}

	@Test
	void 새_공고_카드에_상태_위치_숫자_첫_접수와_두_버튼이_들어간다() {
		RenderedMail m = renderer.digest(digest(List.of(), List.of(notice("A1", "○○자이 더 센트럴")), 0));

		assertEquals("[청담] 제목", m.subject());
		assertTrue(m.html().contains("[접수 예정]"));
		assertTrue(m.html().contains("○○자이 더 센트럴"));
		assertTrue(m.text().contains("경기 수원시 장안구 · 3.2억~5.2억 · 59~84㎡"));
		assertTrue(m.text().contains("첫 접수 10.27(화) 특별공급"));
		assertTrue(m.text().contains("상세 보기: https://cheongdam.app/notices/A1?mail=m02&link=detail"));
		assertTrue(m.text().contains("https://cheongdam.app/api/notices/A1/calendar.ics?mail=m02&link=calendar"));
		assertTrue(m.html().contains("?mail=m02&amp;link=detail")); // href 안의 &는 이스케이프
	}

	@Test
	void 모든_링크_메일은_하단에_면책_출처_설정_해지가_있고_원클릭_해지_주소를_준다() {
		RenderedMail m = renderer.digest(digest(List.of(), List.of(notice("A1", "단지")), 0));

		assertTrue(m.text().contains(AlertMailRenderer.DISCLAIMER));
		assertTrue(m.text().contains("데이터 출처: 청약홈(한국부동산원)"));
		assertTrue(m.text().contains("알림 설정: https://cheongdam.app/alerts/manage?token=MT&mail=m02&link=manage"));
		assertTrue(m.text().contains("알림 그만 받기: https://cheongdam.app/alerts/unsubscribe?token=MT&mail=m02&link=unsubscribe"));
		assertEquals("https://cheongdam.app/api/alerts/unsubscribe?token=MT", m.listUnsubscribeUrl());
	}

	@Test
	void 열_건을_넘으면_더_보기가_조건이_담긴_목록으로_간다() {
		RenderedMail m = renderer.digest(digest(List.of(), List.of(notice("A1", "단지")), 5));

		assertTrue(m.text().contains("5건 더 보기: https://cheongdam.app/?region=%EC%84%9C%EC%9A%B8%2C%EA%B2%BD%EA%B8%B0"
				+ "&supply=newlywed&price_max=50000&mail=m02&link=more"));
	}

	@Test
	void 정정은_바뀐_항목을_보여_주고_일정이_바뀌었을_때만_캘린더를_다시_추가하게_한다() {
		CorrectionEntry schedule = new CorrectionEntry("C1", "정정 단지",
				List.of(new Change("1순위 접수일", "2026-10-21", "2026-10-23")));
		RenderedMail m = renderer.digest(digest(List.of(schedule), List.of(), 0));
		assertTrue(m.text().contains("1순위 접수일: 2026-10-21 → 2026-10-23"));
		assertTrue(m.text().contains("캘린더에 추가한 일정이 있다면 다시 추가해 주세요."));
		assertTrue(m.text().contains("calendar.ics?mail=m03&link=calendar"));

		CorrectionEntry price = new CorrectionEntry("C2", "분양가만", List.of(new Change("분양가", null, "5억")));
		RenderedMail p = renderer.digest(digest(List.of(price), List.of(), 0));
		assertTrue(p.text().contains("분양가: 없음 → 5억"));
		assertFalse(p.text().contains("캘린더"));

		RenderedMail empty = renderer.digest(digest(List.of(new CorrectionEntry("C3", "안내 상자 없음", List.of())), List.of(), 0));
		assertTrue(empty.text().contains("공고문이 새로 올라왔어요."));
		assertFalse(empty.text().contains("캘린더"));
	}

	@Test
	void 정정과_새_공고는_한_통에_정정이_위에_온다() {
		RenderedMail m = renderer.digest(digest(List.of(new CorrectionEntry("C1", "정정 단지", List.of())),
				List.of(notice("A1", "새 단지")), 0));

		assertTrue(m.text().indexOf("정정 단지") < m.text().indexOf("새 단지"));
		assertTrue(m.text().contains("mail=m02m03"));
	}

	@Test
	void 공고_이름의_HTML은_이스케이프한다() {
		RenderedMail m = renderer.digest(digest(List.of(), List.of(notice("A1", "<script>alert(1)</script>")), 0));

		assertFalse(m.html().contains("<script>alert"));
		assertTrue(m.html().contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
	}

	@Test
	void 구독_확인_메일은_확인_링크와_안내만_있고_설정_해지_링크는_없다() {
		RenderedMail m = renderer.confirmation("CT", CONDITIONS);

		assertEquals("[청담] 알림을 시작하려면 확인해 주세요", m.subject());
		assertTrue(m.text().contains("서울·경기 신혼부부 5억 이하"));
		assertTrue(m.text().contains("알림 시작하기: https://cheongdam.app/alerts/confirm?token=CT&mail=m01&link=confirm"));
		assertTrue(m.text().contains("확인하지 않으면 7일 뒤 자동으로 지워져요."));
		assertFalse(m.text().contains("알림 그만 받기"));
		assertNull(m.listUnsubscribeUrl());
	}

	@Test
	void 해지_메일은_다시_구독하기가_조건을_담고_이메일을_지웠을_때만_안내한다() {
		RenderedMail keep = renderer.unsubscribed(List.of(CONDITIONS), false);
		assertEquals("[청담] 알림을 해지했어요", keep.subject());
		assertTrue(keep.text().contains("다시 구독하기: https://cheongdam.app/?region=%EC%84%9C%EC%9A%B8%2C%EA%B2%BD%EA%B8%B0"
				+ "&supply=newlywed&price_max=50000&subscribe=1&mail=m04&link=resubscribe"));
		assertFalse(keep.text().contains("이메일 주소는 바로 지웠어요."));

		assertTrue(renderer.unsubscribed(List.of(CONDITIONS), true).text().contains("이메일 주소는 바로 지웠어요."));
	}

	@Test
	void 관리_링크_메일() {
		RenderedMail m = renderer.manageLink("MT");

		assertEquals("[청담] 알림 설정 링크예요", m.subject());
		assertTrue(m.text().contains("https://cheongdam.app/alerts/manage?token=MT&mail=manage&link=manage"));
	}

	@Test
	void 분양가와_면적_표기는_화면과_같다() {
		assertEquals("3.2억~5.2억", AlertMailRenderer.price(32100, 52340));
		assertEquals("3억", AlertMailRenderer.price(30000, 30000));
		assertEquals("공고문 확인", AlertMailRenderer.price(null, 52340));
		assertEquals("59~84㎡", AlertMailRenderer.area(59.97, 84.89));
		assertEquals("59㎡", AlertMailRenderer.area(59.1, 59.9));
		assertNull(AlertMailRenderer.area(null, null));
	}
}
