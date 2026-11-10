package app.cheongdam.mail;

import java.util.List;
import java.util.Map;

/**
 * 사용자 동작에 맞춰 보내는 구독 메일을 트랜잭션이 커밋된 뒤에 보내기 위한 이벤트(ALERT_DELIVERY 4장).
 * 해지는 같은 트랜잭션에서 이메일을 지우므로(P-09) 주소를 엔티티가 아니라 값으로 담는다.
 */
public final class AlertMailEvents {

	private AlertMailEvents() {
	}

	/** M-01 구독 확인 */
	public record Confirmation(String email, String confirmToken, Map<String, Object> conditions) {
	}

	/** M-04 해지 완료 */
	public record Unsubscribed(String email, List<Map<String, Object>> conditions, boolean emailDeleted) {
	}

	/** 관리 링크 메일(E-18) */
	public record ManageLink(String email, String manageToken) {
	}
}
