package app.cheongdam.mail;

import java.util.List;
import java.util.Map;

/** 사용자 동작에 맞춰 바로 보내는 구독 메일(M-01, M-04, 관리 링크). 실제 발송기는 NCP Mailer 연결 때 구현한다. */
public interface AlertMailer {

	/** M-01 구독 확인 */
	void confirmation(String email, String confirmToken, Map<String, Object> conditions);

	/** M-04 해지 완료. emailDeleted면 이메일 주소를 바로 지웠다는 문구를 넣는다. */
	void unsubscribed(String email, List<Map<String, Object>> conditions, boolean emailDeleted);

	/** 관리 링크 메일(E-18) */
	void manageLink(String email, String manageToken);
}
