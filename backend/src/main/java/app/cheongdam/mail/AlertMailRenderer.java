package app.cheongdam.mail;

import app.cheongdam.mail.DigestMail.Change;
import app.cheongdam.mail.DigestMail.CorrectionEntry;
import app.cheongdam.mail.DigestMail.NewEntry;
import app.cheongdam.util.ConditionsSummary;
import app.cheongdam.util.District;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 구독 메일(M-01~M-04, 관리 링크)의 제목·HTML·텍스트(ALERTS.md 2장). 발송기는 이 결과를 그대로 보낸다.
 * 메일 클라이언트 호환을 위해 표와 인라인 스타일만 쓰고, 링크에는 메일·링크 종류 추적 파라미터를 붙인다(EV-12).
 * 이메일 주소는 어떤 링크에도 넣지 않는다.
 */
@Component
public class AlertMailRenderer {

	public record RenderedMail(String subject, String html, String text,
			/** `List-Unsubscribe` 헤더에 넣을 원클릭 해지 주소(RFC 8058). 해지 링크가 없는 메일은 null */
			String listUnsubscribeUrl) {
	}

	static final String DISCLAIMER = "청담은 청약홈(한국부동산원)의 공개 데이터와 공고문을 바탕으로 정보를 제공하며, 정보의 정확성을 보증하지 않습니다.";
	/** 정정 항목 중 캘린더 일정에 영향을 주는 것을 알아보는 이름(항목 이름이 표시용 문자열이라 단어로 본다) */
	private static final List<String> SCHEDULE_WORDS = List.of("접수", "일정", "당첨", "발표", "계약");
	private static final Map<String, String> STAGE = Map.of("special", "특별공급", "rank1", "1순위", "rank2", "2순위",
			"no_rank", "무순위 접수", "general", "일반공급");

	private final String baseUrl;
	private final Clock clock;

	AlertMailRenderer(@Value("${app.base-url}") String baseUrl, Clock clock) {
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.clock = clock;
	}

	/** M-01 구독 확인 */
	public RenderedMail confirmation(String confirmToken, Map<String, Object> conditions) {
		Doc d = new Doc();
		d.heading("알림을 시작하려면 확인해 주세요");
		d.para("이 조건으로 새 공고와 정정을 알려 드릴게요.");
		d.strong(ConditionsSummary.of(conditions));
		d.button("알림 시작하기", url("/alerts/confirm?token=" + confirmToken, "m01", "confirm"));
		d.muted("내가 신청하지 않았다면 이 메일을 무시하세요. 확인하지 않으면 7일 뒤 자동으로 지워져요.");
		return d.finish("[청담] 알림을 시작하려면 확인해 주세요", null, null);
	}

	/** M-02 새 공고, M-03 정정, 합본(P-19). 제목은 {@link DigestMail#subject()}를 쓴다. */
	public RenderedMail digest(DigestMail m) {
		boolean hasNew = !m.newNotices().isEmpty();
		String code = m.corrections().isEmpty() ? "m02" : hasNew ? "m02m03" : "m03";
		Doc d = new Doc();
		if (!m.corrections().isEmpty()) {
			d.heading(m.corrections().size() == 1 ? "정정된 공고가 있어요" : "정정된 공고가 " + m.corrections().size() + "건 있어요");
			m.corrections().forEach(c -> correctionCard(d, c, code));
		}
		if (hasNew) {
			d.heading("새 공고가 " + (m.newNotices().size() + m.moreNewCount()) + "건 있어요");
			d.muted(m.conditionsSummary());
			m.newNotices().forEach(n -> newCard(d, n, code));
			if (m.moreNewCount() > 0) {
				d.button(m.moreNewCount() + "건 더 보기", url("/?" + query(m.conditions()), code, "more"));
			}
		}
		String manage = url("/alerts/manage?token=" + m.manageToken(), code, "manage");
		String unsubscribe = url("/alerts/unsubscribe?token=" + m.manageToken(), code, "unsubscribe");
		return d.finish(m.subject(), new Link("알림 설정", manage), new Link("알림 그만 받기", unsubscribe),
				baseUrl + "/api/alerts/unsubscribe?token=" + m.manageToken());
	}

	/** M-04 해지 완료 */
	public RenderedMail unsubscribed(List<Map<String, Object>> conditions, boolean emailDeleted) {
		Doc d = new Doc();
		d.heading("알림을 해지했어요");
		conditions.forEach(c -> {
			d.strong(ConditionsSummary.of(c));
			d.button("다시 구독하기", url("/?" + query(c) + "&subscribe=1", "m04", "resubscribe"));
		});
		if (emailDeleted) {
			d.para("이메일 주소는 바로 지웠어요.");
		}
		return d.finish("[청담] 알림을 해지했어요", null, null);
	}

	/** 관리 링크 메일(E-18) */
	public RenderedMail manageLink(String manageToken) {
		Doc d = new Doc();
		d.heading("알림 설정 링크예요");
		d.para("아래 버튼으로 알림 조건을 확인하고 바꾸거나 해지할 수 있어요.");
		d.button("알림 설정 열기", url("/alerts/manage?token=" + manageToken, "manage", "manage"));
		d.muted("내가 요청하지 않았다면 이 메일을 무시하세요.");
		return d.finish("[청담] 알림 설정 링크예요", null, null);
	}

	// ---- 카드 ----

	private void newCard(Doc d, NewEntry n, String code) {
		List<String> badges = new ArrayList<>();
		badges.add(statusLabel(n.status()));
		if (n.noticeType().equals("no_rank")) {
			badges.add("무순위");
		} else if (n.noticeType().equals("resupply")) {
			badges.add("재공급");
		}
		String district = District.of(n.address());
		List<String> facts = new ArrayList<>();
		facts.add(district == null ? n.region() : n.region() + " " + district);
		facts.add(price(n.priceMinMan(), n.priceMaxMan()));
		String area = area(n.areaMin(), n.areaMax());
		if (area != null) {
			facts.add(area);
		}
		String first = n.firstRceptOn() == null ? "접수 일정 확인 필요"
				: "첫 접수 " + date(n.firstRceptOn()) + (n.firstRceptStage() == null ? "" : " " + STAGE.get(n.firstRceptStage()));
		d.card(badges, n.name(), n.houseKind() == null ? null : n.houseKind().equals("private") ? "민영" : "국민",
				List.of(String.join(" · ", facts), first),
				List.of(new Link("상세 보기", url("/notices/" + n.houseManageNo(), code, "detail")),
						new Link("📅 캘린더에 추가", url("/api/notices/" + n.houseManageNo() + "/calendar.ics", code, "calendar"))));
	}

	private void correctionCard(Doc d, CorrectionEntry c, String code) {
		List<String> lines = new ArrayList<>();
		if (c.changes().isEmpty()) {
			lines.add("공고문이 새로 올라왔어요.");
		}
		c.changes().forEach(ch -> lines.add(ch.field() + ": " + orNone(ch.before()) + " → " + orNone(ch.after())));
		List<Link> links = new ArrayList<>();
		links.add(new Link("상세 보기", url("/notices/" + c.houseManageNo(), code, "detail")));
		if (c.changes().stream().anyMatch(AlertMailRenderer::touchesSchedule)) {
			lines.add("캘린더에 추가한 일정이 있다면 다시 추가해 주세요.");
			links.add(new Link("📅 캘린더에 추가", url("/api/notices/" + c.houseManageNo() + "/calendar.ics", code, "calendar")));
		}
		d.card(List.of("정정"), c.name(), null, lines, links);
	}

	private static boolean touchesSchedule(Change ch) {
		return SCHEDULE_WORDS.stream().anyMatch(w -> ch.field() != null && ch.field().contains(w));
	}

	// ---- 표기(P-03). 화면(lib/format.ts)과 같은 규칙 ----

	private static String statusLabel(String status) {
		return switch (status) {
			case "upcoming" -> "접수 예정";
			case "open" -> "접수 중";
			case "closed" -> "마감";
			default -> "일정 확인 필요";
		};
	}

	/** 3.2억~5.2억. 분양가를 모르면 `공고문 확인`. */
	static String price(Integer min, Integer max) {
		if (min == null || max == null) {
			return "공고문 확인";
		}
		String a = eok(min);
		String b = eok(max);
		return a.equals(b) ? a : a + "~" + b;
	}

	private static String eok(int man) {
		double v = Math.round(man / 1000.0) / 10.0;
		return (v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v)) + "억";
	}

	/** 59~84㎡(소수점 버림, 같으면 하나). 면적을 모르면 null. */
	static String area(Double min, Double max) {
		if (min == null || max == null) {
			return null;
		}
		long a = (long) Math.floor(min);
		long b = (long) Math.floor(max);
		return a == b ? a + "㎡" : a + "~" + b + "㎡";
	}

	/** 올해면 연도 생략, 요일 표기: 10.21(수) / 2027.01.05(화) */
	String date(LocalDate d) {
		String md = String.format("%02d.%02d", d.getMonthValue(), d.getDayOfMonth());
		String weekday = d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
		return d.getYear() == LocalDate.now(clock).getYear() ? md + "(" + weekday + ")"
				: d.getYear() + "." + md + "(" + weekday + ")";
	}

	private static String orNone(String v) {
		return v == null || v.isBlank() ? "없음" : v;
	}

	// ---- 링크 ----

	/** 구독 조건을 목록(S-01)의 쿼리로 바꾼다. 화면의 필터 URL과 같은 키·형식이다. */
	static String query(Map<String, Object> c) {
		List<String> parts = new ArrayList<>();
		for (String key : List.of("region", "supply", "price_min", "price_max", "area", "notice_type", "house_kind")) {
			Object v = c.get(key);
			if (v == null) {
				continue;
			}
			String value = v instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList()) : v.toString();
			if (!value.isEmpty()) {
				parts.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
			}
		}
		return String.join("&", parts);
	}

	/** 메일 종류(`mail`)와 링크 종류(`link`)를 붙인다. 이메일 주소는 넣지 않는다. */
	private String url(String path, String mail, String link) {
		return baseUrl + path + (path.contains("?") ? "&" : "?") + "mail=" + mail + "&link=" + link;
	}

	// ---- 문서 조립 ----

	record Link(String label, String url) {
	}

	private static String esc(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
				.replace("'", "&#39;");
	}

	/** HTML과 텍스트를 같은 순서로 쌓는다. */
	private static final class Doc {
		private static final String FONT = "-apple-system,'Apple SD Gothic Neo','Noto Sans KR',Arial,sans-serif";
		private final StringBuilder html = new StringBuilder();
		private final StringBuilder text = new StringBuilder();

		void heading(String s) {
			html.append("<h1 style=\"margin:24px 0 8px;font-size:20px;color:#1c2330\">").append(esc(s)).append("</h1>");
			text.append("\n").append(s).append("\n\n");
		}

		void para(String s) {
			html.append("<p style=\"margin:8px 0;font-size:15px;line-height:1.6;color:#1c2330\">").append(esc(s)).append("</p>");
			text.append(s).append("\n");
		}

		void strong(String s) {
			html.append("<p style=\"margin:8px 0;font-size:15px;font-weight:700;color:#1c2330\">").append(esc(s)).append("</p>");
			text.append(s).append("\n");
		}

		void muted(String s) {
			html.append("<p style=\"margin:8px 0;font-size:13px;line-height:1.5;color:#667085\">").append(esc(s)).append("</p>");
			text.append(s).append("\n");
		}

		void button(String label, String url) {
			html.append("<p style=\"margin:16px 0\">").append(anchor(new Link(label, url), true)).append("</p>");
			text.append(label).append(": ").append(url).append("\n");
		}

		void card(List<String> badges, String title, String kind, List<String> lines, List<Link> links) {
			html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
					+ "style=\"margin:12px 0;border:1px solid #e4e7ec;border-radius:12px\"><tr><td style=\"padding:14px 16px\">");
			html.append("<p style=\"margin:0 0 6px;font-size:12px;font-weight:700;color:#2563eb\">")
					.append(esc(badges.stream().map(b -> "[" + b + "]").reduce((a, b) -> a + " " + b).orElse("")))
					.append("</p>");
			html.append("<p style=\"margin:0 0 6px;font-size:17px;font-weight:700;color:#1c2330\">").append(esc(title));
			if (kind != null) {
				html.append(" <span style=\"font-size:13px;font-weight:400;color:#667085\">").append(esc(kind)).append("</span>");
			}
			html.append("</p>");
			lines.forEach(l -> html.append("<p style=\"margin:2px 0;font-size:14px;line-height:1.5;color:#344054\">")
					.append(esc(l)).append("</p>"));
			html.append("<p style=\"margin:12px 0 0\">");
			links.forEach(l -> html.append(anchor(l, false)).append(" "));
			html.append("</p></td></tr></table>");

			text.append("\n").append(badges.stream().map(b -> "[" + b + "]").reduce((a, b) -> a + " " + b).orElse(""))
					.append(' ').append(title).append(kind == null ? "" : "  " + kind).append("\n");
			lines.forEach(l -> text.append(l).append("\n"));
			links.forEach(l -> text.append(l.label()).append(": ").append(l.url()).append("\n"));
		}

		private static String anchor(Link l, boolean primary) {
			String style = primary
					? "display:inline-block;padding:10px 16px;background:#2563eb;color:#ffffff;border-radius:8px;"
							+ "text-decoration:none;font-size:15px;font-weight:700"
					: "display:inline-block;padding:7px 12px;border:1px solid #d0d5dd;color:#1c2330;border-radius:8px;"
							+ "text-decoration:none;font-size:13px";
			return "<a href=\"" + esc(l.url()) + "\" style=\"" + style + "\">" + esc(l.label()) + "</a>";
		}

		RenderedMail finish(String subject, Link manage, Link unsubscribe) {
			return finish(subject, manage, unsubscribe, null);
		}

		RenderedMail finish(String subject, Link manage, Link unsubscribe, String listUnsubscribeUrl) {
			StringBuilder footer = new StringBuilder();
			footer.append("<p style=\"margin:0 0 8px;font-size:12px;line-height:1.5;color:#667085\">")
					.append(esc(DISCLAIMER)).append("</p>")
					.append("<p style=\"margin:0 0 8px;font-size:12px;color:#667085\">데이터 출처: 청약홈(한국부동산원)</p>");
			text.append("\n--\n").append(DISCLAIMER).append("\n데이터 출처: 청약홈(한국부동산원)\n");
			if (manage != null) {
				footer.append("<p style=\"margin:0;font-size:12px\"><a href=\"").append(esc(manage.url()))
						.append("\" style=\"color:#667085\">").append(esc(manage.label())).append("</a> · <a href=\"")
						.append(esc(unsubscribe.url())).append("\" style=\"color:#667085\">").append(esc(unsubscribe.label()))
						.append("</a></p>");
				text.append(manage.label()).append(": ").append(manage.url()).append("\n").append(unsubscribe.label())
						.append(": ").append(unsubscribe.url()).append("\n");
			}
			String page = "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
					+ "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + esc(subject)
					+ "</title></head><body style=\"margin:0;background:#f6f7f9;font-family:" + FONT + "\">"
					+ "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
					+ "<td align=\"center\" style=\"padding:24px 12px\">"
					+ "<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" "
					+ "style=\"max-width:560px;width:100%;background:#ffffff;border-radius:16px\">"
					+ "<tr><td style=\"padding:8px 24px 0;font-size:15px;font-weight:700;color:#2563eb\">청담</td></tr>"
					+ "<tr><td style=\"padding:0 24px 8px\">" + html + "</td></tr>"
					+ "<tr><td style=\"padding:16px 24px 24px;border-top:1px solid #e4e7ec\">" + footer + "</td></tr>"
					+ "</table></td></tr></table></body></html>";
			return new RenderedMail(subject, page, text.toString().strip() + "\n", listUnsubscribeUrl);
		}
	}
}
