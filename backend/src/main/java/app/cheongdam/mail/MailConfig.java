package app.cheongdam.mail;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** 발송기를 고르지 않은 채 알림 발송 배치가 켜지는 것을 시작할 때 막는다(ALERT_DELIVERY 3.4). */
@Configuration
class MailConfig {

	private final String provider;
	private final boolean dispatchEnabled;

	MailConfig(@Value("${app.mail.provider:none}") String provider,
			@Value("${app.alerts.dispatch.enabled:false}") boolean dispatchEnabled) {
		this.provider = provider;
		this.dispatchEnabled = dispatchEnabled;
	}

	@PostConstruct
	void check() {
		if (dispatchEnabled && provider.equals("none")) {
			throw new IllegalStateException(
					"app.alerts.dispatch.enabled=true인데 app.mail.provider=none이에요. 발송기를 고른 뒤 켜 주세요.");
		}
	}
}
