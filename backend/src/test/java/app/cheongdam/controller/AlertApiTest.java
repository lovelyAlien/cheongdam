package app.cheongdam.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 구독 API(API.md 5장): 신청 → 확인 → 관리 → 해지와 개인정보 파기(P-09). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AlertApiTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

	static final AtomicInteger IP = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("DELETE FROM subscription").update();
		jdbc.sql("DELETE FROM notice").update();
	}

	@Test
	void 신청하면_확인_대기이고_확인하면_구독_중이며_다시_확인해도_같다() throws Exception {
		notice("N1", "경기");
		subscribe("a@example.com", "{\"v\":1,\"region\":[\"경기\"]}").andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("confirmation_sent"))
				.andExpect(header().string("Cache-Control", "no-store"));
		org.junit.jupiter.api.Assertions.assertEquals("pending", statusOf("a@example.com"));

		String token = confirmToken("a@example.com");
		confirm(token).andExpect(status().isOk()).andExpect(jsonPath("$.match_count").value(1))
				.andExpect(jsonPath("$.conditions.region[0]").value("경기"));
		org.junit.jupiter.api.Assertions.assertEquals("active", statusOf("a@example.com"));
		confirm(token).andExpect(status().isOk()).andExpect(jsonPath("$.match_count").value(1));
	}

	@Test
	void 동의가_없거나_알림에_쓸_수_없는_조건이면_400() throws Exception {
		post("/api/alerts/subscriptions", "{\"email\":\"b@example.com\",\"consent\":false,\"conditions\":{\"v\":1}}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("consent"));
		subscribe("b@example.com", "{\"v\":1,\"status\":[\"open\"]}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.field").value("status"));
		subscribe("b@example.com", "{\"v\":1,\"supply\":[\"unknown\"]}").andExpect(status().isBadRequest());
		subscribe("not-an-email", "{\"v\":1}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.field").value("email"));
	}

	@Test
	void 같은_조건은_순서가_달라도_중복이고_같은_이메일의_네_번째_요청은_한도에_걸린다() throws Exception {
		String email = "c@example.com";
		subscribe(email, "{\"v\":1,\"region\":[\"서울\",\"경기\"]}").andExpect(status().isAccepted());
		confirm(confirmToken(email));
		subscribe(email, "{\"v\":1,\"region\":[\"경기\",\"서울\"]}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("already_subscribed"));
		subscribe(email, "{\"v\":1,\"region\":[\"부산\"]}").andExpect(status().isAccepted());
		// 중복 시도도 한 번으로 세므로 같은 이메일의 네 번째 요청은 시간당 3회를 넘는다
		subscribe(email, "{\"v\":1,\"region\":[\"대구\"]}").andExpect(status().isTooManyRequests());
	}

	@Test
	void 세_개를_채운_이메일의_네_번째_조건은_subscription_limit() throws Exception {
		String email = "d@example.com";
		for (String r : new String[] { "서울", "경기", "부산" }) {
			jdbc.sql("""
					INSERT INTO subscription (email, conditions, conditions_hash, status, confirm_token, manage_token, consent_at)
					VALUES (:e, :c::jsonb, :h, 'active', :ct, :mt, now())
					""").param("e", email).param("c", "{\"v\":1,\"region\":[\"" + r + "\"]}").param("h", "h" + r)
					.param("ct", "c" + r).param("mt", "m" + r).update();
		}
		subscribe(email, "{\"v\":1,\"region\":[\"대구\"]}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("subscription_limit"));
	}

	@Test
	void 확인_대기에서_다시_신청하면_같은_토큰으로_7일을_다시_센다() throws Exception {
		String email = "e@example.com";
		subscribe(email, "{\"v\":1}").andExpect(status().isAccepted());
		String token = confirmToken(email);
		jdbc.sql("UPDATE subscription SET requested_at = now() - interval '6 days'").update();
		subscribe(email, "{\"v\":1}").andExpect(status().isAccepted());
		org.junit.jupiter.api.Assertions.assertEquals(token, confirmToken(email));
		jdbc.sql("UPDATE subscription SET requested_at = now() - interval '8 days'").update();
		confirm(token).andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("token_expired"));
		confirm("없는-토큰").andExpect(status().isNotFound());
	}

	@Test
	void 관리_화면은_이메일을_가려_보여_주고_조건을_바꾸고_해지한다() throws Exception {
		String email = "manage@example.com";
		subscribe(email, "{\"v\":1,\"region\":[\"서울\"]}").andExpect(status().isAccepted());
		subscribe(email, "{\"v\":1,\"region\":[\"부산\"]}").andExpect(status().isAccepted());
		jdbc.sql("UPDATE subscription SET status = 'active'").update();
		String token = manageToken(email, "서울");

		mvc.perform(get("/api/alerts/manage").param("token", token)).andExpect(status().isOk())
				.andExpect(jsonPath("$.email_masked").value("ma***@example.com"))
				.andExpect(jsonPath("$.subscriptions.length()").value(2));

		long busan = idOf(email, "부산");
		put("/api/alerts/manage/subscriptions/" + busan + "?token=" + token, "{\"conditions\":{\"v\":1,\"region\":[\"서울\"]}}")
				.andExpect(status().isConflict());
		put("/api/alerts/manage/subscriptions/" + busan + "?token=" + token, "{\"conditions\":{\"v\":1,\"region\":[\"대구\"]}}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.conditions.region[0]").value("대구"));

		mvc.perform(delete("/api/alerts/manage/subscriptions/" + busan).param("token", token))
				.andExpect(status().isNoContent());
		org.junit.jupiter.api.Assertions.assertNotNull(emailOf(busan)); // 서울 구독이 남아 있어 이메일을 유지

		mvc.perform(MockMvcRequestBuilders.post("/api/alerts/manage/unsubscribe-all").param("token", token)).andExpect(status().isNoContent());
		org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT count(*) FROM subscription WHERE email IS NOT NULL")
				.query(Integer.class).single()); // P-09: 마지막 구독이 해지되면 이메일을 모두 지운다
		mvc.perform(get("/api/alerts/manage").param("token", token)).andExpect(status().isNotFound());
	}

	@Test
	void 다른_이메일의_구독은_토큰으로_건드릴_수_없다() throws Exception {
		subscribe("mine@example.com", "{\"v\":1,\"region\":[\"서울\"]}");
		subscribe("other@example.com", "{\"v\":1,\"region\":[\"서울\"]}");
		jdbc.sql("UPDATE subscription SET status = 'active'").update();
		String token = manageToken("mine@example.com", "서울");
		mvc.perform(delete("/api/alerts/manage/subscriptions/" + idOf("other@example.com", "서울")).param("token", token))
				.andExpect(status().isNotFound());
	}

	@Test
	void 메일_링크_해지는_JSON과_메일_앱의_form_두_방식을_받는다() throws Exception {
		subscribe("link@example.com", "{\"v\":1,\"region\":[\"서울\"]}");
		subscribe("link2@example.com", "{\"v\":1,\"region\":[\"부산\"]}");
		jdbc.sql("UPDATE subscription SET status = 'active'").update();
		String t1 = manageToken("link@example.com", "서울");
		String t2 = manageToken("link2@example.com", "부산");

		post("/api/alerts/unsubscribe", "{\"token\":\"" + t1 + "\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.conditions.region[0]").value("서울"));
		mvc.perform(MockMvcRequestBuilders.post("/api/alerts/unsubscribe").param("token", t2).contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.content("List-Unsubscribe=One-Click")).andExpect(status().isOk());
		post("/api/alerts/unsubscribe", "{\"token\":\"" + t1 + "\"}").andExpect(status().isNotFound()); // E-16
	}

	@Test
	void 관리_링크_메일은_이메일이_없어도_202이고_같은_이메일은_시간당_한_번() throws Exception {
		post("/api/alerts/manage-link", "{\"email\":\"nobody@example.com\"}").andExpect(status().isAccepted());
		post("/api/alerts/manage-link", "{\"email\":\"nobody@example.com\"}").andExpect(status().isTooManyRequests());
	}

	// ---- 도우미 ----

	private ResultActions subscribe(String email, String conditions) throws Exception {
		return post("/api/alerts/subscriptions",
				"{\"email\":\"" + email + "\",\"consent\":true,\"conditions\":" + conditions + "}");
	}

	private ResultActions confirm(String token) throws Exception {
		return post("/api/alerts/confirm", "{\"token\":\"" + token + "\"}");
	}

	private ResultActions post(String url, String body) throws Exception {
		return mvc.perform(MockMvcRequestBuilders.post(url)
				.header("X-Real-IP", "10.0.0." + IP.incrementAndGet()).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions put(String url, String body) throws Exception {
		return mvc.perform(MockMvcRequestBuilders.put(url)
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String statusOf(String email) {
		return jdbc.sql("SELECT status FROM subscription WHERE email = :e ORDER BY id DESC LIMIT 1").param("e", email)
				.query(String.class).single();
	}

	private String confirmToken(String email) {
		return jdbc.sql("SELECT confirm_token FROM subscription WHERE email = :e ORDER BY id DESC LIMIT 1")
				.param("e", email).query(String.class).single();
	}

	private String manageToken(String email, String region) {
		return jdbc.sql("SELECT manage_token FROM subscription WHERE email = :e AND conditions ->> 'region' LIKE :r")
				.param("e", email).param("r", "%" + region + "%").query(String.class).single();
	}

	private long idOf(String email, String region) {
		return jdbc.sql("SELECT id FROM subscription WHERE email = :e AND conditions ->> 'region' LIKE :r")
				.param("e", email).param("r", "%" + region + "%").query(Long.class).single();
	}

	private String emailOf(long id) {
		return jdbc.sql("SELECT email FROM subscription WHERE id = :id").param("id", id).query(String.class).single();
	}

	private void notice(String no, String region) {
		jdbc.sql("""
				INSERT INTO notice (house_manage_no, notice_type, source_api, name, region, address, house_kind,
				    announced_on, rcept_start, rcept_end, notice_url, raw, raw_hash, first_collected_at, last_checked_at)
				VALUES (:no, 'general', 'apt', '단지', :region, :region, 'private', :d, :d, :end, 'https://x',
				    '{}'::jsonb, 'h', now(), now())
				""").param("no", no).param("region", region).param("d", LocalDate.now().minusDays(1))
				.param("end", LocalDate.now().plusDays(3)).update();
	}
}
