package app.cheongdam.notice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** 오늘을 2026-10-22(목) 한국 시간으로 고정하고 목록 API의 필터·정렬·상태를 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(NoticeApiTest.FixedClockConfig.class)
class NoticeApiTest {

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
		jdbc.sql("DELETE FROM notice_change").update();
		jdbc.sql("DELETE FROM extraction_run").update();
		jdbc.sql("DELETE FROM notice").update();
		// 접수 중(종료 D-2), 접수 예정(D-3), 마감(어제 종료), 일정 미정, 무순위(주택 구분 없음)
		notice("N1", "general", "private", "경기", "자이 센트럴", -1, 2);
		notice("N2", "general", "public", "서울", "행복주택", 3, 5);
		notice("N3", "general", "private", "부산", "푸르지오", -10, -1);
		notice("N4", "general", "private", "대구", "일정 미정 단지", null, null);
		notice("N5", "no_rank", null, "인천", "더샵 (무순위)", 0, 1);
		// N1: 59㎡ 3억(신혼 10), 84㎡ 6억(신혼 0). 신혼 + 5억 이하 + 60~85㎡는 같은 주택형이 없다.
		unit("N1", "01", 59.97, 30000, 10);
		unit("N1", "02", 84.89, 60000, 0);
		unit("N2", "01", 46.0, 20000, 5);
		unit("N3", "01", 74.99, 40000, 3);
		unit("N5", "01", 59.99, 45000, null);
	}

	@Test
	void 기본_조건은_접수_예정과_접수_중이고_접수_중이_먼저_나온다() throws Exception {
		mvc.perform(get("/api/notices"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-cache"))
				.andExpect(jsonPath("$.total").value(4))
				.andExpect(jsonPath("$.items[0].house_manage_no").value("N5")) // 접수 중, 종료 D-1
				.andExpect(jsonPath("$.items[0].d_day").value(1))
				.andExpect(jsonPath("$.items[1].house_manage_no").value("N1")) // 접수 중, 종료 D-2
				.andExpect(jsonPath("$.items[2].house_manage_no").value("N2")) // 접수 예정 D-3
				.andExpect(jsonPath("$.items[2].status").value("upcoming"))
				.andExpect(jsonPath("$.items[2].d_day").value(3))
				.andExpect(jsonPath("$.items[3].status").value("unknown")); // 일정 확인 필요는 접수 예정 뒤
	}

	@Test
	void 마감_공고는_closed를_고를_때만_나오고_맨_뒤다() throws Exception {
		mvc.perform(get("/api/notices").param("status", "open,upcoming,closed"))
				.andExpect(jsonPath("$.total").value(5))
				.andExpect(jsonPath("$.items[4].house_manage_no").value("N3"))
				.andExpect(jsonPath("$.items[4].status").value("closed"));
	}

	@Test
	void 주택형_조건은_한_주택형이_모두_만족해야_한다() throws Exception {
		// N1은 신혼 세대가 있는 59㎡(3억)와 6억 84㎡가 따로 있어 신혼+5억 이하+60~85㎡를 한 주택형이 못 채운다
		mvc.perform(get("/api/notices")
				.param("supply", "newlywed").param("price_max", "50000").param("area", "60to85"))
				.andExpect(jsonPath("$.total").value(0))
				.andExpect(jsonPath("$.relaxations.length()").value(2));
		mvc.perform(get("/api/notices").param("supply", "newlywed").param("price_max", "50000").param("area", "le60"))
				.andExpect(jsonPath("$.total").value(2)) // N1(59㎡) N2(46㎡)
				.andExpect(jsonPath("$.items[?(@.house_manage_no=='N1')].price_max_man").value(60000));
	}

	@Test
	void 주택_구분이나_특별공급_필터는_무순위를_빼고_제외_건수를_알려_준다() throws Exception {
		mvc.perform(get("/api/notices").param("house_kind", "private"))
				.andExpect(jsonPath("$.total").value(2)) // N1, N4
				.andExpect(jsonPath("$.no_info_excluded").value(1)); // N5
	}

	@Test
	void 결과가_0건이면_완화_제안을_건수_큰_순으로_준다() throws Exception {
		mvc.perform(get("/api/notices").param("region", "서울").param("price_max", "1000"))
				.andExpect(jsonPath("$.total").value(0))
				.andExpect(jsonPath("$.relaxations.length()").value(1)) // 서울 조건만 빼면 가격 조건에도 걸려 0건, 가격만 빼면 1건
				.andExpect(jsonPath("$.relaxations[0].drop").value("price"))
				.andExpect(jsonPath("$.relaxations[0].total").value(1));
	}

	@Test
	void 단지명_검색은_공백과_괄호를_무시한다() throws Exception {
		mvc.perform(get("/api/notices").param("q", "더샵무순위"))
				.andExpect(jsonPath("$.total").value(1));
		mvc.perform(get("/api/notices").param("q", "더샵 (무순위)"))
				.andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void 최신_공고순과_limit_0() throws Exception {
		mvc.perform(get("/api/notices").param("limit", "0"))
				.andExpect(jsonPath("$.total").value(4))
				.andExpect(jsonPath("$.items.length()").value(0));
		mvc.perform(get("/api/notices").param("sort", "latest").param("limit", "1"))
				.andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void 잘못된_값은_400과_필드_이름을_준다() throws Exception {
		mvc.perform(get("/api/notices").param("sort", "bad"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("invalid_param"))
				.andExpect(jsonPath("$.error.field").value("sort"));
		mvc.perform(get("/api/notices").param("supply", "unknown"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/notices").param("price_min", "5").param("price_max", "1"))
				.andExpect(status().isBadRequest());
	}

	private void notice(String no, String type, String kind, String region, String name, Integer startOffset,
			Integer endOffset) {
		jdbc.sql("""
				INSERT INTO notice (house_manage_no, notice_type, source_api, name, region, address, house_kind,
				    announced_on, rcept_start, rcept_end, notice_url, raw, raw_hash,
				    first_collected_at, last_checked_at)
				VALUES (:no, :type, :api, :name, :region, :addr, :kind, :announced, :start, :end, 'https://x',
				    '{}'::jsonb, 'h', now(), now())
				""")
				.param("no", no).param("type", type).param("api", type.equals("general") ? "apt" : "remndr")
				.param("name", name).param("region", region).param("addr", region + " 어딘가 1")
				.param("kind", kind).param("announced", TODAY.minusDays(1))
				.param("start", startOffset == null ? null : TODAY.plusDays(startOffset))
				.param("end", endOffset == null ? null : TODAY.plusDays(endOffset))
				.update();
	}

	private void unit(String no, String model, double area, int priceMan, Integer newlywed) {
		jdbc.sql("""
				INSERT INTO notice_unit (house_manage_no, model_no, house_ty, exclusive_area, top_price_man,
				    sp_newlywed, raw)
				VALUES (:no, :model, :ty, :area, :price, :nw, '{}'::jsonb)
				""")
				.param("no", no).param("model", model).param("ty", String.valueOf(area))
				.param("area", area).param("price", priceMan).param("nw", newlywed)
				.update();
	}
}
