package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.olamundo.MongoIntegrationTest;
import com.example.olamundo.auth.MongoSessionRepository;
import com.example.olamundo.auth.UserAccount;
import com.example.olamundo.auth.UserAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {"app.api-docs.enabled=true", "springdoc.api-docs.enabled=true",
				"springdoc.swagger-ui.enabled=true"})
abstract class StoreApiTestSupport extends MongoIntegrationTest {
	@LocalServerPort int port;
	@Autowired ObjectMapper json;
	@Autowired UserAccountRepository users;
	@Autowired MongoSessionRepository sessions;
	@Autowired PasswordEncoder passwords;
	@Autowired JdbcClient jdbc;

	final List<Actor> actors = new ArrayList<>();
	final List<UUID> createdStores = new ArrayList<>();

	Actor actor() throws Exception {
		String email = "store-test-" + UUID.randomUUID() + "@example.com";
		String password = "a-store-test-passphrase";
		var account = users.insert(new UserAccount(null, "Store Tester", email,
				passwords.encode(password), Instant.now()));
		var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		var actor = new Actor(account, cookies, HttpClient.newBuilder().cookieHandler(cookies)
				.followRedirects(HttpClient.Redirect.NEVER).build());
		actors.add(actor);
		assertThat(post(actor, "/api/auth/login", Map.of("email", email, "password", password)).statusCode())
				.isEqualTo(200);
		return actor;
	}

	JsonNode createStore(Actor actor, String name) throws Exception {
		var response = post(actor, "/api/stores", Map.of("name", name));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		var store = json.readTree(response.body());
		createdStores.add(UUID.fromString(store.get("id").asText()));
		return store;
	}

	HttpResponse<String> get(Actor actor, String path) throws Exception {
		return actor.client().send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	HttpResponse<String> post(Actor actor, String path, Map<String, ?> body) throws Exception {
		return post(actor, path, body, true);
	}

	HttpResponse<String> post(Actor actor, String path, Map<String, ?> body, boolean csrf) throws Exception {
		var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
		if (csrf) {
			request.header("X-CSRF-TOKEN", json.readTree(get(actor, "/api/auth/csrf").body()).get("token").asText());
		}
		return actor.client().send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	@AfterEach
	void cleanupStoreData() {
		for (UUID id : createdStores) {
			jdbc.sql("DELETE FROM catalog_products WHERE store_id = :id").param("id", id).update();
			jdbc.sql("DELETE FROM store_memberships WHERE store_id = :id").param("id", id).update();
			jdbc.sql("DELETE FROM stores WHERE id = :id").param("id", id).update();
		}
		for (Actor actor : actors) {
			users.deleteById(actor.account().id());
			actor.cookies().getCookieStore().getCookies().stream()
					.filter(cookie -> cookie.getName().equals("BUSINESS_SESSION") && !cookie.getValue().isEmpty())
					.forEach(cookie -> sessions.deleteById(
							new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8)));
		}
	}

	record Actor(UserAccount account, CookieManager cookies, HttpClient client) {
	}
}
