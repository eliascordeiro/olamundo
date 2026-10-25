package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import com.example.olamundo.inventory.application.InventoryService;
import com.example.olamundo.inventory.domain.StockMovement.MovementType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {"app.api-docs.enabled=false", "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
class InventoryIntegrationTests extends StoreApiTestSupport {
	@Autowired InventoryService inventory;
	@Autowired DataSource dataSource;

	@Test
	void startsAtZeroAndRecordsAuditedIdempotentEntriesAndExits() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Estoque").get("id").asText();
		String path = stockPath(owner, store, "TEST");
		assertThat(json.readTree(get(owner, path).body()).get("quantity").asInt()).isZero();
		assertThat(json.readTree(get(owner, path + "/movements").body()).get("content").isEmpty()).isTrue();
		var entry = movement("IN", "10");
		var response = post(owner, path + "/movements", entry);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		var saved = json.readTree(response.body());
		assertThat(saved.get("balanceAfter").asInt()).isEqualTo(10);
		assertThat(saved.get("userId").asText()).isEqualTo(owner.account().id());
		assertThat(saved.get("sequence").asLong()).isEqualTo(1);
		assertThat(json.readTree(post(owner, path + "/movements", entry).body())).isEqualTo(saved);
		var changed = new HashMap<String, Object>(entry);
		changed.put("quantity", "11");
		assertThat(post(owner, path + "/movements", changed).statusCode()).isEqualTo(409);
		assertThat(post(owner, path + "/movements", movement("OUT", "4")).statusCode()).isEqualTo(200);
		assertThat(post(owner, path + "/movements", movement("OUT", "7")).statusCode()).isEqualTo(409);
		var balance = json.readTree(get(owner, path).body());
		assertThat(balance.get("quantity").asInt()).isEqualTo(6);
		assertThat(balance.get("version").asLong()).isEqualTo(2);
		var history = json.readTree(get(owner, path + "/movements?size=1").body());
		assertThat(history.get("content").get(0).get("type").asText()).isEqualTo("OUT");
		assertThat(history.get("hasNext").asBoolean()).isTrue();
		var second = json.readTree(get(owner, path + "/movements?page=1&size=1").body());
		assertThat(second.get("content").get(0).get("id")).isEqualTo(saved.get("id"));
		assertThat(second.get("hasNext").asBoolean()).isFalse();
		// A replay must return the original result even after later movements.
		assertThat(json.readTree(post(owner, path + "/movements", entry).body())).isEqualTo(saved);
		assertThat(json.readTree(get(owner, path).body()).get("quantity").asInt()).isEqualTo(6);
	}

	@Test
	void isolatesProductsAndStoresAndChecksRolesAndCsrf() throws Exception {
		var owner = actor();
		var member = actor();
		var outsider = actor();
		String first = createStore(owner, "Estoque gerente").get("id").asText();
		String second = createStore(owner, "Estoque colaborador").get("id").asText();
		String a = stockPath(owner, first, "SAME");
		String b = stockPath(owner, second, "SAME");
		String other = stockPath(owner, first, "OTHER");
		post(owner, "/api/stores/" + first + "/members", Map.of("email", member.account().email(), "role", "MANAGER"));
		post(owner, "/api/stores/" + second + "/members", Map.of("email", member.account().email(), "role", "STAFF"));
		assertThat(post(member, a + "/movements", movement("IN", "5")).statusCode()).isEqualTo(200);
		assertThat(post(member, b + "/movements", movement("IN", "1")).statusCode()).isEqualTo(403);
		assertThat(json.readTree(get(member, b).body()).get("quantity").asInt()).isZero();
		assertThat(json.readTree(get(owner, other).body()).get("quantity").asInt()).isZero();
		String cross = a.replace(first, second);
		for (String path : new String[]{cross, cross + "/movements"}) {
			assertThat(get(owner, path).statusCode()).isEqualTo(404);
		}
		assertThat(post(owner, cross + "/movements", movement("IN", "1")).statusCode()).isEqualTo(404);
		assertThat(get(outsider, a).statusCode()).isEqualTo(404);
		assertThat(get(outsider, a + "/movements").statusCode()).isEqualTo(404);
		assertThat(post(outsider, a + "/movements", movement("IN", "1")).statusCode()).isEqualTo(404);
		assertThat(post(owner, a + "/movements", movement("IN", "1"), false).statusCode()).isEqualTo(403);
		assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(a)).GET().build(),
				HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
		jdbc.sql("UPDATE store_memberships SET role = 'STAFF' WHERE store_id = :store AND user_id = :user")
				.param("store", UUID.fromString(first)).param("user", member.account().id()).update();
		assertThat(post(member, a + "/movements", movement("OUT", "1")).statusCode()).isEqualTo(403);
	}

	@Test
	void validatesIntegerBoundsAndRejectsInvalidRequestsWithoutChangingBalance() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Limites").get("id").asText();
		String path = stockPath(owner, store, "LIMIT");
		for (String quantity : new String[]{"0", "-1", "1.5", "2147483648", "NaN", "1,2"}) {
			assertThat(post(owner, path + "/movements", movement("IN", quantity)).statusCode()).as(quantity).isEqualTo(400);
		}
		for (String key : new String[]{"requestId", "type", "quantity", "reason"}) {
			var invalid = new HashMap<String, Object>(movement("IN", "1"));
			invalid.remove(key);
			assertThat(post(owner, path + "/movements", invalid).statusCode()).as(key).isEqualTo(400);
		}
		var invalid = new HashMap<String, Object>(movement("ADJUST", "1"));
		assertThat(post(owner, path + "/movements", invalid).statusCode()).isEqualTo(400);
		invalid.put("type", "IN");
		for (String reason : new String[]{" ", " a ", "x".repeat(501)}) {
			invalid.put("reason", reason);
			assertThat(post(owner, path + "/movements", invalid).statusCode()).isEqualTo(400);
		}
		assertThat(post(owner, path + "/movements", movement("OUT", "1")).statusCode()).isEqualTo(409);
		assertThat(json.readTree(get(owner, path).body()).get("version").asLong()).isZero();
		assertThat(post(owner, path + "/movements", movement("IN", "2147483647")).statusCode()).isEqualTo(200);
		assertThat(post(owner, path + "/movements", movement("IN", "1")).statusCode()).isEqualTo(409);
		assertThat(json.readTree(get(owner, path).body()).get("quantity").asLong()).isEqualTo(2147483647L);
		for (String query : new String[]{"?size=0", "?size=101", "?page=-1", "?page=abc"}) {
			assertThat(get(owner, path + "/movements" + query).statusCode()).isEqualTo(400);
		}
		assertThat(json.readTree(get(owner, path + "/movements").body()).get("content").size()).isEqualTo(1);
	}

	@Test
	void serializesConcurrentFirstEntriesReplaysAndCompetingExits() throws Exception {
		var owner = actor();
		UUID store = UUID.fromString(createStore(owner, "Concorrencia").get("id").asText());
		String path = stockPath(owner, store.toString(), "RACE");
		UUID product = UUID.fromString(path.split("/")[5]);
		UUID request = UUID.randomUUID();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var start = new CountDownLatch(1);
			var calls = List.of(
					CompletableFuture.supplyAsync(() -> concurrentMove(start, store, product, owner.account().id(), request, MovementType.IN, 10), executor),
					CompletableFuture.supplyAsync(() -> concurrentMove(start, store, product, owner.account().id(), request, MovementType.IN, 10), executor));
			start.countDown();
			assertThat(calls.stream().map(CompletableFuture::join).toList()).containsExactly(200, 200);
			assertThat(inventory.balance(store, product, owner.account().id()).quantity()).isEqualTo(10);
			assertThat(inventory.history(store, product, owner.account().id(), 0, 20).content()).hasSize(1);
			var exits = new CountDownLatch(1);
			var first = CompletableFuture.supplyAsync(() -> concurrentMove(exits, store, product, owner.account().id(), UUID.randomUUID(), MovementType.OUT, 7), executor);
			var second = CompletableFuture.supplyAsync(() -> concurrentMove(exits, store, product, owner.account().id(), UUID.randomUUID(), MovementType.OUT, 7), executor);
			exits.countDown();
			assertThat(List.of(first.join(), second.join())).containsExactlyInAnyOrder(200, 409);
			assertThat(inventory.balance(store, product, owner.account().id()).quantity()).isEqualTo(3);
			assertThat(inventory.history(store, product, owner.account().id(), 0, 20).content()).hasSize(2);
			var entries = new CountDownLatch(1);
			var third = CompletableFuture.supplyAsync(() -> concurrentMove(entries, store, product, owner.account().id(), UUID.randomUUID(), MovementType.IN, 2), executor);
			var fourth = CompletableFuture.supplyAsync(() -> concurrentMove(entries, store, product, owner.account().id(), UUID.randomUUID(), MovementType.IN, 2), executor);
			entries.countDown();
			assertThat(List.of(third.join(), fourth.join())).containsExactly(200, 200);
			assertThat(inventory.balance(store, product, owner.account().id()).quantity()).isEqualTo(7);
		}
	}

	@Test
	void upgradesV2WithExistingProductWithoutInventingStock() {
		String schema = "inventory_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		try {
			Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("2").load().migrate();
			var store = UUID.randomUUID();
			var product = UUID.randomUUID();
			jdbc.sql("INSERT INTO " + schema + ".stores VALUES (:id, 'Loja existente', now())").param("id", store).update();
			jdbc.sql("INSERT INTO " + schema + ".catalog_products"
					+ " (id,store_id,name,sku,description,price,currency,created_at)"
					+ " VALUES (:id,:store,'Produto existente','000001','Preservar',19.90,'BRL',now())")
					.param("id", product).param("store", store).update();
			var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
			flyway.migrate();
			flyway.migrate();
			assertThat(jdbc.sql("SELECT sku FROM " + schema + ".catalog_products").query(String.class).single()).isEqualTo("000001");
			assertThat(jdbc.sql("SELECT count(*) FROM " + schema + ".inventory_balances").query(Long.class).single()).isZero();
			assertThat(jdbc.sql("SELECT count(*) FROM " + schema + ".inventory_movements").query(Long.class).single()).isZero();
		} finally {
			// Only this randomly named schema inside the test container is removed.
			jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
		}

	}

	@Test
	void rollsBackBalanceWhenHistoryCannotBePersisted() throws Exception {
		var owner = actor();
		UUID store = UUID.fromString(createStore(owner, "Rollback estoque").get("id").asText());
		String path = stockPath(owner, store.toString(), "ROLLBACK");
		UUID product = UUID.fromString(path.split("/")[5]);
		inventory.move(store, product, owner.account().id(), UUID.randomUUID(), MovementType.IN,
				BigDecimal.TEN, "Entrada valida");
		jdbc.sql("ALTER TABLE inventory_movements ADD CONSTRAINT inventory_test_reject_reason"
				+ " CHECK (reason <> 'Falha simulada')").update();
		try {
			assertThatThrownBy(() -> inventory.move(store, product, owner.account().id(), UUID.randomUUID(),
					MovementType.OUT, BigDecimal.ONE, "Falha simulada")).isInstanceOf(DataIntegrityViolationException.class);
			var balance = inventory.balance(store, product, owner.account().id());
			assertThat(balance.quantity()).isEqualTo(10);
			assertThat(balance.version()).isEqualTo(1);
			assertThat(inventory.history(store, product, owner.account().id(), 0, 20).content()).hasSize(1);
		} finally {
			jdbc.sql("ALTER TABLE inventory_movements DROP CONSTRAINT inventory_test_reject_reason").update();
		}
	}

	private int concurrentMove(CountDownLatch start, UUID store, UUID product, String user, UUID request, MovementType type, int units) {
		try {
			start.await();
			inventory.move(store, product, user, request, type, BigDecimal.valueOf(units), "Teste concorrente");
			return 200;
		} catch (ResponseStatusException exception) {
			return exception.getStatusCode().value();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private String stockPath(Actor owner, String store, String sku) throws Exception {
		var response = post(owner, "/api/stores/" + store + "/products", Map.of("name", "Produto estoque", "sku", sku, "price", "19.90"));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return "/api/stores/" + store + "/products/" + json.readTree(response.body()).get("id").asText() + "/stock";
	}

	private static Map<String, Object> movement(String type, String quantity) {
		return Map.of("requestId", UUID.randomUUID().toString(), "type", type, "quantity", quantity, "reason", "Teste de estoque");
	}
}
