package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.olamundo.auth.UserAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = "app.platform.super-emails=platform-super@example.com")
class PlatformOwnerIntegrationTests extends StoreApiTestSupport {
	private static final String SUPER_EMAIL = "platform-super@example.com";
	private static final String PASSWORD = "a-platform-test-passphrase";
	private static final UUID MURIMEL = UUID.fromString("00000000-0000-4000-8000-00000000a001");

	private final List<UUID> owners = new ArrayList<>();
	private final List<String> createdEmails = new ArrayList<>();

	@AfterEach
	void cleanupPlatformData() {
		for (UUID id : owners) {
			jdbc.sql("UPDATE stores SET owner_id = NULL WHERE owner_id = :id").param("id", id).update();
			jdbc.sql("DELETE FROM customer_legacy_codes WHERE owner_id = :id").param("id", id).update();
			jdbc.sql("DELETE FROM customers WHERE owner_id = :id").param("id", id).update();
			jdbc.sql("DELETE FROM platform_owner_invites WHERE owner_id = :id").param("id", id).update();
			jdbc.sql("DELETE FROM platform_owners WHERE id = :id").param("id", id).update();
		}
		createdEmails.forEach(email -> users.findByEmail(email).ifPresent(users::delete));
	}

	@Test
	void onlySuperReachesPlatformEndpointsAndWritesRequireCsrf() throws Exception {
		var platformSuper = superActor();
		var regular = actor();
		assertThat(get(regular, "/api/platform/owners").statusCode()).isEqualTo(403);
		assertThat(post(regular, "/api/platform/owners", Map.of("requestId", UUID.randomUUID(), "name", "Negado"))
				.statusCode()).isEqualTo(403);
		assertThat(post(regular, "/api/platform/owners/" + MURIMEL + "/invite", Map.of("email", "x@example.com"))
				.statusCode()).isEqualTo(403);
		var anonymous = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(uri("/api/platform/owners")).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(anonymous.statusCode()).isEqualTo(401);
		assertThat(post(platformSuper, "/api/platform/owners", Map.of("requestId", UUID.randomUUID(), "name", "Sem CSRF"),
				false).statusCode()).isEqualTo(403);
		assertThat(json.readTree(get(platformSuper, "/api/auth/me").body()).get("platformAdmin").asBoolean()).isTrue();
		assertThat(json.readTree(get(regular, "/api/auth/me").body()).get("platformAdmin").asBoolean()).isFalse();
	}

	@Test
	void legacyOwnerIsSeededWithoutAccountOrEmail() throws Exception {
		var platformSuper = superActor();
		var page = json.readTree(get(platformSuper, "/api/platform/owners?size=100").body());
		JsonNode murimel = null;
		for (JsonNode owner : page.get("content")) {
			if (owner.get("id").asText().equals(MURIMEL.toString())) murimel = owner;
		}
		assertThat(murimel).isNotNull();
		assertThat(murimel.get("name").asText()).isEqualTo("Móveis Murimel");
		assertThat(murimel.get("status").asText()).isEqualTo("PENDING");
		assertThat(murimel.get("email").isNull()).isTrue();
		assertThat(murimel.get("userId").isNull()).isTrue();
	}

	@Test
	void inviteIsSingleUseExpiresAndNewInviteReplacesTheOldOne() throws Exception {
		var platformSuper = superActor();
		UUID request = UUID.randomUUID();
		String email = newEmail();
		var created = createOwner(platformSuper, request, "Loja Nova", email);
		assertThat(created.get("owner").get("status").asText()).isEqualTo("INVITED");
		String first = created.get("inviteToken").asText();
		assertThat(first).hasSizeGreaterThan(30);
		assertThat(jdbc.sql("SELECT count(*) FROM platform_owner_invites WHERE token_hash = :token")
				.param("token", first).query(Long.class).single()).isZero();

		var replay = createOwner(platformSuper, request, "Loja Nova", email);
		assertThat(replay.get("inviteToken").isNull()).isTrue();
		assertThat(post(platformSuper, "/api/platform/owners",
				Map.of("requestId", request, "name", "Outro nome", "email", email)).statusCode()).isEqualTo(409);

		var second = json.readTree(post(platformSuper, "/api/platform/owners/" + request + "/invite",
				Map.of("email", email)).body()).get("inviteToken").asText();
		assertThat(second).isNotEqualTo(first);
		assertThat(accept(first, "Dono Novo").statusCode()).isEqualTo(404);

		jdbc.sql("UPDATE platform_owner_invites SET expires_at = :past WHERE owner_id = :id")
				.param("past", java.sql.Timestamp.from(Instant.now().minusSeconds(60))).param("id", request).update();
		assertThat(accept(second, "Dono Novo").statusCode()).isEqualTo(404);

		String third = json.readTree(post(platformSuper, "/api/platform/owners/" + request + "/invite",
				Map.of("email", email)).body()).get("inviteToken").asText();
		assertThat(accept(third, "Dono Novo").statusCode()).isEqualTo(200);
		assertThat(accept(third, "Dono Novo").statusCode()).isEqualTo(404);

		var owner = owner(platformSuper, request);
		assertThat(owner.get("status").asText()).isEqualTo("ACTIVE");
		assertThat(owner.get("userId").asText()).isEqualTo(users.findByEmail(email).orElseThrow().id());
		assertThat(post(platformSuper, "/api/platform/owners/" + request + "/invite", Map.of("email", email))
				.statusCode()).isEqualTo(409);

		var invited = newClient();
		assertThat(post(invited, "/api/auth/login", Map.of("email", email, "password", PASSWORD)).statusCode())
				.isEqualTo(200);
		assertThat(get(invited, "/api/platform/owners").statusCode()).isEqualTo(403);
		assertThat(createStore(invited, "Primeira loja do proprietario").get("name").asText())
				.isEqualTo("Primeira loja do proprietario");
	}

	@Test
	void existingAccountBecomesOwnerImmediatelyAndCannotOwnTwoRecords() throws Exception {
		var platformSuper = superActor();
		var existing = plainActor();
		String email = existing.account().email();
		var created = createOwner(platformSuper, UUID.randomUUID(), "Conta Existente", email);
		assertThat(created.get("owner").get("status").asText()).isEqualTo("ACTIVE");
		assertThat(created.get("inviteToken").isNull()).isTrue();
		assertThat(created.get("owner").get("userId").asText()).isEqualTo(existing.account().id());

		assertThat(post(platformSuper, "/api/platform/owners",
				Map.of("requestId", UUID.randomUUID(), "name", "Duplicado", "email", email)).statusCode())
				.isEqualTo(409);
	}

	@Test
	void onlyActiveOwnersCreateStoresAndSessionReportsOwnerStatus() throws Exception {
		var plain = plainActor();
		var denied = post(plain, "/api/stores", Map.of("name", "Loja Negada"));
		assertThat(denied.statusCode()).isEqualTo(403);
		assertThat(get(plain, "/api/stores").body()).doesNotContain("Loja Negada");
		assertThat(json.readTree(get(plain, "/api/auth/me").body()).get("platformOwner").asBoolean()).isFalse();

		var platformSuper = superActor();
		assertThat(post(platformSuper, "/api/stores", Map.of("name", "Super nao opera")).statusCode()).isEqualTo(403);

		var owner = actor();
		assertThat(json.readTree(get(owner, "/api/auth/me").body()).get("platformOwner").asBoolean()).isTrue();
		assertThat(createStore(owner, "Loja do Proprietario").get("name").asText()).isEqualTo("Loja do Proprietario");
	}

	@Test
	void ownerWithoutEmailStaysPendingUntilInvited() throws Exception {
		var platformSuper = superActor();
		UUID id = UUID.randomUUID();
		var created = createOwner(platformSuper, id, "Sem E-mail", null);
		assertThat(created.get("owner").get("status").asText()).isEqualTo("PENDING");
		assertThat(created.get("inviteToken").isNull()).isTrue();
		String email = newEmail();
		var invited = json.readTree(post(platformSuper, "/api/platform/owners/" + id + "/invite",
				Map.of("email", email)).body());
		assertThat(invited.get("owner").get("status").asText()).isEqualTo("INVITED");
		assertThat(invited.get("owner").get("email").asText()).isEqualTo(email);
	}

	private JsonNode createOwner(Actor actor, UUID id, String name, String email) throws Exception {
		owners.add(id);
		var body = new java.util.HashMap<String, Object>(Map.of("requestId", id, "name", name));
		if (email != null) body.put("email", email);
		var response = post(actor, "/api/platform/owners", body);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return json.readTree(response.body());
	}

	private JsonNode owner(Actor actor, UUID id) throws Exception {
		for (JsonNode owner : json.readTree(get(actor, "/api/platform/owners?size=100").body()).get("content")) {
			if (owner.get("id").asText().equals(id.toString())) return owner;
		}
		throw new AssertionError("Owner not listed: " + id);
	}

	private HttpResponse<String> accept(String token, String name) throws Exception {
		var anonymous = newClient();
		return post(anonymous, "/api/platform/invites/accept", Map.of("token", token, "name", name, "password", PASSWORD));
	}

	private String newEmail() {
		String email = "owner-test-" + UUID.randomUUID() + "@example.com";
		createdEmails.add(email);
		return email;
	}

	private Actor newClient() {
		var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		return new Actor(new UserAccount("none", "none", "none", "none", Instant.now()), cookies,
				HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build());
	}

	private Actor superActor() throws Exception {
		var account = users.findByEmail(SUPER_EMAIL).orElseGet(() ->
				users.insert(new UserAccount(null, "Super", SUPER_EMAIL, passwords.encode(PASSWORD), Instant.now())));
		var client = newClient();
		var actor = new Actor(account, client.cookies(), client.client());
		actors.add(actor);
		assertThat(post(actor, "/api/auth/login", Map.of("email", SUPER_EMAIL, "password", PASSWORD)).statusCode())
				.isEqualTo(200);
		return actor;
	}
}
