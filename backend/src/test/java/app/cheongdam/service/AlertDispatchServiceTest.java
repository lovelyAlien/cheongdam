package app.cheongdam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cheongdam.mail.AlertMailer;
import app.cheongdam.mail.DigestMail;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 오늘을 2026-10-22(목) 09:00 한국 시간으로 고정하고 알림 매칭·발송(P-17~P-20)을 확인한다. */
@SpringBootTest
@Testcontainers
@Import(AlertDispatchServiceTest.TestConfig.class)
class AlertDispatchServiceTest {

	static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T09:00:00+09:00");
	static final OffsetDateTime CUTOFF = OffsetDateTime.parse("2026-10-22T07:30:00+09:00");

	static class RecordingMailer implements AlertMailer {
		final List<DigestMail> digests = new ArrayList<>();
		int failures;

		@Override
		public void confirmation(String email, String confirmToken, Map<String, Object> conditions) {
		}

		@Override
		public void unsubscribed(String email, List<Map<String, Object>> conditions, boolean emailDeleted) {
		}

		@Override
		public void digest(String email, DigestMail mail) {
			if (failures > 0) {
				failures--;
				throw new IllegalStateException("발송기 오류");
			}
			digests.add(mail);
		}

		@Override
		public void manageLink(String email, String manageToken) {
		}
	}

	@TestConfiguration
	static class TestConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("Asia/Seoul"));
		}

		@Bean
		@Primary
		RecordingMailer recordingMailer() {
			return new RecordingMailer();
		}
	}

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

	@Autowired
	AlertDispatchService service;

	@Autowired
	RecordingMailer mailer;

	@Autowired
	JdbcClient jdbc;

	int seq;

	@BeforeEach
	void clean() {
		mailer.digests.clear();
		mailer.failures = 0;
		jdbc.sql("DELETE FROM alert_log").update();
		jdbc.sql("DELETE FROM subscription").update(); // outbox는 함께 지워진다
		jdbc.sql("DELETE FROM notice_change").update();
		jdbc.sql("DELETE FROM notice").update();
	}

	@Test
	void 새_공고는_조건에_맞고_구독을_확인한_뒤_수집된_것만_보내고_다시_보내지_않는다() {
		long sub = subscription("a@example.com", "{\"v\":1,\"region\":[\"경기\"]}", "active", "2026-10-20T00:00:00+09:00");
		notice("N1", "경기", "2026-10-21T12:00:00+09:00", null, 1, null);
		notice("N2", "서울", "2026-10-21T12:00:00+09:00", null, 1, null); // 조건 밖
		notice("N3", "경기", "2026-10-19T12:00:00+09:00", null, 1, null); // 구독 확인 전에 수집
		notice("N4", "경기", "2026-10-22T08:00:00+09:00", null, 1, null); // 이번 매칭 이후 수집 → 다음 번에

		assertEquals(1, service.enqueue(CUTOFF));
		assertEquals(List.of("N1"), queuedNotices(sub));
		assertEquals("new", jdbc.sql("SELECT mail_type FROM alert_outbox").query(String.class).single());

		assertEquals(1, service.sendDue(NOW));
		DigestMail mail = mailer.digests.get(0);
		assertEquals("[청담] 새 공고 1건 · 경기", mail.subject());
		assertEquals("N1", mail.newNotices().get(0).houseManageNo());
		assertEquals(1, count("alert_log"));
		assertEquals("sent", jdbc.sql("SELECT status FROM alert_outbox").query(String.class).single());
		assertEquals(CUTOFF.toInstant(), jdbc.sql("SELECT last_sent_at FROM subscription WHERE id = :s").param("s", sub)
				.query(OffsetDateTime.class).single().toInstant());

		assertEquals(0, service.enqueue(CUTOFF)); // 하루에 구독당 한 통
		assertEquals(0, service.sendDue(NOW));
	}

	@Test
	void 정정은_접수_상태와_상관없이_계약이_끝나지_않았으면_보내고_새_공고와_겹치면_새_공고로_보낸다() {
		long sub = subscription("b@example.com", "{\"v\":1,\"region\":[\"경기\"]}", "active", "2026-10-20T00:00:00+09:00");
		// C1: 접수는 끝났지만 계약이 남은 공고의 정정. C2: 계약까지 끝난 공고. N5: 새로 수집되고 곧바로 정정됨
		notice("C1", "경기", "2026-10-01T00:00:00+09:00", "2026-10-21T10:00:00+09:00", 2, "2026-11-30");
		notice("C2", "경기", "2026-10-01T00:00:00+09:00", "2026-10-21T10:00:00+09:00", 2, "2026-10-01");
		notice("N5", "경기", "2026-10-21T09:00:00+09:00", "2026-10-21T11:00:00+09:00", 2, null);
		change("C1", 2, "1순위 접수일", "2026-10-21", "2026-10-23");

		assertEquals(1, service.enqueue(CUTOFF));
		assertEquals("combined", jdbc.sql("SELECT mail_type FROM alert_outbox").query(String.class).single());
		assertEquals(List.of("C1:correction", "N5:new"), jdbc.sql(
				"SELECT house_manage_no || ':' || kind FROM alert_outbox_notice ORDER BY house_manage_no")
				.query(String.class).list());

		service.sendDue(NOW);
		DigestMail mail = mailer.digests.get(0);
		assertEquals("[청담] 정정 공고 1건·새 공고 1건", mail.subject());
		assertEquals("1순위 접수일", mail.corrections().get(0).changes().get(0).field());
		assertEquals("2026-10-23", mail.corrections().get(0).changes().get(0).after());
		assertEquals(sub, jdbc.sql("SELECT subscription_id FROM alert_log LIMIT 1").query(Long.class).single());
	}

	@Test
	void 정정만_있으면_정정_메일_제목에_단지명을_쓴다() {
		subscription("c@example.com", "{\"v\":1}", "active", "2026-10-20T00:00:00+09:00");
		notice("C1", "경기", "2026-10-01T00:00:00+09:00", "2026-10-21T10:00:00+09:00", 2, null);
		service.enqueue(CUTOFF);
		service.sendDue(NOW);
		assertEquals("[청담] 정정 공고 1건 · 단지 C1", mailer.digests.get(0).subject());
	}

	@Test
	void 새_공고가_열_건을_넘으면_열_건만_싣고_나머지는_건수로_알린다() {
		subscription("d@example.com", "{\"v\":1}", "active", "2026-10-20T00:00:00+09:00");
		for (int i = 0; i < 12; i++) {
			notice("M" + (10 + i), "경기", "2026-10-21T12:00:00+09:00", null, 1, null);
		}
		service.enqueue(CUTOFF);
		service.sendDue(NOW);
		DigestMail mail = mailer.digests.get(0);
		assertEquals(10, mail.newNotices().size());
		assertEquals(2, mail.moreNewCount());
		assertEquals(12, count("alert_log")); // 더 보기로 알린 것도 다시 보내지 않는다
	}

	@Test
	void 일시_실패는_한_시간_뒤_한_번_다시_보내고_또_실패하면_포기한다() {
		subscription("e@example.com", "{\"v\":1}", "active", "2026-10-20T00:00:00+09:00");
		notice("N1", "경기", "2026-10-21T12:00:00+09:00", null, 1, null);
		service.enqueue(CUTOFF);

		mailer.failures = 1;
		assertEquals(0, service.sendDue(NOW));
		assertEquals(1, jdbc.sql("SELECT attempts FROM alert_outbox").query(Integer.class).single());
		assertEquals(0, count("alert_log"));
		assertEquals(0, service.sendDue(NOW.plusMinutes(30))); // 아직 때가 아님
		assertTrue(mailer.digests.isEmpty());
		assertEquals(1, service.sendDue(NOW.plusHours(1)));
		assertEquals(2, jdbc.sql("SELECT attempts FROM alert_outbox").query(Integer.class).single());

		jdbc.sql("DELETE FROM alert_log").update();
		jdbc.sql("DELETE FROM alert_outbox").update();
		jdbc.sql("UPDATE subscription SET last_sent_at = NULL").update();
		service.enqueue(CUTOFF);
		mailer.failures = 2;
		service.sendDue(NOW);
		service.sendDue(NOW.plusHours(1));
		assertEquals("failed", jdbc.sql("SELECT status FROM alert_outbox").query(String.class).single());
		assertEquals(0, count("alert_log")); // 포기해도 보냈다고 기록하지 않는다
	}

	@Test
	void 구독_중이_아닌_구독은_쌓지_않고_쌓은_뒤_해지되면_보내지_않는다() {
		long pending = subscription("f@example.com", "{\"v\":1}", "pending", null);
		long active = subscription("g@example.com", "{\"v\":1}", "active", "2026-10-20T00:00:00+09:00");
		notice("N1", "경기", "2026-10-21T12:00:00+09:00", null, 1, null);

		assertEquals(1, service.enqueue(CUTOFF));
		assertEquals(0, queuedNotices(pending).size());
		jdbc.sql("UPDATE subscription SET status = 'unsubscribed', email = NULL WHERE id = :s").param("s", active).update();

		assertEquals(0, service.sendDue(NOW));
		assertTrue(mailer.digests.isEmpty());
		assertEquals("failed", jdbc.sql("SELECT status FROM alert_outbox").query(String.class).single());
	}

	// ---- 도우미 ----

	private long subscription(String email, String conditions, String status, String confirmedAt) {
		int n = ++seq;
		return jdbc.sql("""
				INSERT INTO subscription (email, conditions, conditions_hash, status, confirm_token, manage_token,
				    consent_at, confirmed_at)
				VALUES (:e, :c::jsonb, :h, :st, :ct, :mt, now(), :ca::timestamptz) RETURNING id
				""").param("e", email).param("c", conditions).param("h", "h" + n).param("st", status)
				.param("ct", "ct" + n).param("mt", "mt" + n).param("ca", confirmedAt).query(Long.class).single();
	}

	private void notice(String no, String region, String collectedAt, String changedAt, int version, String contractEnd) {
		jdbc.sql("""
				INSERT INTO notice (house_manage_no, notice_type, source_api, name, region, address, house_kind,
				    announced_on, rcept_start, rcept_end, contract_end, notice_url, raw, raw_hash, current_version,
				    first_collected_at, last_checked_at, last_changed_at)
				VALUES (:no, 'general', 'apt', :name, :region, :region, 'private', '2026-10-20', '2026-10-23',
				    '2026-10-26', :contract::date, 'https://x', '{}'::jsonb, 'h', :v, :fc::timestamptz, now(),
				    :lc::timestamptz)
				""").param("no", no).param("name", "단지 " + no).param("region", region).param("v", version)
				.param("fc", collectedAt).param("lc", changedAt).param("contract", contractEnd).update();
		for (int v = 1; v <= version; v++) {
			jdbc.sql("INSERT INTO notice_version (house_manage_no, version, detected_at, api_hash) "
					+ "VALUES (:no, :v, now(), 'h')").param("no", no).param("v", v).update();
		}
	}

	private void change(String no, int version, String field, String before, String after) {
		jdbc.sql("INSERT INTO notice_change (house_manage_no, version, field, before_value, after_value, source) "
				+ "VALUES (:no, :v, :f, :b, :a, 'api_diff')").param("no", no).param("v", version).param("f", field)
				.param("b", before).param("a", after).update();
	}

	private List<String> queuedNotices(long subscriptionId) {
		return jdbc.sql("""
				SELECT n.house_manage_no FROM alert_outbox_notice n JOIN alert_outbox o ON o.id = n.outbox_id
				WHERE o.subscription_id = :s ORDER BY 1
				""").param("s", subscriptionId).query(String.class).list();
	}

	private int count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
	}
}
