package com.example.olamundo.auth;

import java.nio.charset.StandardCharsets;
import java.security.Principal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
	private final AuthService service;
	private final AuthRateLimiter rateLimiter;
	private final HttpSessionSecurityContextRepository contextRepository;

	public AuthController(AuthService service, AuthRateLimiter rateLimiter,
			HttpSessionSecurityContextRepository contextRepository) {
		this.service = service;
		this.rateLimiter = rateLimiter;
		this.contextRepository = contextRepository;
	}

	@GetMapping("/csrf")
	public CsrfView csrf(CsrfToken token) {
		return new CsrfView(token.getToken());
	}

	@PostMapping("/register")
	@SecurityRequirement(name = "csrf")
	@ResponseStatus(HttpStatus.CREATED)
	public void register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
		rateLimiter.check(request.getRemoteAddr());
		service.register(body.name(), body.email(), body.password());
	}

	@PostMapping("/login")
	@SecurityRequirement(name = "csrf")
	public AuthService.UserView login(@Valid @RequestBody LoginRequest body,
			HttpServletRequest request, HttpServletResponse response) {
		rateLimiter.check(request.getRemoteAddr());
		var authentication = service.authenticate(body.email(), body.password());
		new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response);
		new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository())
				.onAuthentication(authentication, request, response);
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
		return service.currentUser(authentication.getName());
	}

	@GetMapping("/me")
	public AuthService.UserView me(Principal principal) {
		return service.currentUser(principal.getName());
	}

	public record CsrfView(String token) {
	}

	public record LoginRequest(
			@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(max = 72) String password) {

		@AssertTrue(message = "A senha deve ter no maximo 72 bytes.")
		public boolean isPasswordWithinByteLimit() {
			return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
		}
	}

	public record RegisterRequest(
			@NotBlank @Size(min = 2, max = 80) String name,
			@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 12, max = 72) String password) {

		@AssertTrue(message = "A senha deve ter no maximo 72 bytes.")
		public boolean isPasswordWithinByteLimit() {
			return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
		}

		@AssertTrue(message = "Informe um nome com pelo menos dois caracteres.")
		public boolean isNameValid() {
			return name == null || name.strip().length() >= 2;
		}
	}
}
