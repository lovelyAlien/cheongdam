package app.cheongdam.service;

import app.cheongdam.domain.Subscription;
import app.cheongdam.dto.AlertDtos.ConditionsResponse;
import app.cheongdam.dto.AlertDtos.ConfirmResponse;
import app.cheongdam.dto.AlertDtos.ManageResponse;
import app.cheongdam.dto.AlertDtos.ManagedSubscription;
import app.cheongdam.dto.SubscriptionConditions;
import app.cheongdam.exception.ApiException;
import app.cheongdam.mail.AlertMailer;
import app.cheongdam.repository.NoticeRepository;
import app.cheongdam.repository.NoticeSpecifications;
import app.cheongdam.repository.SubscriptionRepository;
import app.cheongdam.util.RateLimiter;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 알림 구독(S-08). 로그인이 없어 메일 속 토큰이 곧 권한이다(API.md 5장, D-27). */
@Service
@Transactional
public class SubscriptionService {

	static final List<String> LIVE = List.of("pending", "active");
	static final int MAX_PER_EMAIL = 3;
	static final Duration CONFIRM_WINDOW = Duration.ofDays(7);
	private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SubscriptionRepository repository;
	private final NoticeRepository noticeRepository;
	private final AlertMailer mailer;
	private final RateLimiter limiter;
	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final Clock clock;

	public SubscriptionService(SubscriptionRepository repository, NoticeRepository noticeRepository,
			AlertMailer mailer, RateLimiter limiter, JdbcClient jdbc, JsonMapper json, Clock clock) {
		this.repository = repository;
		this.noticeRepository = noticeRepository;
		this.mailer = mailer;
		this.limiter = limiter;
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
	}

	// ---- 신청·확인 ----

	/** 구독 신청. 확인 메일(M-01)을 보낸다. */
	public void subscribe(String rawEmail, Boolean consent, Map<String, Object> rawConditions, String ip) {
		if (!Boolean.TRUE.equals(consent)) {
			throw ApiException.invalidParam("consent", "개인정보 수집·이용에 동의해 주세요.");
		}
		String email = email(rawEmail);
		Map<String, Object> conditions = SubscriptionConditions.normalize(rawConditions);
		limiter.check("subscribe:ip:" + ip, 10);
		limiter.check("subscribe:email:" + email, 3);

		lock(email);
		String conditionsJson = json.writeValueAsString(conditions);
		String hash = SubscriptionConditions.hash(conditionsJson);
		OffsetDateTime now = OffsetDateTime.now(clock);

		var same = repository.findByEmailAndConditionsHashAndStatusIn(email, hash, LIVE);
		if (same.isPresent()) {
			Subscription s = same.get();
			if (s.getStatus().equals("active")) {
				throw ApiException.conflict("already_subscribed", "이미 이 조건으로 알림을 받고 있어요.");
			}
			s.renew(now); // E-17: 확인 대기면 같은 토큰으로 다시 보내고 7일을 다시 센다
			mailer.confirmation(email, s.getConfirmToken(), conditions);
			return;
		}
		if (repository.findByEmailAndStatusInOrderById(email, LIVE).size() >= MAX_PER_EMAIL) {
			throw ApiException.conflict("subscription_limit", "알림 조건은 3개까지 저장할 수 있어요.");
		}
		Subscription s = repository.save(
				Subscription.pending(email, conditionsJson, hash, token(), token(), now));
		mailer.confirmation(email, s.getConfirmToken(), conditions);
	}

	/** 확인 링크. 이미 확인한 토큰도 같은 응답을 준다. */
	public ConfirmResponse confirm(String token) {
		Subscription s = repository.findByConfirmToken(blank(token)).orElseThrow(SubscriptionService::notFound);
		OffsetDateTime now = OffsetDateTime.now(clock);
		switch (s.getStatus()) {
			case "pending" -> {
				if (s.getRequestedAt().plus(CONFIRM_WINDOW).isBefore(now)) {
					throw ApiException.gone("token_expired", "확인 기간이 지났어요. 다시 구독해 주세요.");
				}
				s.confirm(now);
			}
			case "active" -> {
			}
			default -> throw notFound();
		}
		Map<String, Object> conditions = conditions(s);
		long matches = noticeRepository.count(NoticeSpecifications.matching(SubscriptionConditions.toFilter(conditions),
				LocalDate.now(clock), Set.of(), false));
		return new ConfirmResponse(conditions, matches);
	}

	// ---- 관리 ----

	@Transactional(readOnly = true)
	public ManageResponse manage(String token) {
		Subscription s = byManageToken(token);
		List<ManagedSubscription> subs = repository.findByEmailAndStatusInOrderById(s.getEmail(), LIVE).stream()
				.map(x -> new ManagedSubscription(x.getId(), conditions(x), x.getStatus())).toList();
		return new ManageResponse(mask(s.getEmail()), subs);
	}

	public ConditionsResponse changeConditions(String token, long id, Map<String, Object> rawConditions) {
		Subscription s = byManageToken(token);
		Subscription target = owned(s, id);
		Map<String, Object> conditions = SubscriptionConditions.normalize(rawConditions);
		lock(s.getEmail());
		String conditionsJson = json.writeValueAsString(conditions);
		String hash = SubscriptionConditions.hash(conditionsJson);
		repository.findByEmailAndConditionsHashAndStatusIn(s.getEmail(), hash, LIVE)
				.filter(other -> !other.getId().equals(target.getId()))
				.ifPresent(other -> {
					throw ApiException.conflict("already_subscribed", "이미 이 조건으로 알림을 받고 있어요.");
				});
		target.changeConditions(conditionsJson, hash);
		return new ConditionsResponse(conditions);
	}

	public void unsubscribeOne(String token, long id) {
		Subscription s = byManageToken(token);
		unsubscribe(s.getEmail(), List.of(owned(s, id)));
	}

	public void unsubscribeAll(String token) {
		Subscription s = byManageToken(token);
		unsubscribe(s.getEmail(), repository.findByEmailAndStatusInOrderById(s.getEmail(), LIVE));
	}

	/** 메일 링크 해지(S-08d). 확인 단계 없이 그 메일이 속한 조건 하나만 해지한다. */
	public ConditionsResponse unsubscribeByLink(String token) {
		Subscription s = byManageToken(token);
		Map<String, Object> conditions = conditions(s);
		unsubscribe(s.getEmail(), List.of(s));
		return new ConditionsResponse(conditions);
	}

	/** 관리 링크 메일(E-18). 이메일이 있는지 알려 주지 않으려고 호출 쪽은 항상 같은 응답을 준다. */
	public void sendManageLink(String rawEmail, String ip) {
		String email = email(rawEmail);
		limiter.check("manage-link:ip:" + ip, 10);
		limiter.check("manage-link:email:" + email, 1);
		List<Subscription> live = repository.findByEmailAndStatusInOrderById(email, LIVE);
		live.stream().filter(s -> s.getStatus().equals("active")).findFirst().or(() -> live.stream().findFirst())
				.ifPresent(s -> mailer.manageLink(email, s.getManageToken()));
	}

	// ---- 내부 ----

	/** 해지하고 M-04를 보낸다. 남은 구독이 없으면 메일을 보낸 뒤 이메일을 지운다(P-09). */
	private void unsubscribe(String email, List<Subscription> targets) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		List<Map<String, Object>> conditions = targets.stream().map(this::conditions).toList();
		targets.forEach(t -> t.unsubscribe(now));
		boolean last = repository.findByEmailAndStatusInOrderById(email, LIVE).isEmpty();
		mailer.unsubscribed(email, conditions, last);
		if (last) {
			// 발송 중지(suspended)의 이메일은 차단용으로 남긴다
			repository.findByEmailAndStatusInOrderById(email, List.of("unsubscribed")).forEach(Subscription::clearEmail);
		}
	}

	private Subscription byManageToken(String token) {
		Subscription s = repository.findByManageToken(blank(token)).orElseThrow(SubscriptionService::notFound);
		if (!LIVE.contains(s.getStatus())) {
			throw notFound();
		}
		return s;
	}

	/** 토큰이 가리키는 이메일의 구독만 다룰 수 있다. */
	private Subscription owned(Subscription tokenOwner, long id) {
		return repository.findById(id)
				.filter(x -> tokenOwner.getEmail().equals(x.getEmail()) && LIVE.contains(x.getStatus()))
				.orElseThrow(SubscriptionService::notFound);
	}

	/** 같은 이메일의 신청이 개수 확인과 저장 사이에 끼어들지 않게 직렬화한다. */
	private void lock(String email) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:email))::text").param("email", email).query().singleRow();
	}

	private Map<String, Object> conditions(Subscription s) {
		return json.readValue(s.getConditions(), Map.class);
	}

	private static String email(String raw) {
		String e = raw == null ? "" : raw.strip().toLowerCase();
		if (e.length() > 254 || !EMAIL.matcher(e).matches()) {
			throw ApiException.invalidParam("email", "이메일 주소를 확인해 주세요.");
		}
		return e;
	}

	private static String mask(String email) {
		int at = email.indexOf('@');
		return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
	}

	private static String token() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	private static String blank(String token) {
		return token == null ? "" : token;
	}

	private static ApiException notFound() {
		return ApiException.notFound("이미 해지되었거나 찾을 수 없는 알림이에요.");
	}
}
