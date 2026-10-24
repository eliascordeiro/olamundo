package com.example.olamundo.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AuthRateLimiterTests {
	@Test
	void capsAttemptsPerAddressAndAllowsNewWindow() {
		var clock = new MutableClock();
		var limiter = new AuthRateLimiter(clock);
		for (int i = 0; i < 20; i++) {
			limiter.check("test-client");
		}
		assertThatThrownBy(() -> limiter.check("test-client"))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("429");
		limiter.check("independent-client");
		clock.now = clock.now.plusSeconds(900);
		limiter.check("test-client");
	}

	private static class MutableClock extends Clock {
		private Instant now = Instant.parse("2026-01-01T00:00:00Z");
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now; }
	}
}
