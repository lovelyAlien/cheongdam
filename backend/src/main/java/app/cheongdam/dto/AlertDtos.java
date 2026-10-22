package app.cheongdam.dto;

import java.util.List;
import java.util.Map;

/** API.md 5장의 요청·응답. 필드 이름은 snake_case로 직렬화된다. */
public final class AlertDtos {

	private AlertDtos() {
	}

	public record SubscribeRequest(String email, Boolean consent, Map<String, Object> conditions) {
	}

	public record TokenRequest(String token) {
	}

	public record ConditionsRequest(Map<String, Object> conditions) {
	}

	public record EmailRequest(String email) {
	}

	public record StatusResponse(String status) {
	}

	public record ConfirmResponse(Map<String, Object> conditions, long matchCount) {
	}

	public record ConditionsResponse(Map<String, Object> conditions) {
	}

	public record ManageResponse(String emailMasked, List<ManagedSubscription> subscriptions) {
	}

	public record ManagedSubscription(long id, Map<String, Object> conditions, String status) {
	}
}
