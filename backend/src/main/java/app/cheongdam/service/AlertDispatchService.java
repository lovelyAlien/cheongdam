package app.cheongdam.service;

import app.cheongdam.domain.Notice;
import app.cheongdam.domain.NoticeChange;
import app.cheongdam.domain.NoticeUnit;
import app.cheongdam.domain.Subscription;
import app.cheongdam.dto.NoticeFilter;
import app.cheongdam.dto.SubscriptionConditions;
import app.cheongdam.mail.AlertMailer;
import app.cheongdam.mail.DigestMail;
import app.cheongdam.mail.DigestMail.Change;
import app.cheongdam.mail.DigestMail.CorrectionEntry;
import app.cheongdam.mail.DigestMail.NewEntry;
import app.cheongdam.repository.NoticeChangeRepository;
import app.cheongdam.repository.NoticeRepository;
import app.cheongdam.repository.NoticeSpecifications;
import app.cheongdam.repository.NoticeUnitRepository;
import app.cheongdam.repository.SubscriptionRepository;
import app.cheongdam.util.ConditionsSummary;
import app.cheongdam.util.NoticeStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알림 대상 매칭과 발송(D-28, ALERTS.md 4장). 구독마다 보낼 공고를 {@code alert_outbox}에 쌓고, 쌓인 것을 읽어 보낸다.
 * 구독 하나가 실패해도 다른 구독은 계속하도록 구독마다 트랜잭션을 나눈다.
 */
@Service
public class AlertDispatchService {

	private static final Logger log = LoggerFactory.getLogger(AlertDispatchService.class);

	static final int MAX_NEW_IN_MAIL = 10;
	/** P-20: 일시 실패는 1시간 뒤 1회 재시도 */
	static final int MAX_ATTEMPTS = 2;
	static final Duration RETRY_AFTER = Duration.ofHours(1);

	private final SubscriptionRepository subscriptionRepository;
	private final NoticeRepository noticeRepository;
	private final NoticeUnitRepository unitRepository;
	private final NoticeChangeRepository changeRepository;
	private final AlertMailer mailer;
	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final Clock clock;
	private final TransactionTemplate tx;

	public AlertDispatchService(SubscriptionRepository subscriptionRepository, NoticeRepository noticeRepository,
			NoticeUnitRepository unitRepository, NoticeChangeRepository changeRepository, AlertMailer mailer,
			JdbcClient jdbc, JsonMapper json, Clock clock, PlatformTransactionManager txManager) {
		this.subscriptionRepository = subscriptionRepository;
		this.noticeRepository = noticeRepository;
		this.unitRepository = unitRepository;
		this.changeRepository = changeRepository;
		this.mailer = mailer;
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
		this.tx = new TransactionTemplate(txManager);
	}

	// ---- 매칭: 구독마다 보낼 공고를 발송 대기에 쌓는다 ----

	/**
	 * 구독 중인 구독마다 직전 발송 이후 새로 수집됐거나 정정된 공고를 찾아 쌓는다.
	 * {@code cutoff} 이후에 수집된 것은 다음 번에 보낸다. 하루에 몇 번 불러도 같은 결과다(구독당 하루 한 통, P-19).
	 *
	 * @return 쌓은 메일 수
	 */
	public int enqueue(OffsetDateTime cutoff) {
		LocalDate today = LocalDate.now(clock);
		int queued = 0;
		for (Subscription s : subscriptionRepository.findByStatus("active")) {
			try {
				if (Boolean.TRUE.equals(tx.execute(st -> enqueueOne(s.getId(), today, cutoff)))) {
					queued++;
				}
			} catch (RuntimeException e) {
				log.error("알림 대상 매칭에 실패했어요. subscription={}", s.getId(), e);
			}
		}
		return queued;
	}

	private boolean enqueueOne(long subscriptionId, LocalDate today, OffsetDateTime cutoff) {
		Subscription s = subscriptionRepository.findById(subscriptionId).orElseThrow();
		if (!s.getStatus().equals("active") || s.getEmail() == null) {
			return false;
		}
		boolean already = jdbc.sql("SELECT count(*) FROM alert_outbox WHERE subscription_id = :s AND send_date = :d")
				.param("s", subscriptionId).param("d", today).query(Integer.class).single() > 0;
		if (already) {
			return false;
		}
		// 직전 발송 시각(발송 때 이 메일을 쌓은 시각으로 기록한다) 이후. 아직 보낸 적이 없으면 확인한 시각 이후
		OffsetDateTime baseline = s.getLastSentAt() != null ? s.getLastSentAt() : s.getConfirmedAt();
		NoticeFilter filter = SubscriptionConditions.toFilter(conditions(s));
		Set<String> sent = jdbc.sql("SELECT house_manage_no || '|' || version || '|' || kind FROM alert_log "
				+ "WHERE subscription_id = :s").param("s", subscriptionId).query(String.class).set();

		// 새 공고(P-18): 접수 예정·접수 중
		List<Notice> fresh = noticeRepository
				.findAll(NoticeSpecifications.matching(filter, today, Set.of(), false)
						.and(between("firstCollectedAt", baseline, cutoff)))
				.stream().filter(n -> !sent.contains(key(n, "new")))
				.sorted(Comparator.comparing(Notice::getRceptStart, Comparator.nullsLast(Comparator.naturalOrder()))
						.thenComparing(Notice::getHouseManageNo))
				.toList();
		Set<String> freshNos = fresh.stream().map(Notice::getHouseManageNo).collect(Collectors.toSet());

		// 정정(P-18): 접수 상태와 상관없이 계약 종료일이 지나지 않은 공고. 같은 번에 새 공고로 나가는 것은 새 공고에 최신 내용이 담긴다
		List<Notice> corrected = noticeRepository
				.findAll(NoticeSpecifications.matching(filter, today, Set.of("status"), false)
						.and(between("lastChangedAt", baseline, cutoff)).and(contractNotEnded(today)))
				.stream().filter(n -> !freshNos.contains(n.getHouseManageNo()) && !sent.contains(key(n, "correction")))
				.sorted(Comparator.comparing(Notice::getHouseManageNo)).toList();

		if (fresh.isEmpty() && corrected.isEmpty()) {
			return false;
		}
		String mailType = corrected.isEmpty() ? "new" : fresh.isEmpty() ? "correction" : "combined";
		long outboxId = jdbc.sql("INSERT INTO alert_outbox (subscription_id, send_date, mail_type, created_at) "
				+ "VALUES (:s, :d, :t, :c) RETURNING id").param("s", subscriptionId).param("d", today)
				.param("t", mailType).param("c", cutoff).query(Long.class).single();
		fresh.forEach(n -> addNotice(outboxId, n, "new"));
		corrected.forEach(n -> addNotice(outboxId, n, "correction"));
		return true;
	}

	private void addNotice(long outboxId, Notice n, String kind) {
		jdbc.sql("INSERT INTO alert_outbox_notice (outbox_id, house_manage_no, version, kind) "
				+ "VALUES (:o, :n, :v, :k)").param("o", outboxId).param("n", n.getHouseManageNo())
				.param("v", n.getCurrentVersion()).param("k", kind).update();
	}

	private static String key(Notice n, String kind) {
		return n.getHouseManageNo() + "|" + n.getCurrentVersion() + "|" + kind;
	}

	private static Specification<Notice> between(String field, OffsetDateTime after, OffsetDateTime until) {
		return (root, query, cb) -> cb.and(cb.greaterThan(root.<OffsetDateTime>get(field), after),
				cb.lessThanOrEqualTo(root.<OffsetDateTime>get(field), until));
	}

	private static Specification<Notice> contractNotEnded(LocalDate today) {
		return (root, query, cb) -> cb.or(cb.isNull(root.get("contractEnd")),
				cb.greaterThanOrEqualTo(root.<LocalDate>get("contractEnd"), today));
	}

	// ---- 발송: 쌓인 것을 읽어 보낸다 ----

	private record Outbox(long id, long subscriptionId, String mailType, int attempts, OffsetDateTime createdAt) {
	}

	private record Queued(String houseManageNo, int version, String kind) {
	}

	/** 보낼 때가 된 대기 메일을 보낸다. 일시 실패는 1시간 뒤 한 번 더 시도한다(P-20). @return 보낸 메일 수 */
	public int sendDue(OffsetDateTime now) {
		List<Long> ids = jdbc.sql("SELECT id FROM alert_outbox WHERE status = 'pending' AND send_date <= :d "
				+ "AND (next_attempt_at IS NULL OR next_attempt_at <= :now) ORDER BY id")
				.param("d", LocalDate.now(clock)).param("now", now).query(Long.class).list();
		int sent = 0;
		for (long id : ids) {
			try {
				if (Boolean.TRUE.equals(tx.execute(st -> sendOne(id, now)))) {
					sent++;
				}
			} catch (RuntimeException e) {
				log.error("알림 발송 처리에 실패했어요. outbox={}", id, e);
			}
		}
		return sent;
	}

	private boolean sendOne(long id, OffsetDateTime now) {
		Outbox ob = jdbc.sql("SELECT id, subscription_id, mail_type, attempts, created_at FROM alert_outbox "
				+ "WHERE id = :id AND status = 'pending' FOR UPDATE")
				.param("id", id).query((rs, n) -> new Outbox(rs.getLong("id"), rs.getLong("subscription_id"),
						rs.getString("mail_type"), rs.getInt("attempts"),
						rs.getObject("created_at", OffsetDateTime.class)))
				.optional().orElse(null);
		if (ob == null) {
			return false;
		}
		Subscription s = subscriptionRepository.findById(ob.subscriptionId()).orElse(null);
		if (s == null || !s.getStatus().equals("active") || s.getEmail() == null) {
			jdbc.sql("UPDATE alert_outbox SET status = 'failed', error = '구독이 더 이상 구독 중이 아니에요' WHERE id = :id")
					.param("id", id).update();
			return false;
		}
		List<Queued> queued = jdbc.sql("SELECT house_manage_no, version, kind FROM alert_outbox_notice WHERE outbox_id = :id")
				.param("id", id).query((rs, n) -> new Queued(rs.getString("house_manage_no"), rs.getInt("version"),
						rs.getString("kind")))
				.list();
		try {
			mailer.digest(s.getEmail(), compose(s, queued));
		} catch (RuntimeException e) {
			recordFailure(ob, now, e);
			return false;
		}
		// 메일을 보낸 뒤 기록하다 실패하면 다음에 한 번 더 보낼 수 있다(적어도 한 번 전달)
		queued.forEach(q -> jdbc.sql("INSERT INTO alert_log (subscription_id, house_manage_no, version, kind) "
				+ "VALUES (:s, :n, :v, :k) ON CONFLICT DO NOTHING")
				.param("s", s.getId()).param("n", q.houseManageNo()).param("v", q.version()).param("k", q.kind())
				.update());
		jdbc.sql("UPDATE alert_outbox SET status = 'sent', sent_at = :now, attempts = attempts + 1 WHERE id = :id")
				.param("now", now).param("id", id).update();
		// 다음 번 매칭의 기준. 이 메일을 쌓은 시각까지의 공고를 알렸다
		jdbc.sql("UPDATE subscription SET last_sent_at = GREATEST(coalesce(last_sent_at, :c), :c) WHERE id = :s")
				.param("c", ob.createdAt()).param("s", s.getId()).update();
		return true;
	}

	private void recordFailure(Outbox ob, OffsetDateTime now, RuntimeException e) {
		int attempts = ob.attempts() + 1;
		boolean giveUp = attempts >= MAX_ATTEMPTS;
		String error = String.valueOf(e.getMessage());
		jdbc.sql("UPDATE alert_outbox SET attempts = :a, status = :st, next_attempt_at = :next, error = :err "
				+ "WHERE id = :id").param("a", attempts).param("st", giveUp ? "failed" : "pending")
				.param("next", giveUp ? null : now.plus(RETRY_AFTER))
				.param("err", error.substring(0, Math.min(error.length(), 500))).param("id", ob.id()).update();
		log.warn("알림 메일을 보내지 못했어요. outbox={} attempts={} 포기={}", ob.id(), attempts, giveUp, e);
	}

	// ---- 메일 내용 ----

	private DigestMail compose(Subscription s, List<Queued> queued) {
		LocalDate today = LocalDate.now(clock);
		Map<String, Object> conditions = conditions(s);
		String summary = ConditionsSummary.of(conditions);

		Map<String, Notice> notices = noticeRepository
				.findAllById(queued.stream().map(Queued::houseManageNo).distinct().toList()).stream()
				.collect(Collectors.toMap(Notice::getHouseManageNo, n -> n));
		Map<String, List<NoticeUnit>> units = unitRepository.findByIdHouseManageNoIn(notices.keySet()).stream()
				.collect(Collectors.groupingBy(u -> u.getId().getHouseManageNo()));

		List<NewEntry> all = new ArrayList<>(queued.stream().filter(q -> q.kind().equals("new"))
				.map(q -> notices.get(q.houseManageNo())).filter(Objects::nonNull)
				.sorted(Comparator.comparing(Notice::getRceptStart, Comparator.nullsLast(Comparator.naturalOrder()))
						.thenComparing(Notice::getHouseManageNo))
				.map(n -> newEntry(n, units.getOrDefault(n.getHouseManageNo(), List.of()), today)).toList());
		List<NewEntry> shown = all.size() > MAX_NEW_IN_MAIL ? all.subList(0, MAX_NEW_IN_MAIL) : all;

		List<CorrectionEntry> corrections = queued.stream().filter(q -> q.kind().equals("correction"))
				.filter(q -> notices.containsKey(q.houseManageNo()))
				.sorted(Comparator.comparing(Queued::houseManageNo))
				.map(q -> new CorrectionEntry(q.houseManageNo(), notices.get(q.houseManageNo()).getName(),
						changes(q.houseManageNo(), q.version())))
				.toList();

		return new DigestMail(subject(summary, all.size(), corrections), conditions, summary, corrections, shown,
				all.size() - shown.size(), s.getManageToken());
	}

	/** M-02 `새 공고 2건 · 서울·경기 신혼부부`, M-03 `정정 공고 1건 · 단지명`, 합본 `정정 공고 1건·새 공고 2건`. */
	private static String subject(String summary, int newCount, List<CorrectionEntry> corrections) {
		if (corrections.isEmpty()) {
			return "[청담] 새 공고 " + newCount + "건 · " + ConditionsSummary.shorten(summary);
		}
		if (newCount > 0) {
			return "[청담] 정정 공고 " + corrections.size() + "건·새 공고 " + newCount + "건";
		}
		String first = corrections.get(0).name();
		return "[청담] 정정 공고 " + corrections.size() + "건 · "
				+ (corrections.size() == 1 ? first : first + " 외 " + (corrections.size() - 1) + "건");
	}

	private List<Change> changes(String houseManageNo, int version) {
		return changeRepository.findByHouseManageNoOrderByVersionDescIdAsc(houseManageNo).stream()
				.filter(c -> c.getVersion() == version)
				.map((NoticeChange c) -> new Change(c.getField(), c.getBeforeValue(), c.getAfterValue())).toList();
	}

	private static NewEntry newEntry(Notice n, List<NoticeUnit> units, LocalDate today) {
		List<Integer> prices = units.stream().map(NoticeUnit::getTopPriceMan).filter(Objects::nonNull).toList();
		List<BigDecimal> areas = units.stream().map(NoticeUnit::getExclusiveArea).filter(Objects::nonNull).toList();
		return new NewEntry(n.getHouseManageNo(), n.getName(), n.getNoticeType(), n.getHouseKind(), n.getRegion(),
				n.getAddress(), NoticeStatus.of(n.getRceptStart(), n.getRceptEnd(), today), n.getRceptStart(),
				n.getRceptEnd(), prices.stream().min(Integer::compare).orElse(null),
				prices.stream().max(Integer::compare).orElse(null),
				areas.stream().min(BigDecimal::compareTo).map(BigDecimal::doubleValue).orElse(null),
				areas.stream().max(BigDecimal::compareTo).map(BigDecimal::doubleValue).orElse(null));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> conditions(Subscription s) {
		return json.readValue(s.getConditions(), Map.class);
	}
}
