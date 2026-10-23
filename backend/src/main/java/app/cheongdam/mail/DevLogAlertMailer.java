package app.cheongdam.mail;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 개발 중에는 메일 대신 링크를 로그로 찍어 구독 흐름을 직접 따라가 볼 수 있게 한다. */
@Component
@Profile("dev")
class DevLogAlertMailer implements AlertMailer {

	private static final Logger log = LoggerFactory.getLogger(DevLogAlertMailer.class);

	@Override
	public void confirmation(String email, String confirmToken, Map<String, Object> conditions) {
		log.info("[M-01] to={} /alerts/confirm?token={} conditions={}", email, confirmToken, conditions);
	}

	@Override
	public void unsubscribed(String email, List<Map<String, Object>> conditions, boolean emailDeleted) {
		log.info("[M-04] to={} conditions={} emailDeleted={}", email, conditions, emailDeleted);
	}

	@Override
	public void manageLink(String email, String manageToken) {
		log.info("[관리 링크] to={} /alerts/manage?token={}", email, manageToken);
	}
}
