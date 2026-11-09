package app.cheongdam.mail;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 발송기를 고르지 않았을 때의 자리({@code app.mail.provider=none}, 기본값). 토큰이 든 링크는 로그에 남기지 않는다.
 * 이 상태로 발송 배치를 켜면 {@link MailConfig}가 시작할 때 막는다.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "none", matchIfMissing = true)
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

	/** 보냈다고 기록하지 않도록 던진다. 설정 문제이므로 재시도 횟수를 쓰지 않는다(ALERT_DELIVERY 3.3). */
	@Override
	public void digest(String email, DigestMail mail) {
		throw new MailProviderException("메일 발송기가 설정되지 않았어요. app.mail.provider를 지정해 주세요.");
	}

	@Override
	public void manageLink(String email, String manageToken) {
		log.warn("메일 발송이 아직 연결되지 않아 관리 링크를 보내지 못했어요");
	}
}
