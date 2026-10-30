package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
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

// Contexto proprio: o limitador de tentativas de login e por contexto e esta classe faz muitos logins.
@TestPropertySource(properties = "app.test.context=seller-access")
class SellerAccessIntegrationTests extends StoreApiTestSupport {
	private static final String PASSWORD = "a-seller-test-passphrase";
	private final List<String> invitedEmails = new ArrayList<>();

	@AfterEach
	void cleanupInvites() {
		for (String email : invitedEmails) {
			jdbc.sql("DELETE FROM seller_invites WHERE email = :email").param("email", email).update();
			users.findByEmail(email).ifPresent(users::delete);
		}
	}

	@Test
	void existingAccountGetsMembershipsAndLinkedSellersInEveryChosenStore() throws Exception {
		var owner = actor();
		var account = plainActor();
		String north = createStore(owner, "Acesso Norte").get("id").asText();
		String south = createStore(owner, "Acesso Sul").get("id").asText();
		String untouched = createStore(owner, "Acesso Intocada").get("id").asText();
		var seller = createSeller(owner, north, "Marina Multiloja");

		var response = grant(owner, north, seller, account.account().email(), 0,
				List.of(item(north, "STAFF"), item(south, "MANAGER")));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		var body = json.readTree(response.body());
		assertThat(body.get("inviteToken").isNull()).isTrue();
		assertThat(body.get("seller").get("userId").asText()).isEqualTo(account.account().id());
		assertThat(body.get("seller").get("accountEmail").asText()).isEqualTo(account.account().email());

		assertThat(json.readTree(get(account, "/api/stores/" + north).body()).get("role").asText()).isEqualTo("STAFF");
		assertThat(json.readTree(get(account, "/api/stores/" + south).body()).get("role").asText()).isEqualTo("MANAGER");
		assertThat(get(account, "/api/stores/" + untouched).statusCode()).isEqualTo(404);
		assertThat(get(account, "/api/stores/" + north + "/salespeople").statusCode()).isEqualTo(403);

		var southSellers = json.readTree(get(owner, "/api/stores/" + south + "/salespeople").body()).get("content");
		assertThat(southSellers).hasSize(1);
		assertThat(southSellers.get(0).get("name").asText()).isEqualTo("Marina Multiloja");
		assertThat(southSellers.get(0).get("userId").asText()).isEqualTo(account.account().id());

		var again = grant(owner, north, seller, account.account().email(), 1,
				List.of(item(north, "STAFF"), item(south, "MANAGER")));
		assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
		assertThat(json.readTree(get(owner, "/api/stores/" + south + "/salespeople").body()).get("content")).hasSize(1);
	}

	@Test
	void grantRejectsStoresTheOwnerDoesNotOwnAndInvalidRequestsWithoutSideEffects() throws Exception {
		var owner = actor();
		var stranger = actor();
		var account = plainActor();
		String north = createStore(owner, "Valida Norte").get("id").asText();
		String foreign = createStore(stranger, "Loja de Outro").get("id").asText();
		var seller = createSeller(owner, north, "Sem Efeito");
		String email = account.account().email();

		assertThat(grant(owner, north, seller, email, 0, List.of(item(north, "STAFF"), item(foreign, "STAFF")))
				.statusCode()).isEqualTo(404);
		assertThat(grant(owner, north, seller, email, 0, List.of(item(foreign, "STAFF"))).statusCode()).isEqualTo(400);
		assertThat(grant(owner, north, seller, email, 0, List.of(item(north, "OWNER"))).statusCode()).isEqualTo(400);
		assertThat(grant(owner, north, seller, email, 0, List.of(item(north, "STAFF"), item(north, "MANAGER")))
				.statusCode()).isEqualTo(400);
		assertThat(grant(owner, north, seller, email, 0, List.of()).statusCode()).isEqualTo(400);
		assertThat(get(account, "/api/stores/" + north).statusCode()).isEqualTo(404);

		assertThat(grant(stranger, north, seller, email, 0, List.of(item(north, "STAFF"))).statusCode()).isEqualTo(404);
		assertThat(post(owner, "/api/stores/" + north + "/salespeople/" + seller.asText() + "/access",
				Map.of("email", email, "version", 0, "stores", List.of(item(north, "STAFF"))), false).statusCode())
				.isEqualTo(403);
	}

	@Test
	void staleVersionIsRejectedAndLeavesNoMembership() throws Exception {
		var owner = actor();
		var account = plainActor();
		String north = createStore(owner, "Versao Norte").get("id").asText();
		String south = createStore(owner, "Versao Sul").get("id").asText();
		var seller = createSeller(owner, north, "Versao");
		var stale = grant(owner, north, seller, account.account().email(), 9,
				List.of(item(north, "STAFF"), item(south, "STAFF")));
		assertThat(stale.statusCode()).isEqualTo(409);
		assertThat(get(account, "/api/stores/" + north).statusCode()).isEqualTo(404);
		assertThat(get(account, "/api/stores/" + south).statusCode()).isEqualTo(404);
	}

	@Test
	void sellerWithoutAccountReceivesSingleUseInviteThatAppliesAllGrants() throws Exception {
		var owner = actor();
		String north = createStore(owner, "Convite Norte").get("id").asText();
		String south = createStore(owner, "Convite Sul").get("id").asText();
		var seller = createSeller(owner, north, "Novo Vendedor");
		String email = "seller-test-" + UUID.randomUUID() + "@example.com";
		invitedEmails.add(email);

		var created = json.readTree(grant(owner, north, seller, email, 0,
				List.of(item(north, "STAFF"), item(south, "MANAGER"))).body());
		String first = created.get("inviteToken").asText();
		assertThat(first).hasSizeGreaterThan(30);
		assertThat(users.findByEmail(email)).isEmpty();
		assertThat(created.get("seller").get("userId").isNull()).isTrue();
		assertThat(jdbc.sql("SELECT count(*) FROM seller_invites WHERE token_hash = :token").param("token", first)
				.query(Long.class).single()).isZero();

		String second = json.readTree(grant(owner, north, seller, email, 0,
				List.of(item(north, "STAFF"), item(south, "MANAGER"))).body()).get("inviteToken").asText();
		assertThat(second).isNotEqualTo(first);
		assertThat(accept(first).statusCode()).isEqualTo(404);

		jdbc.sql("UPDATE seller_invites SET expires_at = :past WHERE email = :email")
				.param("past", java.sql.Timestamp.from(Instant.now().minusSeconds(60))).param("email", email).update();
		assertThat(accept(second).statusCode()).isEqualTo(404);

		String third = json.readTree(grant(owner, north, seller, email, 0,
				List.of(item(north, "STAFF"), item(south, "MANAGER"))).body()).get("inviteToken").asText();
		var accepted = accept(third);
		assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
		var acceptedBody = json.readTree(accepted.body());
		assertThat(acceptedBody.get("email").asText()).isEqualTo(email);
		assertThat(acceptedBody.get("stores")).hasSize(2);
		assertThat(accept(third).statusCode()).isEqualTo(404);

		var client = anonymous();
		assertThat(post(client, "/api/auth/login", Map.of("email", email, "password", PASSWORD)).statusCode()).isEqualTo(200);
		assertThat(json.readTree(get(client, "/api/stores/" + north).body()).get("role").asText()).isEqualTo("STAFF");
		assertThat(json.readTree(get(client, "/api/stores/" + south).body()).get("role").asText()).isEqualTo("MANAGER");
		var northSeller = json.readTree(get(owner, "/api/stores/" + north + "/salespeople/" + seller.asText()).body());
		assertThat(northSeller.get("accountEmail").asText()).isEqualTo(email);
		assertThat(json.readTree(get(owner, "/api/stores/" + south + "/salespeople").body()).get("content")).hasSize(1);
	}

	@Test
	void importedSellerWithTheSameNameIsLinkedInsteadOfDuplicated() throws Exception {
		var owner = actor();
		var account = plainActor();
		String north = createStore(owner, "Homonimo Norte").get("id").asText();
		String south = createStore(owner, "Homonimo Sul").get("id").asText();
		var seller = createSeller(owner, north, "Marina Importada");
		createSeller(owner, south, "marina IMPORTADA");

		var response = grant(owner, north, seller, account.account().email(), 0,
				List.of(item(north, "STAFF"), item(south, "STAFF")));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		var southSellers = json.readTree(get(owner, "/api/stores/" + south + "/salespeople").body()).get("content");
		assertThat(southSellers).hasSize(1);
		assertThat(southSellers.get(0).get("userId").asText()).isEqualTo(account.account().id());
	}

	@Test
	void inviteIsIgnoredForStoresTheIssuerNoLongerOwns() throws Exception {
		var owner = actor();
		String north = createStore(owner, "Perdida Norte").get("id").asText();
		var seller = createSeller(owner, north, "Sem Dono");
		String email = "seller-test-" + UUID.randomUUID() + "@example.com";
		invitedEmails.add(email);
		String token = json.readTree(grant(owner, north, seller, email, 0, List.of(item(north, "STAFF"))).body())
				.get("inviteToken").asText();
		jdbc.sql("UPDATE store_memberships SET role = 'MANAGER' WHERE store_id = :store AND user_id = :user")
				.param("store", UUID.fromString(north)).param("user", owner.account().id()).update();
		assertThat(accept(token).statusCode()).isEqualTo(410);
		assertThat(users.findByEmail(email)).isEmpty();
	}

	private JsonNode createSeller(Actor owner, String store, String name) throws Exception {
		var response = post(owner, "/api/stores/" + store + "/salespeople", Map.of("requestId", UUID.randomUUID(), "name", name));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return json.readTree(response.body()).get("id");
	}

	private HttpResponse<String> grant(Actor actor, String store, JsonNode seller, String email, long version,
			List<Map<String, String>> stores) throws Exception {
		return post(actor, "/api/stores/" + store + "/salespeople/" + seller.asText() + "/access",
				Map.of("email", email, "version", version, "stores", stores));
	}

	private HttpResponse<String> accept(String token) throws Exception {
		return post(anonymous(), "/api/seller-invites/accept", Map.of("token", token, "name", "Vendedor Novo", "password", PASSWORD));
	}

	private static Map<String, String> item(String store, String role) {
		return Map.of("storeId", store, "role", role);
	}

	private Actor anonymous() {
		var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		return new Actor(new UserAccount("none", "none", "none", "none", Instant.now()), cookies,
				HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build());
	}
}
