package app.cheongdam.mail;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** NCP Mailer를 연결하기 전까지의 자리. 토큰이 든 링크는 로그에 남기지 않는다. */
@Component
@Profile("!dev")
class UnconfiguredAlertMailer implements AlertMailer {

	private static final Logger log = LoggerFactory.getLogger(UnconfiguredAlertMailer.class);

	@Override
	public void confirmation(String email, String confirmToken, Map<String, Object> conditions) {
		log.warn("메일 발송이 아직 연결되지 않아 M-01을 보내지 못했어요");
	}

	@Override
	public void unsubscribed(String email, List<Map<String, Object>> conditions, boolean emailDeleted) {
		log.warn("메일 발송이 아직 연결되지 않아 M-04를 보내지 못했어요");
	}

	@Override
	public void manageLink(String email, String manageToken) {
		log.warn("메일 발송이 아직 연결되지 않아 관리 링크를 보내지 못했어요");
	}
}
