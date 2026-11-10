package app.cheongdam.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** SMTP 발송기가 메일을 어떻게 만들고 실패를 어떻게 나누는지(ALERT_DELIVERY 3.3). 실제 SMTP 서버에 붙지 않는다. */
class SmtpAlertMailerTest {

	private static final Map<String, Object> CONDITIONS = Map.of("v", 1, "region", List.of("경기"));
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("Asia/Seoul"));

	/** 보내는 대신 만들어진 메일을 붙잡아 둔다. */
	static class CapturingSender extends JavaMailSenderImpl {
		MimeMessage sent;
		RuntimeException failure;

		@Override
		public void send(MimeMessage... messages) {
			if (failure != null) {
				throw failure;
			}
			sent = messages[0];
		}
	}

	private final CapturingSender sender = new CapturingSender();
	private final SmtpAlertMailer mailer = new SmtpAlertMailer(sender,
			new AlertMailRenderer("https://cheongdam.app", CLOCK), "", "청담", "noreply@cheongdam.app");

	@Test
	void 알림_메일은_발신자와_본문_두_벌과_원클릭_해지_헤더를_담는다() throws Exception {
		DigestMail digest = new DigestMail("[청담] 새 공고 1건 · 경기", CONDITIONS, "경기", List.of(), List.of(), 0, "mt1");

		mailer.digest("reader@example.com", digest);

		MimeMessage m = sender.sent;
		InternetAddress from = (InternetAddress) m.getFrom()[0];
		assertEquals("noreply@cheongdam.app", from.getAddress());
		assertEquals("청담", from.getPersonal());
		assertEquals("reader@example.com", m.getAllRecipients()[0].toString());
		assertEquals("[청담] 새 공고 1건 · 경기", m.getSubject());
		// RFC 8058: 주소는 꺾쇠 안에, 원클릭임을 함께 알린다
		assertEquals("<https://cheongdam.app/api/alerts/unsubscribe?token=mt1>", m.getHeader("List-Unsubscribe")[0]);
		assertEquals("List-Unsubscribe=One-Click", m.getHeader("List-Unsubscribe-Post")[0]);

		m.saveChanges();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		m.writeTo(out);
		String raw = out.toString(StandardCharsets.UTF_8);
		assertTrue(raw.contains("text/plain"), "텍스트 본문이 있어야 한다");
		assertTrue(raw.contains("text/html"), "HTML 본문이 있어야 한다");
	}

	@Test
	void 해지_링크가_없는_메일에는_해지_헤더를_넣지_않는다() throws Exception {
		mailer.confirmation("reader@example.com", "ct1", CONDITIONS);

		assertEquals("[청담] 알림을 시작하려면 확인해 주세요", sender.sent.getSubject());
		assertNull(sender.sent.getHeader("List-Unsubscribe"));
	}

	@Test
	void 인증_실패와_발송_한도는_설정_문제로_그_밖의_실패는_일시_실패로_나눈다() {
		assertInstanceOf(MailProviderException.class,
				SmtpAlertMailer.classify(new MailAuthenticationException("535-5.7.8 Username and Password not accepted")));
		assertInstanceOf(MailProviderException.class,
				SmtpAlertMailer.classify(new MailSendException("550 5.4.5 Daily sending quota exceeded")));
		assertInstanceOf(TransientMailException.class,
				SmtpAlertMailer.classify(new MailSendException("Couldn't connect to host: connection timed out")));
	}

	@Test
	void 발송에_실패하면_분류한_예외를_던진다() {
		sender.failure = new MailAuthenticationException("535-5.7.8");
		try {
			mailer.manageLink("reader@example.com", "mt1");
			org.junit.jupiter.api.Assertions.fail("예외가 나와야 한다");
		} catch (RuntimeException e) {
			assertInstanceOf(MailProviderException.class, e);
		}
	}
}
