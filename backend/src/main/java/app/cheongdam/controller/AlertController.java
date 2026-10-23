package app.cheongdam.controller;

import app.cheongdam.dto.AlertDtos.ConditionsRequest;
import app.cheongdam.dto.AlertDtos.ConditionsResponse;
import app.cheongdam.dto.AlertDtos.ConfirmResponse;
import app.cheongdam.dto.AlertDtos.EmailRequest;
import app.cheongdam.dto.AlertDtos.ManageResponse;
import app.cheongdam.dto.AlertDtos.StatusResponse;
import app.cheongdam.dto.AlertDtos.SubscribeRequest;
import app.cheongdam.dto.AlertDtos.TokenRequest;
import app.cheongdam.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API.md 5장. 토큰이 권한이라 모두 token을 받는다. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

	private final SubscriptionService service;

	public AlertController(SubscriptionService service) {
		this.service = service;
	}

	@PostMapping("/subscriptions")
	@ResponseStatus(HttpStatus.ACCEPTED)
	StatusResponse subscribe(@RequestBody SubscribeRequest body, HttpServletRequest request) {
		service.subscribe(body.email(), body.consent(), body.conditions(), ip(request));
		return new StatusResponse("confirmation_sent");
	}

	@PostMapping("/confirm")
	ConfirmResponse confirm(@RequestBody TokenRequest body) {
		return service.confirm(body.token());
	}

	@GetMapping("/manage")
	ManageResponse manage(@RequestParam(required = false) String token) {
		return service.manage(token);
	}

	@PutMapping("/manage/subscriptions/{id}")
	ConditionsResponse change(@PathVariable long id, @RequestParam(required = false) String token,
			@RequestBody ConditionsRequest body) {
		return service.changeConditions(token, id, body.conditions());
	}

	@DeleteMapping("/manage/subscriptions/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unsubscribeOne(@PathVariable long id, @RequestParam(required = false) String token) {
		service.unsubscribeOne(token, id);
	}

	@PostMapping("/manage/unsubscribe-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unsubscribeAll(@RequestParam(required = false) String token) {
		service.unsubscribeAll(token);
	}

	@PostMapping(value = "/unsubscribe", consumes = "application/json")
	ConditionsResponse unsubscribe(@RequestBody TokenRequest body) {
		return service.unsubscribeByLink(body.token());
	}

	/** 메일 앱의 원클릭 해지(RFC 8058)는 form 본문으로 보내고 token은 쿼리에 있다. */
	@PostMapping("/unsubscribe")
	ConditionsResponse unsubscribeFromMailApp(@RequestParam(required = false) String token) {
		return service.unsubscribeByLink(token);
	}

	@PostMapping("/manage-link")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void manageLink(@RequestBody EmailRequest body, HttpServletRequest request) {
		service.sendManageLink(body.email(), ip(request));
	}

	/** Nginx가 X-Real-IP를 직접 넣는다. 백엔드 포트는 밖으로 열려 있지 않다. */
	private static String ip(HttpServletRequest request) {
		String real = request.getHeader("X-Real-IP");
		return real != null && !real.isBlank() ? real : request.getRemoteAddr();
	}
}
