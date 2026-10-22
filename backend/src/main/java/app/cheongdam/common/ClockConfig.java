package app.cheongdam.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 접수 상태와 D-day는 한국 시간 날짜로 판단한다(P-01). */
	/** API 시각은 항상 한국 시간 오프셋(+09:00)으로 내려준다(API.md 1장). DB는 UTC로 돌려줄 수 있다. */
	public static java.time.OffsetDateTime kst(java.time.OffsetDateTime at) {
		return at == null ? null : at.atZoneSameInstant(KST).toOffsetDateTime();
	}

	@Bean
	Clock clock() {
		return Clock.system(KST);
	}
}
