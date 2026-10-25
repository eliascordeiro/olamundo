package com.example.olamundo.auth;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ApiError> authentication(AuthenticationException exception) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(new ApiError("E-mail ou senha incorretos."));
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
			MethodArgumentTypeMismatchException.class})
	ResponseEntity<ApiError> validation(Exception exception) {
		String message = "Corpo da requisicao invalido.";
		if (exception instanceof MethodArgumentNotValidException validation) {
			message = "Confira os campos: " + validation.getBindingResult().getFieldErrors().stream()
					.map(error -> error.getField() + " - " + error.getDefaultMessage())
					.distinct().collect(java.util.stream.Collectors.joining("; "));
		}
		return ResponseEntity.badRequest().body(new ApiError(message));
	}

	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<ApiError> status(ResponseStatusException exception) {
		var builder = ResponseEntity.status(exception.getStatusCode());
		if (exception.getStatusCode().value() == 429) {
			builder.header("Retry-After", "900");
		}
		return builder.body(new ApiError(exception.getReason()));
	}

	@ExceptionHandler(DataAccessException.class)
	ResponseEntity<ApiError> database(DataAccessException exception) {
		log.error("Persistence operation failed ({})", exception.getClass().getSimpleName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(new ApiError("Servico temporariamente indisponivel. Tente novamente mais tarde."));
	}

	static void writeError(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"message\":\"" + message + "\"}");
	}

	public record ApiError(String message) {
	}
}
