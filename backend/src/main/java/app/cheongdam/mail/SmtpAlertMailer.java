package app.cheongdam.mail;

import app.cheongdam.mail.AlertMailRenderer.RenderedMail;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP로 보내는 실제 발송기. 개발 중에는 Gmail(smtp.gmail.com:587, 앱 비밀번호)을 쓰고,
 * 런칭 때는 호스트와 계정만 바꾸면 다른 발송기로 옮길 수 있다(ALERT_DELIVERY 3장).
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "smtp")
class SmtpAlertMailer implements AlertMailer {

	/** 원인 사슬을 따라가다 멈출 깊이. 순환 참조로 무한히 도는 것을 막는다. */
	private static final int MAX_CAUSE_DEPTH = 10;
	/** 발송기 설정·한도 문제로 보는 SMTP 응답 코드와 문구 */
	private static final List<String> PROVIDER_SIGNS = List.of("5.7.8", "5.7.9", "5.4.5", "5.3.4", "sending limit",
			"daily sending quota", "quota exceeded");

	private final JavaMailSender sender;
	private final AlertMailRenderer renderer;
	private final String from;
	private final String fromName;

	SmtpAlertMailer(JavaMailSender sender, AlertMailRenderer renderer,
			@Value("${app.mail.from:}") String from,
			@Value("${app.mail.from-name:청담}") String fromName,
			@Value("${spring.mail.username:}") String username) {
		this.sender = sender;
		this.renderer = renderer;
		this.from = from.isBlank() ? username : from;
		this.fromName = fromName;
		if (this.from.isBlank()) {
			throw new IllegalStateException("app.mail.from이나 spring.mail.username을 설정해 주세요.");
		}
	}

	@Override
	public void confirmation(String email, String confirmToken, Map<String, Object> conditions) {
		send(email, renderer.confirmation(confirmToken, conditions));
	}

	@Override
	public void unsubscribed(String email, List<Map<String, Object>> conditions, boolean emailDeleted) {
		send(email, renderer.unsubscribed(conditions, emailDeleted));
	}

	@Override
	public void digest(String email, DigestMail mail) {
		send(email, renderer.digest(mail));
	}

	@Override
	public void manageLink(String email, String manageToken) {
		send(email, renderer.manageLink(manageToken));
	}

	private void send(String email, RenderedMail mail) {
		MimeMessage message = sender.createMimeMessage();
		try {
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(from, fromName);
			helper.setTo(email);
			helper.setSubject(mail.subject());
			helper.setText(mail.text(), mail.html());
			if (mail.listUnsubscribeUrl() != null) {
				// RFC 8058 원클릭 해지. 메일 앱의 해지 버튼이 /api/alerts/unsubscribe로 POST한다
				message.setHeader("List-Unsubscribe", "<" + mail.listUnsubscribeUrl() + ">");
				message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
			}
		} catch (Exception e) {
			throw new MailProviderException("메일을 만들지 못했어요", e);
		}
		try {
			sender.send(message);
		} catch (MailException e) {
			throw classify(e);
		}
	}

	/** 설정·한도 문제와 일시 실패를 나눈다(ALERT_DELIVERY 3.3). 앞의 것은 재시도 횟수를 쓰지 않는다. */
	static RuntimeException classify(MailException e) {
		if (e instanceof MailAuthenticationException) {
			return new MailProviderException("SMTP 인증에 실패했어요. 사용자 이름과 앱 비밀번호를 확인해 주세요", e);
		}
		Throwable t = e;
		for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
			String message = t.getMessage();
			if (message == null) {
				continue;
			}
			String lower = message.toLowerCase(Locale.ROOT);
			if (PROVIDER_SIGNS.stream().anyMatch(lower::contains)) {
				return new MailProviderException("발송기 설정이나 한도 문제예요: " + message, e);
			}
		}
		return new TransientMailException("메일을 보내지 못했어요", e);
	}
}
