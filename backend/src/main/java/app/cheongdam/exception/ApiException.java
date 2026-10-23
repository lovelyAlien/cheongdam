package app.cheongdam.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final String field;

	public ApiException(HttpStatus status, String code, String message, String field) {
		super(message);
		this.status = status;
		this.code = code;
		this.field = field;
	}

	public static ApiException invalidParam(String field, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "invalid_param", message, field);
	}

	public static ApiException notFound(String message) {
		return new ApiException(HttpStatus.NOT_FOUND, "not_found", message, null);
	}

	public static ApiException conflict(String code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message, null);
	}

	public static ApiException gone(String code, String message) {
		return new ApiException(HttpStatus.GONE, code, message, null);
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public String field() {
		return field;
	}
}
