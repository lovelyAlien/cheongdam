package app.cheongdam.mail;

/**
 * 발송기 설정이나 한도 문제(인증 실패, 발송 한도 초과, 발송기 미설정).
 * 고치면 그날 쌓인 메일이 그대로 나가야 하므로 재시도 횟수를 쓰지 않는다(ALERT_DELIVERY 3.3).
 */
public class MailProviderException extends RuntimeException {

	public MailProviderException(String message) {
		super(message);
	}

	public MailProviderException(String message, Throwable cause) {
		super(message, cause);
	}
}
