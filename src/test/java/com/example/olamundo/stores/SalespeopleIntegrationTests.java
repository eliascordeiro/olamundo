package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

class SalespeopleIntegrationTests extends StoreApiTestSupport {
	@Test
	void onlyOwnerManagesCommercialSalespeopleAndAllWritesRequireCsrf() throws Exception {
		var owner = actor();
		var outsider = actor();
		var manager = actor();
		var staff = actor();
		String store = createStore(owner, "Equipe Norte").get("id").asText();
		String path = "/api/stores/" + store + "/salespeople";
		for (var member : java.util.List.of(manager, staff)) {
			assertThat(post(owner, "/api/stores/" + store + "/members", Map.of(
					"email", member.account().email(), "role", member == manager ? "MANAGER" : "STAFF"))
					.statusCode()).isEqualTo(201);
		}
		var seller = createSeller(owner, path, "Marina");
		String item = path + "/" + seller.get("id").asText();
		for (var member : java.util.List.of(manager, staff, outsider)) {
			int expected = member == outsider ? 404 : 403;
			assertThat(get(member, path).statusCode()).isEqualTo(expected);
			assertThat(get(member, item).statusCode()).isEqualTo(expected);
			assertThat(post(member, path, Map.of("requestId", UUID.randomUUID(), "name", "Negado")).statusCode()).isEqualTo(expected);
			assertThat(post(member, item, Map.of("name", "Negado", "active", false, "version", 0)).statusCode()).isEqualTo(expected);
			assertThat(post(member, item + "/account", Map.of("email", "", "version", 0)).statusCode()).isEqualTo(expected);
		}
		for (String endpoint : java.util.List.of(path, item, item + "/account")) {
			assertThat(post(owner, endpoint, Map.of(), false).statusCode()).isEqualTo(403);
		}
		var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(anonymous.statusCode()).isEqualTo(401);
		String other = createStore(owner, "Equipe Sul").get("id").asText();
		assertThat(get(owner, "/api/stores/" + other + "/salespeople/" + seller.get("id").asText())
				.statusCode()).isEqualTo(404);
	}

	@Test
	void registrationReplaySurvivesEditsAndInactivationDoesNotChangeAccessOrHistory() throws Exception {
		var owner = actor();
		var member = actor();
		String store = createStore(owner, "Equipe").get("id").asText();
		String path = "/api/stores/" + store + "/salespeople";
		assertThat(post(owner, "/api/stores/" + store + "/members",
				Map.of("email", member.account().email(), "role", "STAFF")).statusCode()).isEqualTo(201);
		UUID request = UUID.randomUUID();
		var registration = Map.of("requestId", request, "name", "  Marina  ");
		var first = post(owner, path, registration);
		assertThat(first.statusCode()).isEqualTo(200);
		String item = path + "/" + request;
		assertThat(json.readTree(first.body()).get("name").asText()).isEqualTo("Marina");
		assertThat(post(owner, path, registration).body()).isEqualTo(first.body());
		assertThat(post(owner, path, Map.of("requestId", request, "name", "Outra")).statusCode()).isEqualTo(409);
		var linked = post(owner, item + "/account", Map.of("email", member.account().email().toUpperCase(), "version", 0));
		assertThat(linked.statusCode()).as(linked.body()).isEqualTo(200);
		assertThat(json.readTree(linked.body()).get("userId").asText()).isEqualTo(member.account().id());
		var changed = post(owner, item, Map.of("name", "Marina Silva", "active", false, "version", 1));
		assertThat(changed.statusCode()).isEqualTo(200);
		var inactive = json.readTree(changed.body());
		assertThat(inactive.get("active").asBoolean()).isFalse();
		assertThat(inactive.get("version").asLong()).isEqualTo(2);
		assertThat(inactive.get("createdBy").asText()).isEqualTo(owner.account().id());
		assertThat(inactive.get("updatedBy").asText()).isEqualTo(owner.account().id());
		assertThat(json.readTree(post(owner, path, registration).body())).isEqualTo(inactive);
		assertThat(post(owner, item, Map.of("name", "Sobrescrita", "active", true, "version", 1)).statusCode()).isEqualTo(409);
		assertThat(get(owner, item).body()).contains("Marina Silva");
		assertThat(get(member, "/api/stores/" + store).body()).contains("STAFF");
		assertThat(get(member, "/api/auth/me").statusCode()).isEqualTo(200);
		assertThat(get(member, path).statusCode()).isEqualTo(403);
		assertThat(post(owner, item + "/account", Map.of("email", "", "version", 2)).statusCode()).isEqualTo(200);
		assertThat(json.readTree(get(owner, item).body()).get("userId").isNull()).isTrue();
		assertThat(get(member, "/api/stores/" + store).body()).contains("STAFF");
		assertThat(jdbc.sql("SELECT count(*) FROM sales_orders WHERE store_id = :store")
				.param("store", UUID.fromString(store)).query(Long.class).single()).isZero();
	}

	@Test
	void linkingRequiresExistingMembershipAndUniqueAccountPerStore() throws Exception {
		var owner = actor();
		var member = actor();
		var outsider = actor();
		String store = createStore(owner, "Norte").get("id").asText();
		String other = createStore(owner, "Sul").get("id").asText();
		String path = "/api/stores/" + store + "/salespeople";
		assertThat(post(owner, "/api/stores/" + store + "/members",
				Map.of("email", member.account().email(), "role", "STAFF")).statusCode()).isEqualTo(201);
		var first = createSeller(owner, path, "Primeira");
		var second = createSeller(owner, path, "Segunda");
		String item = path + "/" + first.get("id").asText();
		assertThat(post(owner, item + "/account", Map.of("email", outsider.account().email(), "version", 0)).statusCode()).isEqualTo(404);
		assertThat(post(owner, item + "/account", Map.of("email", "missing@example.com", "version", 0)).statusCode()).isEqualTo(404);
		assertThat(post(owner, item + "/account", Map.of("email", member.account().email(), "version", 0)).statusCode()).isEqualTo(200);
		assertThat(post(owner, path + "/" + second.get("id").asText() + "/account",
				Map.of("email", member.account().email(), "version", 0)).statusCode()).isEqualTo(409);
		assertThat(json.readTree(get(owner, path + "/" + second.get("id").asText()).body()).get("version").asLong()).isZero();
		assertThat(post(owner, "/api/stores/" + other + "/members",
				Map.of("email", member.account().email(), "role", "MANAGER")).statusCode()).isEqualTo(201);
		var third = createSeller(owner, "/api/stores/" + other + "/salespeople", "Outra loja");
		assertThat(post(owner, "/api/stores/" + other + "/salespeople/" + third.get("id").asText() + "/account",
				Map.of("email", member.account().email(), "version", 0)).statusCode()).isEqualTo(200);
		assertThat(get(outsider, "/api/stores/" + store).statusCode()).isEqualTo(404);
	}

	@Test
	void validationPaginationAndConcurrentEditsAreExplicit() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Validacao").get("id").asText();
		String path = "/api/stores/" + store + "/salespeople";
		for (String name : java.util.List.of("", " a ", "x".repeat(121))) {
			assertThat(post(owner, path, Map.of("requestId", UUID.randomUUID(), "name", name)).statusCode()).isEqualTo(400);
		}
		assertThat(post(owner, path, Map.of("name", "Sem identificador")).statusCode()).isEqualTo(400);
		var first = createSeller(owner, path, "Primeira");
		createSeller(owner, path, "Segunda");
		var listing = json.readTree(get(owner, path + "?size=1").body());
		assertThat(listing.get("content").size()).isEqualTo(1);
		assertThat(listing.get("hasNext").asBoolean()).isTrue();
		assertThat(json.readTree(get(owner, path + "?page=1&size=1").body()).get("hasNext").asBoolean()).isFalse();
		for (String query : java.util.List.of("?page=-1", "?size=0", "?size=101")) {
			assertThat(get(owner, path + query).statusCode()).isEqualTo(400);
		}
		String item = path + "/" + first.get("id").asText();
		assertThat(post(owner, item, Map.of("name", "Valido", "active", true, "version", -1)).statusCode()).isEqualTo(400);
		assertThat(post(owner, item, Map.of("name", "Valido", "version", 0)).statusCode()).isEqualTo(400);
		assertThat(post(owner, item + "/account", Map.of("email", "invalid", "version", 0)).statusCode()).isEqualTo(400);
		assertThat(get(owner, path + "/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var a = CompletableFuture.supplyAsync(() -> editStatus(owner, item, "Edicao A"), executor);
			var b = CompletableFuture.supplyAsync(() -> editStatus(owner, item, "Edicao B"), executor);
			assertThat(java.util.List.of(a.join(), b.join())).containsExactlyInAnyOrder(200, 409);
		}
		assertThat(json.readTree(get(owner, item).body()).get("version").asLong()).isEqualTo(1);
	}

	private tools.jackson.databind.JsonNode createSeller(Actor owner, String path, String name) throws Exception {
		var response = post(owner, path, Map.of("requestId", UUID.randomUUID(), "name", name));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return json.readTree(response.body());
	}

	@Test
	void concurrentRegistrationsAndAccountLinksNeverDuplicateCommercialIdentity() throws Exception {
		var owner = actor();
		var member = actor();
		String store = createStore(owner, "Concorrencia comercial").get("id").asText();
		String path = "/api/stores/" + store + "/salespeople";
		assertThat(post(owner, "/api/stores/" + store + "/members",
				Map.of("email", member.account().email(), "role", "STAFF")).statusCode()).isEqualTo(201);
		var registration = Map.of("requestId", UUID.randomUUID(), "name", "Mesmo cadastro");
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var a = CompletableFuture.supplyAsync(() -> writeStatus(owner, path, registration), executor);
			var b = CompletableFuture.supplyAsync(() -> writeStatus(owner, path, registration), executor);
			assertThat(java.util.List.of(a.join(), b.join())).containsOnly(200);
		}
		assertThat(json.readTree(get(owner, path).body()).get("content").size()).isEqualTo(1);
		var second = createSeller(owner, path, "Segundo cadastro");
		var link = Map.of("email", member.account().email(), "version", 0);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var a = CompletableFuture.supplyAsync(() -> writeStatus(owner,
					path + "/" + registration.get("requestId") + "/account", link), executor);
			var b = CompletableFuture.supplyAsync(() -> writeStatus(owner,
					path + "/" + second.get("id").asText() + "/account", link), executor);
			assertThat(java.util.List.of(a.join(), b.join())).containsExactlyInAnyOrder(200, 409);
		}
		assertThat(jdbc.sql("SELECT count(*) FROM salespeople WHERE store_id = :store AND user_id = :user")
				.param("store", UUID.fromString(store)).param("user", member.account().id())
				.query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success")
				.query(Long.class).single()).isEqualTo(1);
	}

	private int writeStatus(Actor owner, String path, Map<String, ?> body) {
		try {
			return post(owner, path, body).statusCode();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private int editStatus(Actor owner, String item, String name) {
		return writeStatus(owner, item, Map.of("name", name, "active", true, "version", 0));
	}
}
