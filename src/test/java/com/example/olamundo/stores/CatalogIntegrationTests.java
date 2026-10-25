package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import com.example.olamundo.catalog.application.CatalogService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

class CatalogIntegrationTests extends StoreApiTestSupport {
	@Autowired CatalogService catalog;
	@Autowired javax.sql.DataSource dataSource;

	@Test
	void persistsExactPriceAndSkuWithinStoreAndNeverReadsAcrossStores() throws Exception {
		var owner = actor();
		var outsider = actor();
		String first = createStore(owner, "Catalogo A").get("id").asText();
		String second = createStore(owner, "Catalogo B").get("id").asText();
		var response = post(owner, path(first), product(" sku-01 ", "0.10"));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		var saved = json.readTree(response.body());
		String id = saved.get("id").asText();
		assertThat(response.headers().firstValue("location").orElseThrow()).isEqualTo(path(first) + "/" + id);
		assertThat(saved.get("sku").asText()).isEqualTo("SKU-01");
		assertThat(saved.get("currency").asText()).isEqualTo("BRL");
		assertThat(jdbc.sql("SELECT price FROM catalog_products WHERE id = :id").param("id", UUID.fromString(id))
				.query(BigDecimal.class).single()).isEqualByComparingTo("0.10");
		assertThat(get(owner, path(first) + "/" + id).statusCode()).isEqualTo(200);
		assertThat(get(owner, path(second) + "/" + id).statusCode()).isEqualTo(404);
		assertThat(get(outsider, path(first)).statusCode()).isEqualTo(404);
		assertThat(get(outsider, path(first) + "/" + id).statusCode()).isEqualTo(404);
		assertThat(post(outsider, path(first), product("BLOCKED", "2.00")).statusCode()).isEqualTo(404);
		assertThat(post(owner, path(first), product("SKU-01", "2.00")).statusCode()).isEqualTo(409);
		assertThat(post(owner, path(second), product("SKU-01", "2.00")).statusCode()).isEqualTo(201);
		assertThat(json.readTree(get(owner, path(first)).body()).get("content").size()).isEqualTo(1);
		assertThat(json.readTree(get(owner, path(second)).body()).get("content").get(0).get("storeId").asText())
				.isEqualTo(second);
		assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path(first))).GET().build(),
				HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
		assertThat(post(owner, path(first), product("NO-CSRF", "1.00"), false).statusCode()).isEqualTo(403);
	}

	@Test
	void managerWritesOnlyInAuthorizedStoreAndStaffCanOnlyRead() throws Exception {
		var owner = actor();
		var member = actor();
		String first = createStore(owner, "Gerente aqui").get("id").asText();
		String second = createStore(owner, "Colaborador aqui").get("id").asText();
		assertThat(post(owner, "/api/stores/" + first + "/members",
				Map.of("email", member.account().email(), "role", "MANAGER")).statusCode()).isEqualTo(201);
		assertThat(post(owner, "/api/stores/" + second + "/members",
				Map.of("email", member.account().email(), "role", "STAFF")).statusCode()).isEqualTo(201);
		assertThat(post(member, path(first), product("MANAGER-1", "19.90")).statusCode()).isEqualTo(201);
		assertThat(post(member, path(second), product("STAFF-1", "19.90")).statusCode()).isEqualTo(403);
		assertThat(get(member, path(first)).statusCode()).isEqualTo(200);
		assertThat(get(member, path(second)).statusCode()).isEqualTo(200);
		assertThat(json.readTree(get(member, path(second)).body()).get("content").isEmpty()).isTrue();
		// Authorization is re-read, not copied into the global MongoDB session.
		jdbc.sql("UPDATE store_memberships SET role = 'STAFF' WHERE store_id = :storeId AND user_id = :userId")
				.param("storeId", UUID.fromString(first)).param("userId", member.account().id()).update();
		assertThat(post(member, path(first), product("AFTER-CHANGE", "1.00")).statusCode()).isEqualTo(403);
	}

	@Test
	void validatesMoneySkuAndPaginationWithoutRoundingOrTruncation() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Validacao").get("id").asText();
		for (String price : new String[]{"-0.01", "1.001", "10000000000.00", "NaN", "1,20"}) {
			assertThat(post(owner, path(store), product("INVALID", price)).statusCode()).as(price).isEqualTo(400);
		}
		for (String sku : new String[]{"", " ", "SKU SPACE", "á", "x".repeat(65)}) {
			assertThat(post(owner, path(store), product(sku, "1.00")).statusCode()).as(sku).isEqualTo(400);
		}
		var invalid = new HashMap<String, Object>(product("VALID", "1.00"));
		invalid.put("name", " a ");
		assertThat(post(owner, path(store), invalid).statusCode()).isEqualTo(400);
		invalid.put("name", "Produto valido");
		invalid.put("description", "x".repeat(2001));
		assertThat(post(owner, path(store), invalid).statusCode()).isEqualTo(400);
		invalid.remove("price");
		assertThat(post(owner, path(store), invalid).statusCode()).isEqualTo(400);
		assertThat(json.readTree(get(owner, path(store)).body()).get("content").isEmpty()).isTrue();

		for (String price : new String[]{"0", "19.90", "9999999999.99"}) {
			assertThat(post(owner, path(store), product("P-" + price.replace('.', '-'), price)).statusCode()).isEqualTo(201);
		}
		var first = json.readTree(get(owner, path(store) + "?page=0&size=2").body());
		var second = json.readTree(get(owner, path(store) + "?page=1&size=2").body());
		assertThat(first.get("content").size()).isEqualTo(2);
		assertThat(first.get("hasNext").asBoolean()).isTrue();
		assertThat(second.get("content").size()).isEqualTo(1);
		assertThat(second.get("hasNext").asBoolean()).isFalse();
		assertThat(second.get("content").get(0).get("id")).isNotEqualTo(first.get("content").get(0).get("id"));
		for (String query : new String[]{"?page=-1", "?size=0", "?size=101", "?page=abc"}) {
			assertThat(get(owner, path(store) + query).statusCode()).isEqualTo(400);
		}
		assertThat(get(owner, path(store) + "/not-a-uuid").statusCode()).isEqualTo(400);
		assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success")
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void concurrentSameSkuCreatesOnlyOneProduct() throws Exception {
		var owner = actor();
		var store = UUID.fromString(createStore(owner, "Concorrencia SKU").get("id").asText());
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var first = CompletableFuture.supplyAsync(() -> insertStatus(store, owner.account().id()), executor);
			var second = CompletableFuture.supplyAsync(() -> insertStatus(store, owner.account().id()), executor);
			assertThat(java.util.List.of(first.join(), second.join())).containsExactlyInAnyOrder(201, 409);
		}

		assertThat(json.readTree(get(owner, path(store.toString())).body()).get("content").size()).isEqualTo(1);
	}

	@Test
	void upgradesExistingV1SchemaWithoutChangingStoreMembershipData() {
		String schema = "catalog_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		var versionOne = Flyway.configure().dataSource(dataSource).schemas(schema)
				.defaultSchema(schema).target("1").load();
		try {
			versionOne.migrate();
			var storeId = UUID.randomUUID();
			jdbc.sql("INSERT INTO " + schema + ".stores (id, name, created_at) VALUES (:id, 'Existing store', now())")
					.param("id", storeId).update();
			jdbc.sql("INSERT INTO " + schema + ".store_memberships (store_id, user_id, role, created_at)"
					+ " VALUES (:id, 'existing-mongo-user', 'OWNER', now())").param("id", storeId).update();
			var latest = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
			latest.migrate();
			assertThat(jdbc.sql("SELECT name FROM " + schema + ".stores WHERE id = :id").param("id", storeId)
					.query(String.class).single()).isEqualTo("Existing store");
			assertThat(jdbc.sql("SELECT user_id FROM " + schema + ".store_memberships WHERE store_id = :id")
					.param("id", storeId).query(String.class).single()).isEqualTo("existing-mongo-user");
			assertThat(jdbc.sql("SELECT count(*) FROM " + schema + ".catalog_products").query(Long.class).single())
					.isZero();
			latest.migrate();
			assertThat(jdbc.sql("SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE version = '2'")
					.query(Long.class).single()).isEqualTo(1);
		} finally {
			// This randomly named schema exists only inside the isolated test container.
			jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
		}
	}

	private int insertStatus(UUID store, String userId) {
		try {
			catalog.create(store, userId, "Produto concorrente", "SAME-SKU", "", new BigDecimal("19.90"));
			return 201;
		} catch (ResponseStatusException exception) {
			return exception.getStatusCode().value();
		}
	}

	private static String path(String store) {
		return "/api/stores/" + store + "/products";
	}

	private static Map<String, String> product(String sku, String price) {
		return Map.of("name", "Produto teste", "sku", sku, "description", "Descricao do produto", "price", price);
	}
}
