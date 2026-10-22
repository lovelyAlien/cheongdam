package app.cheongdam.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DistrictTest {

	@Test
	void 시군구까지_자른다() {
		assertThat(District.of("경기도 수원시 장안구 ○○동 123")).isEqualTo("수원시 장안구");
		assertThat(District.of("서울특별시 강동구 ○○동 45")).isEqualTo("강동구");
		assertThat(District.of("충청남도 천안시 서북구 ○○동 2")).isEqualTo("천안시 서북구");
	}

	@Test
	void 규칙에_맞지_않으면_null() {
		assertThat(District.of("세종특별자치시 한누리대로 1")).isNull();
		assertThat(District.of(null)).isNull();
		assertThat(District.of("  ")).isNull();
	}
}
