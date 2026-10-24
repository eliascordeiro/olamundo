package com.example.olamundo.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthRateLimiter {
	private static final Duration WINDOW = Duration.ofMinutes(15);
	private final Map<String, Attempts> attempts = new HashMap<>();
	private final Clock clock;

	public AuthRateLimiter() {
		this(Clock.systemUTC());
	}

	AuthRateLimiter(Clock clock) {
		this.clock = clock;
	}

	public synchronized void check(String address) {
		Instant now = clock.instant();
		attempts.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
		Attempts previous = attempts.get(address);
		if ((previous != null && previous.count() >= 20)
				|| (previous == null && attempts.size() >= 10_000)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"Muitas tentativas. Aguarde 15 minutos antes de tentar novamente.");
		}
		attempts.put(address, previous == null
				? new Attempts(1, now.plus(WINDOW))
				: new Attempts(previous.count() + 1, previous.expiresAt()));
	}

	private record Attempts(int count, Instant expiresAt) {
	}
}
