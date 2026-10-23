package app.cheongdam.util;

import app.cheongdam.exception.ApiException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 한 시간 고정 창 카운터(API.md 5장, D-27).
 * shortcut: 서버 메모리라 VM 한 대에서만 맞고 재시작하면 초기화된다. 서버를 늘리거나 남용이 보이면 DB·Redis로.
 */
@Component
public class RateLimiter {

	private static final long WINDOW_MS = 3_600_000L;

	private record Window(long start, int count) {
	}

	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
	private final Clock clock;

	public RateLimiter(Clock clock) {
		this.clock = clock;
	}

	/** 이 창에서 {@code max}번을 넘기면 429. */
	public void check(String key, int max) {
		long now = clock.millis();
		if (windows.size() > 10_000) {
			windows.values().removeIf(w -> now - w.start >= WINDOW_MS);
		}
		Window w = windows.merge(key, new Window(now, 1),
				(old, fresh) -> now - old.start >= WINDOW_MS ? fresh : new Window(old.start, old.count + 1));
		if (w.count > max) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "잠시 뒤에 다시 시도해 주세요.", null);
		}
	}
}
