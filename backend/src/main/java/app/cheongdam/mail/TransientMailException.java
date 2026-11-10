package app.cheongdam.mail;

/** 일시적인 발송 실패(연결 끊김, 타임아웃 등). 1시간 뒤 한 번 더 시도한다(P-20). */
public class TransientMailException extends RuntimeException {

	public TransientMailException(String message, Throwable cause) {
		super(message, cause);
	}
}
