package app.cheongdam.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** API.md 1장의 오류 형식. */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ErrorBody(String code, String message, String field) {
	}

	public record ErrorResponse(ErrorBody error) {
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> handle(ApiException e) {
		return ResponseEntity.status(e.status())
				.body(new ErrorResponse(new ErrorBody(e.code(), e.getMessage(), e.field())));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("처리하지 못한 오류", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ErrorResponse(new ErrorBody("internal", "잠시 문제가 생겼어요.", null)));
	}
}
