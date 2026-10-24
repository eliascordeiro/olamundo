package com.example.olamundo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import com.example.olamundo.MongoIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTests extends MongoIntegrationTest {
	@LocalServerPort int port;
	@Autowired ObjectMapper json;
	@Autowired UserAccountRepository users;
	@Autowired MongoSessionRepository sessions;
	@Autowired MongoTemplate mongo;
	@Autowired PasswordEncoder encoder;

	private final String email = "test-" + UUID.randomUUID() + "@example.com";
	private final String password = "an-exclusive-passphrase";
	private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
	private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
	private String messageId;

	@AfterEach
	void cleanup() {
		users.findByEmail(email).ifPresent(users::delete);
		for (var cookie : cookies.getCookieStore().getCookies()) {
			if (cookie.getName().equals("BUSINESS_SESSION") && !cookie.getValue().isEmpty()) {
				sessions.deleteById(new String(Base64.getDecoder().decode(cookie.getValue()),
						java.nio.charset.StandardCharsets.UTF_8));
			}
		}
		if (messageId != null) {
			mongo.remove(org.springframework.data.mongodb.core.query.Query.query(
					org.springframework.data.mongodb.core.query.Criteria.where("_id").is(messageId)), "mensagens");
		}
	}

	@Test
	void registrationLoginPersistenceAndLogout() throws Exception {
		assertThat(get("/api/auth/me").statusCode()).isEqualTo(401);
		assertThat(get("/api/mensagens").statusCode()).isEqualTo(401);
		register();
		var account = users.findByEmail(email).orElseThrow();
		assertThat(account.passwordHash()).isNotEqualTo(password);
		assertThat(encoder.matches(password, account.passwordHash())).isTrue();

		String anonymousCookie = sessionCookie();
		var response = post("/api/auth/login", Map.of("email", email.toUpperCase(), "password", password), true);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).doesNotContain("password", "Hash");
		assertThat(sessionCookie()).isNotEqualTo(anonymousCookie);
		assertThat(response.headers().allValues("set-cookie").toString()).contains("HttpOnly", "SameSite=Lax");
		assertThat(get("/api/auth/me").body()).contains(email, "Test User");
		assertThat(get("/api/mensagens").statusCode()).isEqualTo(200);

		var message = post("/api/mensagens", Map.of("texto", "integration-" + email), true);
		assertThat(message.statusCode()).isEqualTo(201);
		messageId = json.readTree(message.body()).get("id").asText();
		assertThat(get("/api/mensagens").body()).contains(messageId, "integration-" + email);
		String authenticatedId = decodedSessionId();
		assertThat(sessions.findById(authenticatedId)).isNotNull();
		assertThat(post("/api/auth/logout", Map.of(), true).statusCode()).isEqualTo(204);
		assertThat(sessions.findById(authenticatedId)).isNull();
		assertThat(get("/api/auth/me").statusCode()).isEqualTo(401);
	}

	@Test
	void rejectsInvalidCredentialsMissingCsrfAndInvalidInput() throws Exception {
		assertThat(post("/api/auth/register", Map.of("name", "Test User", "email", email,
				"password", password), false).statusCode()).isEqualTo(403);
		assertThat(users.findByEmail(email)).isEmpty();
		assertThat(post("/api/auth/register", Map.of("name", " ", "email", email,
				"password", "short"), true).statusCode()).isEqualTo(400);
		register();
		assertThat(post("/api/auth/login", Map.of("email", email, "password", "wrong-password"),
				true).statusCode()).isEqualTo(401);
		assertThat(get("/api/auth/me").statusCode()).isEqualTo(401);
		assertThat(post("/api/auth/login", Map.of("email", email, "password", "á".repeat(40)),
				true).statusCode()).isEqualTo(400);
		assertThat(post("/api/auth/register", Map.of("name", "Test User", "email", email.toUpperCase(),
				"password", password), true).statusCode()).isEqualTo(409);
		assertThat(users.findByEmail(email)).isPresent();
	}

	@Test
	void enforcesSessionExpiryAndRejectsUnapprovedOrigins() throws Exception {
		register();
		assertThat(post("/api/auth/login", Map.of("email", email, "password", password), true)
				.statusCode()).isEqualTo(200);
		String id = decodedSessionId();
		var session = sessions.findById(id);
		session.setLastAccessedTime(Instant.now().minusSeconds(1801));
		sessions.save(session);
		assertThat(get("/api/auth/me").statusCode()).isEqualTo(401);
		assertThat(sessions.findById(id)).isNull();
		var corsResponse = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf"))
				.header("Origin", "https://untrusted.example").GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(corsResponse.statusCode()).isEqualTo(403);
	}

	private void register() throws Exception {
		assertThat(post("/api/auth/register", Map.of("name", "Test User", "email", email,
				"password", password), true).statusCode()).isEqualTo(201);
	}

	private HttpResponse<String> get(String path) throws Exception {
		return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, Map<String, String> body, boolean csrf) throws Exception {
		var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
		if (csrf) {
			JsonNode token = json.readTree(get("/api/auth/csrf").body());
			request.header("X-CSRF-TOKEN", token.get("token").asText());
		}
		return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
	private String sessionCookie() {
		return cookies.getCookieStore().getCookies().stream()
				.filter(cookie -> cookie.getName().equals("BUSINESS_SESSION"))
				.findFirst().orElseThrow().getValue();
	}
	private String decodedSessionId() {
		return new String(Base64.getDecoder().decode(sessionCookie()), java.nio.charset.StandardCharsets.UTF_8);
	}
}
