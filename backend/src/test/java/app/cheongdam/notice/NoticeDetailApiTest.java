package app.cheongdam.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 오늘을 2026-10-22(목) 한국 시간으로 고정하고 상세 API와 캘린더를 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(NoticeDetailApiTest.FixedClockConfig.class)
class NoticeDetailApiTest {

	static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-10-22T01:00:00Z"), ZoneId.of("Asia/Seoul"));
		}
	}

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void fixtures() {
		// notice_change·extraction_run은 notice_version에 ON DELETE CASCADE가 없어 먼저 지운다
		jdbc.sql("DELETE FROM notice_change").update();
		jdbc.sql("DELETE FROM extraction_run").update();
		jdbc.sql("DELETE FROM notice").update();
		notice("D1", "general", "private", "자이 센트럴", -1, 2, 2, "done");
		notice("D2", "no_rank", null, "더샵 무순위", -10, -5, 1, "pending");
		jdbc.sql("INSERT INTO notice_version (house_manage_no, version, detected_at, api_hash, pdf_status, change_source) VALUES "
				+ "('D1',1,now(),'h','ok',NULL),('D1',2,now(),'h','ok','pdf')").update();
		jdbc.sql("INSERT INTO notice_change (house_manage_no, version, field, before_value, after_value, source) VALUES "
				+ "('D1',2,'1순위 접수일','2026-10-21','2026-10-23','pdf_notice_box')").update();
		schedule("D1", "special", "all", -3, -3);
		schedule("D1", "rank1", "local", -1, 0);
		schedule("D1", "rank1", "gyeonggi", 1, 1);
		schedule("D1", "winner", "all", 9, 9);
		schedule("D2", "no_rank", "all", -10, -5);
		unit("D1", "01", 59.97, 30000, 10, 100);
		unit("D1", "02", 84.89, 60000, 0, 200);
		jdbc.sql("INSERT INTO extraction_run (house_manage_no, version, status, attempt) VALUES ('D1',2,'done',1)").update();
		item("D1", 6, "rewin_limit", "{전체}", "당첨일부터 10년간 재당첨이 제한돼요", false, "confirmed", 34);
		item("D1", 6, "resale_limit", "{전체}", "전매제한 3년", false, "confirmed", 35);
		item("D1", 4, null, "{newlywed}", "혼인 기간이 7년 이내여야 해요", false, "needs_review", 11);
	}

	@Test
	void 헤더는_상태와_정정_횟수와_정정_배지를_준다() throws Exception {
		mvc.perform(get("/api/notices/D1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.header.status").value("open"))
				.andExpect(jsonPath("$.header.d_day").value(2))
				.andExpect(jsonPath("$.header.correction_count").value(1))
				.andExpect(jsonPath("$.header.removed").value(false))
				.andExpect(jsonPath("$.header.last_checked_at").value(org.hamcrest.Matchers.endsWith("+09:00")))
				.andExpect(jsonPath("$.header.is_regulated").doesNotExist()); // D-03
	}

	@Test
	void 일정은_단계_순서로_오늘_기준_state를_준다() throws Exception {
		mvc.perform(get("/api/notices/D1"))
				.andExpect(jsonPath("$.schedule.length()").value(4))
				.andExpect(jsonPath("$.schedule[0].stage").value("special"))
				.andExpect(jsonPath("$.schedule[0].state").value("done"))
				.andExpect(jsonPath("$.schedule[1].stage").value("rank1"))
				.andExpect(jsonPath("$.schedule[1].area").value("local"))
				.andExpect(jsonPath("$.schedule[1].state").value("today"))
				.andExpect(jsonPath("$.schedule[2].area").value("gyeonggi"))
				.andExpect(jsonPath("$.schedule[2].state").value("upcoming"))
				.andExpect(jsonPath("$.schedule[3].stage").value("winner"));
	}

	@Test
	void 요약은_주택형_합계와_분양가_범위를_준다() throws Exception {
		mvc.perform(get("/api/notices/D1"))
				.andExpect(jsonPath("$.summary.special_households").value(10)) // unit() 헬퍼가 special_count = 신혼 세대수로 넣는다 (10 + 0)
				.andExpect(jsonPath("$.summary.general_households").value(300))
				.andExpect(jsonPath("$.summary.price_min_man").value(30000))
				.andExpect(jsonPath("$.summary.price_max_man").value(60000))
				.andExpect(jsonPath("$.units[0].special_by_type.newlywed").value(10));
	}

	@Test
	void 추출_결과는_블록_B와_C로_나뉘고_빠진_코드를_알려_준다() throws Exception {
		mvc.perform(get("/api/notices/D1"))
				.andExpect(jsonPath("$.extraction.status").value("done"))
				.andExpect(jsonPath("$.extraction.reprocessing").value(false))
				.andExpect(jsonPath("$.extraction.constraints.items.length()").value(2))
				.andExpect(jsonPath("$.extraction.constraints.items[0].code").value("rewin_limit"))
				.andExpect(jsonPath("$.extraction.constraints.items[0].evidence[0].page").value(34))
				.andExpect(jsonPath("$.extraction.constraints.missing_codes.length()").value(5))
				.andExpect(jsonPath("$.extraction.conditions.items.length()").value(1))
				.andExpect(jsonPath("$.extraction.conditions.items[0].status").value("needs_review"))
				.andExpect(jsonPath("$.extraction.conditions.supply_types[0].code").value("general_1st"))
				.andExpect(jsonPath("$.extraction.conditions.supply_types[2].code").value("newlywed"));
	}

	@Test
	void 정정_이력은_바뀐_항목을_준다() throws Exception {
		mvc.perform(get("/api/notices/D1"))
				.andExpect(jsonPath("$.changes.length()").value(1))
				.andExpect(jsonPath("$.changes[0].version").value(2))
				.andExpect(jsonPath("$.changes[0].items[0].field").value("1순위 접수일"))
				.andExpect(jsonPath("$.changes[0].items[0].before").value("2026-10-21"))
				.andExpect(jsonPath("$.changes[0].items[0].after").value("2026-10-23"));
	}

	@Test
	void 추출_전인_무순위_공고는_준비_중이고_유형별_세대수가_없다() throws Exception {
		mvc.perform(get("/api/notices/D2"))
				.andExpect(jsonPath("$.header.status").value("closed"))
				.andExpect(jsonPath("$.extraction.status").value("pending"))
				.andExpect(jsonPath("$.extraction.constraints.items.length()").value(0))
				.andExpect(jsonPath("$.extraction.conditions.supply_types[0].code").value("no_rank"))
				.andExpect(jsonPath("$.changes.length()").value(0));
	}

	@Test
	void 없는_번호는_404() throws Exception {
		mvc.perform(get("/api/notices/NOPE"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("not_found"));
	}

	@Test
	void 캘린더는_남은_일정만_종일_이벤트로_담고_UID가_고정이다() throws Exception {
		String ics = mvc.perform(get("/api/notices/D1/calendar.ics"))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("cheongdam-D1.ics")))
				.andExpect(content().contentTypeCompatibleWith("text/calendar"))
				.andReturn().getResponse().getContentAsString().replace("\r\n ", ""); // 접힌 줄을 편다
		assertThat(ics).contains("BEGIN:VCALENDAR").contains("END:VCALENDAR");
		assertThat(ics).contains("UID:D1-rank1-local@cheongdam.app", "UID:D1-winner-all@cheongdam.app");
		assertThat(ics).doesNotContain("UID:D1-special-all@cheongdam.app"); // 이미 지난 일정
		assertThat(ics).contains("DTSTART;VALUE=DATE:20261021", "DTEND;VALUE=DATE:20261023"); // 10.21~10.22 → 종료는 다음 날(배타)
		assertThat(ics).contains("SUMMARY:[청담] 자이 센트럴 1순위 접수 (해당지역)");
		assertThat(ics).contains("TRIGGER:-PT15H").contains("\r\n");
	}

	@Test
	void 남은_일정이_없으면_캘린더는_404() throws Exception {
		mvc.perform(get("/api/notices/D2/calendar.ics")).andExpect(status().isNotFound());
	}

	@Test
	void ics_긴_줄은_75바이트로_접고_글자를_자르지_않는다() {
		String folded = NoticeCalendar.fold("SUMMARY:" + "가".repeat(40));
		for (String line : folded.split("\r\n")) {
			assertThat(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
		}
		assertThat(folded.replace("\r\n ", "")).isEqualTo("SUMMARY:" + "가".repeat(40));
	}

	private void notice(String no, String type, String kind, String name, int startOffset, int endOffset,
			int version, String summaryStatus) {
		jdbc.sql("""
				INSERT INTO notice (house_manage_no, notice_type, source_api, name, region, address, house_kind,
				    announced_on, rcept_start, rcept_end, notice_url, raw, raw_hash,
				    first_collected_at, last_checked_at, last_changed_at, current_version, summary_status)
				VALUES (:no, :type, :api, :name, '경기', '경기도 수원시 장안구 1', :kind, :ann, :start, :end,
				    'https://www.applyhome.co.kr/x', '{}'::jsonb, 'h', now(), now(),
				    CASE WHEN :v > 1 THEN now() END, :v, :ss)
				""")
				.param("no", no).param("type", type).param("api", type.equals("general") ? "apt" : "remndr")
				.param("name", name).param("kind", kind).param("ann", TODAY.minusDays(1))
				.param("start", TODAY.plusDays(startOffset)).param("end", TODAY.plusDays(endOffset))
				.param("v", version).param("ss", summaryStatus)
				.update();
	}

	private void schedule(String no, String stage, String area, int s, int e) {
		jdbc.sql("INSERT INTO notice_schedule VALUES (:no, :stage, :area, :s, :e)")
				.param("no", no).param("stage", stage).param("area", area)
				.param("s", TODAY.plusDays(s)).param("e", TODAY.plusDays(e)).update();
	}

	private void unit(String no, String model, double area, int price, int newlywed, int general) {
		jdbc.sql("""
				INSERT INTO notice_unit (house_manage_no, model_no, house_ty, exclusive_area, top_price_man,
				    general_count, special_count, sp_newlywed, raw)
				VALUES (:no, :m, :ty, :a, :p, :g, :nw, :nw, '{}'::jsonb)
				""")
				.param("no", no).param("m", model).param("ty", String.valueOf(area)).param("a", area)
				.param("p", price).param("g", general).param("nw", newlywed).update();
	}

	private void item(String no, int category, String code, String targets, String text, boolean general,
			String status, int page) {
		Long id = jdbc.sql("""
				INSERT INTO extracted_item (house_manage_no, version, category, constraint_code, targets, requirement,
				    is_general_rule, status, sort_order)
				VALUES (:no, 2, :c, :code, :t::text[], :text, :g, :s, 0) RETURNING id
				""")
				.param("no", no).param("c", category).param("code", code).param("t", targets).param("text", text)
				.param("g", general).param("s", status).query(Long.class).single();
		jdbc.sql("INSERT INTO item_evidence (item_id, page, quote, verified) VALUES (:id, :p, '인용 문구', true)")
				.param("id", id).param("p", page).update();
	}
}
