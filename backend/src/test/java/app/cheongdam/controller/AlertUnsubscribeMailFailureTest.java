package app.cheongdam.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.cheongdam.mail.AlertMailer;
import app.cheongdam.mail.DigestMail;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 메일 발송이 실패해도 해지는 되돌아가지 않는다(ALERT_DELIVERY 4장).
 * 메일 링크 해지(S-08d)는 확인 단계가 없어, 롤백되면 사용자는 그만 받기를 눌렀는데도 메일을 계속 받는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(AlertUnsubscribeMailFailureTest.TestConfig.class)
class AlertUnsubscribeMailFailureTest {

	@TestConfiguration
	static class TestConfig {
		@Bean
		@Primary
		AlertMailer failingMailer() {
			return new AlertMailer() {
				@Override
				public void confirmation(String email, String confirmToken, Map<String, Object> conditions) {
					throw new IllegalStateException("메일 서버에 못 붙었어요");
				}

				@Override
				public void unsubscribed(String email, List<Map<String, Object>> conditions, boolean emailDeleted) {
					throw new IllegalStateException("메일 서버에 못 붙었어요");
				}

				@Override
				public void digest(String email, DigestMail mail) {
					throw new IllegalStateException("메일 서버에 못 붙었어요");
				}

				@Override
				public void manageLink(String email, String manageToken) {
					throw new IllegalStateException("메일 서버에 못 붙었어요");
				}
			};
		}
	}

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void 해지_메일이_실패해도_해지는_그대로_남는다() throws Exception {
		jdbc.sql("DELETE FROM subscription").update();
		jdbc.sql("""
				INSERT INTO subscription (email, conditions, conditions_hash, status, confirm_token, manage_token,
				    consent_at, confirmed_at)
				VALUES ('z@example.com', '{"v":1}'::jsonb, 'hz', 'active', 'ctz', 'mtz', now(), now())
				""").update();

		mvc.perform(post("/api/alerts/unsubscribe").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"mtz\"}")).andExpect(status().isOk());

		assertEquals("unsubscribed",
				jdbc.sql("SELECT status FROM subscription WHERE manage_token = 'mtz'").query(String.class).single());
		assertEquals(0, jdbc.sql("SELECT count(*) FROM subscription WHERE email IS NOT NULL")
				.query(Integer.class).single()); // 마지막 구독이라 이메일도 지워진다(P-09)
	}
}
