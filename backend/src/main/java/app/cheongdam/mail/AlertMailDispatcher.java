package app.cheongdam.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 구독 메일을 커밋된 뒤에 보낸다(ALERT_DELIVERY 4장). 발송이 실패해도 이미 끝난 신청·해지를 되돌리지 않는다.
 * M-01은 다시 신청하면 같은 토큰으로 다시 가고(E-17), M-04는 해지가 이미 끝났으므로 로그만 남긴다.
 */
@Component
class AlertMailDispatcher {

	private static final Logger log = LoggerFactory.getLogger(AlertMailDispatcher.class);

	private final AlertMailer mailer;

	AlertMailDispatcher(AlertMailer mailer) {
		this.mailer = mailer;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onConfirmation(AlertMailEvents.Confirmation e) {
		send("M-01", () -> mailer.confirmation(e.email(), e.confirmToken(), e.conditions()));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onUnsubscribed(AlertMailEvents.Unsubscribed e) {
		send("M-04", () -> mailer.unsubscribed(e.email(), e.conditions(), e.emailDeleted()));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onManageLink(AlertMailEvents.ManageLink e) {
		send("관리 링크", () -> mailer.manageLink(e.email(), e.manageToken()));
	}

	/** 커밋 뒤 콜백에서 던지면 이미 끝난 요청이 500이 되므로 여기서 삼킨다. */
	private void send(String kind, Runnable task) {
		try {
			task.run();
		} catch (RuntimeException e) {
			log.error("{} 메일을 보내지 못했어요", kind, e);
		}
	}
}
