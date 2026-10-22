package app.cheongdam.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 접수 상태와 D-day는 한국 시간 날짜로 판단한다(P-01). */
	@Bean
	Clock clock() {
		return Clock.system(KST);
	}
}
