package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
import com.example.olamundo.orders.application.OrderService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {"app.api-docs.enabled=false", "springdoc.api-docs.enabled=false",
				"springdoc.swagger-ui.enabled=false", "app.orders-test=true"})
class OrderIntegrationTests extends StoreApiTestSupport {
	@Autowired OrderService orders;
	@Autowired InventoryService inventory;
	@Autowired DataSource dataSource;

	@Test
	void snapshotsExactPricesAndConfirmsWithoutDoubleDeduction() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Pedidos exatos").get("id").asText();
		String product = product(owner, store, "DECIMAL", "0.10");
		entry(store, product, owner, 10);
		var request = body(UUID.randomUUID(), product, "3");
		var order = create(owner, store, request);
		String id = order.get("summary").get("id").asText();
		assertThat(order.get("summary").get("status").asText()).isEqualTo("DRAFT");
		assertThat(order.get("summary").get("total").asText()).isEqualTo("0.30");
		assertThat(balance(store, product, owner)).isEqualTo(10);
		jdbc.sql("UPDATE catalog_products SET price = 9.99, name = 'Nome novo' WHERE id = :id")
				.param("id", UUID.fromString(product)).update();
		var changedPriceReplay = create(owner, store, request);
		assertThat(changedPriceReplay).isEqualTo(order);
		var confirmation = post(owner, path(store) + "/" + id + "/confirm", Map.of());
		assertThat(confirmation.statusCode()).as(confirmation.body()).isEqualTo(200);
		var confirmed = json.readTree(confirmation.body());
		assertThat(confirmed.get("summary").get("status").asText()).isEqualTo("CONFIRMED");
		assertThat(confirmed.get("summary").get("confirmedBy").asText()).isEqualTo(owner.account().id());
		assertThat(confirmed.get("items").get(0).get("productName").asText()).isEqualTo("Produto pedido");
		assertThat(confirmed.get("summary").get("total").asText()).isEqualTo("0.30");
		assertThat(balance(store, product, owner)).isEqualTo(7);
		assertThat(json.readTree(post(owner, path(store) + "/" + id + "/confirm", Map.of()).body())).isEqualTo(confirmed);
		assertThat(create(owner, store, request)).isEqualTo(confirmed);
		var history = inventory.history(UUID.fromString(store), UUID.fromString(product), owner.account().id(), 0, 20);
		assertThat(history.content()).hasSize(2);
		assertThat(history.content().get(0).reason()).contains(id);
		assertThat(history.content().get(0).quantity()).isEqualTo(3);
		assertThat(balance(store, product, owner)).isEqualTo(7);
		assertThat(get(owner, path(store) + "/" + id).statusCode()).isEqualTo(200);
		assertThat(post(owner, path(store), body(UUID.fromString(id), product, "4")).statusCode()).isEqualTo(409);
	}

	@Test
	void insufficientLaterItemRollsBackEveryStockMovementAndCanBeRetried() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Pedido atomico").get("id").asText();
		var products = new ArrayList<>(List.of(product(owner, store, "A", "19.90"), product(owner, store, "B", "2.00")));
		products.sort(String::compareTo);
		entry(store, products.get(0), owner, 5);
		var request = Map.<String, Object>of("requestId", UUID.randomUUID().toString(),
				"items", List.of(item(products.get(0), "2"), item(products.get(1), "1")));
		var order = create(owner, store, request);
		String id = order.get("summary").get("id").asText();
		String confirm = path(store) + "/" + id + "/confirm";
		assertThat(post(owner, confirm, Map.of()).statusCode()).isEqualTo(409);
		assertThat(balance(store, products.get(0), owner)).isEqualTo(5);
		assertThat(inventory.history(UUID.fromString(store), UUID.fromString(products.get(0)), owner.account().id(), 0, 20).content()).hasSize(1);
		assertThat(json.readTree(get(owner, path(store) + "/" + id).body()).get("summary").get("status").asText()).isEqualTo("DRAFT");
		entry(store, products.get(1), owner, 2);
		assertThat(post(owner, confirm, Map.of()).statusCode()).isEqualTo(200);
		assertThat(balance(store, products.get(0), owner)).isEqualTo(3);
		assertThat(balance(store, products.get(1), owner)).isEqualTo(1);
	}

	@Test
	void enforcesTenantProductRoleAndCsrfBoundaries() throws Exception {
		var owner = actor();
		var member = actor();
		var outsider = actor();
		String a = createStore(owner, "Pedidos gerente").get("id").asText();
		String b = createStore(owner, "Pedidos leitura").get("id").asText();
		String product = product(owner, a, "P", "1.00");
		entry(a, product, owner, 4);
		assertThat(post(owner, "/api/stores/" + a + "/members", Map.of("email", member.account().email(), "role", "MANAGER")).statusCode()).isEqualTo(201);
		assertThat(post(owner, "/api/stores/" + b + "/members", Map.of("email", member.account().email(), "role", "STAFF")).statusCode()).isEqualTo(201);
		var order = create(member, a, body(UUID.randomUUID(), product, "1"));
		String id = order.get("summary").get("id").asText();
		assertThat(post(member, path(a) + "/" + id + "/confirm", Map.of()).statusCode()).isEqualTo(200);
		assertThat(post(member, path(b), body(UUID.randomUUID(), product, "1")).statusCode()).isEqualTo(403);
		assertThat(get(member, path(b)).statusCode()).isEqualTo(200);
		assertThat(get(owner, path(b) + "/" + id).statusCode()).isEqualTo(404);
		assertThat(post(owner, path(b) + "/" + id + "/confirm", Map.of()).statusCode()).isEqualTo(404);
		assertThat(post(owner, path(b), body(UUID.randomUUID(), product, "1")).statusCode()).isEqualTo(404);
		assertThat(json.readTree(get(owner, path(b)).body()).get("content").isEmpty()).isTrue();
		assertThat(get(outsider, path(a)).statusCode()).isEqualTo(404);
		assertThat(get(outsider, path(a) + "/" + id).statusCode()).isEqualTo(404);
		assertThat(post(outsider, path(a) + "/" + id + "/confirm", Map.of()).statusCode()).isEqualTo(404);
		assertThat(post(owner, path(a), body(UUID.randomUUID(), product, "1"), false).statusCode()).isEqualTo(403);
		assertThat(post(owner, path(a) + "/" + id + "/confirm", Map.of(), false).statusCode()).isEqualTo(403);
		assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path(a))).GET().build(),
				HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
		jdbc.sql("UPDATE store_memberships SET role = 'STAFF' WHERE store_id = :store AND user_id = :user")
				.param("store", UUID.fromString(a)).param("user", member.account().id()).update();
		assertThat(get(member, path(a) + "/" + id).statusCode()).isEqualTo(200);
		assertThat(post(member, path(a) + "/" + id + "/confirm", Map.of()).statusCode()).isEqualTo(403);
	}

	@Test
	void validatesItemsAndPaginationAndIgnoresCallerProvidedPrice() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Validacao pedidos").get("id").asText();
		String product = product(owner, store, "MAX", "9999999999.99");
		for (String quantity : new String[]{"0", "-1", "1.5", "2147483648", "NaN"}) {
			assertThat(post(owner, path(store), body(UUID.randomUUID(), product, quantity)).statusCode()).as(quantity).isEqualTo(400);
		}
		assertThat(post(owner, path(store), Map.of("requestId", UUID.randomUUID(), "items", List.of())).statusCode()).isEqualTo(400);
		assertThat(post(owner, path(store), Map.of("requestId", UUID.randomUUID(), "items", List.of(item(product, "1"), item(product, "2")))).statusCode()).isEqualTo(400);
		assertThat(post(owner, path(store), Map.of("requestId", UUID.randomUUID(), "items", java.util.Collections.nCopies(51, item(product, "1")))).statusCode()).isEqualTo(400);
		assertThat(post(owner, path(store), Map.of("items", List.of(item(product, "1")))).statusCode()).isEqualTo(400);
		var line = new HashMap<String, Object>(item(product, "2147483647"));
		line.put("price", "0.01");
		var maximum = create(owner, store, Map.of("requestId", UUID.randomUUID(), "items", List.of(line)));
		assertThat(new BigDecimal(maximum.get("summary").get("total").asText()))
				.isEqualByComparingTo(new BigDecimal("9999999999.99").multiply(new BigDecimal("2147483647")));
		create(owner, store, body(UUID.randomUUID(), product, "1"));
		var first = json.readTree(get(owner, path(store) + "?size=1").body());
		var second = json.readTree(get(owner, path(store) + "?size=1&page=1").body());
		assertThat(first.get("hasNext").asBoolean()).isTrue();
		assertThat(second.get("hasNext").asBoolean()).isFalse();
		assertThat(first.get("content").get(0).get("id")).isNotEqualTo(second.get("content").get(0).get("id"));
		for (String query : new String[]{"?page=-1", "?size=0", "?size=101", "?page=abc"}) {
			assertThat(get(owner, path(store) + query).statusCode()).isEqualTo(400);
		}
		assertThat(get(owner, path(store) + "/not-a-uuid").statusCode()).isEqualTo(400);
	}

	@Test
	void concurrentCreationAndConfirmationDoNotDuplicateOrdersOrStockExits() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Concorrencia pedidos").get("id").asText();
		String product = product(owner, store, "RACE", "19.90");
		entry(store, product, owner, 10);
		UUID storeId = UUID.fromString(store);
		UUID orderId = UUID.randomUUID();
		var items = List.of(new OrderService.RequestedItem(UUID.fromString(product), new BigDecimal("7")));
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var start = new CountDownLatch(1);
			var one = CompletableFuture.supplyAsync(() -> {
				await(start);
				return orders.create(storeId, owner.account().id(), orderId, items);
			}, executor);
			var two = CompletableFuture.supplyAsync(() -> {
				await(start);
				return orders.create(storeId, owner.account().id(), orderId, items);
			}, executor);
			start.countDown();
			assertThat(one.join()).isEqualTo(two.join());
			assertThat(orders.list(storeId, owner.account().id(), 0, 20).content()).hasSize(1);
			var confirm = new CountDownLatch(1);
			var c1 = CompletableFuture.supplyAsync(() -> confirmStatus(confirm, storeId, orderId, owner.account().id()), executor);
			var c2 = CompletableFuture.supplyAsync(() -> confirmStatus(confirm, storeId, orderId, owner.account().id()), executor);
			confirm.countDown();
			assertThat(List.of(c1.join(), c2.join())).containsExactly(200, 200);
			assertThat(balance(store, product, owner)).isEqualTo(3);
			assertThat(inventory.history(storeId, UUID.fromString(product), owner.account().id(), 0, 20).content()).hasSize(2);
		}
	}

	@Test
	void competingOrdersForSameProductsHaveNoDeadlockOrOversell() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Pedidos concorrentes").get("id").asText();
		String p1 = product(owner, store, "ONE", "1.00");
		String p2 = product(owner, store, "TWO", "2.00");
		entry(store, p1, owner, 10);
		entry(store, p2, owner, 10);
		var first = create(owner, store, Map.of("requestId", UUID.randomUUID(), "items", List.of(item(p1, "7"), item(p2, "7"))));
		var second = create(owner, store, Map.of("requestId", UUID.randomUUID(), "items", List.of(item(p2, "7"), item(p1, "7"))));
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var start = new CountDownLatch(1);
			var one = CompletableFuture.supplyAsync(() -> confirmStatus(start, UUID.fromString(store),
					UUID.fromString(first.get("summary").get("id").asText()), owner.account().id()), executor);
			var two = CompletableFuture.supplyAsync(() -> confirmStatus(start, UUID.fromString(store),
					UUID.fromString(second.get("summary").get("id").asText()), owner.account().id()), executor);
			start.countDown();
			assertThat(List.of(one.join(), two.join())).containsExactlyInAnyOrder(200, 409);
			assertThat(balance(store, p1, owner)).isEqualTo(3);
			assertThat(balance(store, p2, owner)).isEqualTo(3);
		}
	}

	@Test
	void upgradesV3WithoutChangingExistingStockOrProducts() {
		String schema = "order_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		try {
			Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("3").load().migrate();
			UUID store = UUID.randomUUID(), product = UUID.randomUUID();
			jdbc.sql("INSERT INTO " + schema + ".stores VALUES (:id, 'Loja existente', now())").param("id", store).update();
			jdbc.sql("INSERT INTO " + schema + ".catalog_products VALUES (:id,:store,'Produto','SKU','',19.90,'BRL',now())")
					.param("id", product).param("store", store).update();
			jdbc.sql("INSERT INTO " + schema + ".inventory_balances VALUES (:store,:product,7,0)")
					.param("store", store).param("product", product).update();
			var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
			flyway.migrate();
			flyway.migrate();
			assertThat(jdbc.sql("SELECT quantity FROM " + schema + ".inventory_balances").query(Integer.class).single()).isEqualTo(7);
			assertThat(jdbc.sql("SELECT count(*) FROM " + schema + ".sales_orders").query(Long.class).single()).isZero();
			assertThat(jdbc.sql("SELECT price FROM " + schema + ".catalog_products").query(BigDecimal.class).single()).isEqualByComparingTo("19.90");
		} finally {
			// Only the randomly named schema in the isolated test container is removed.
			jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
		}
	}

	private JsonNode create(Actor actor, String store, Map<String, ?> body) throws Exception {
		var response = post(actor, path(store), body);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return json.readTree(response.body());
	}

	private String product(Actor owner, String store, String sku, String price) throws Exception {
		var response = post(owner, "/api/stores/" + store + "/products", Map.of("name", "Produto pedido", "sku", sku, "price", price));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return json.readTree(response.body()).get("id").asText();
	}

	private void entry(String store, String product, Actor owner, int quantity) {
		inventory.move(UUID.fromString(store), UUID.fromString(product), owner.account().id(), UUID.randomUUID(),
				MovementType.IN, BigDecimal.valueOf(quantity), "Entrada para pedidos");
	}

	private int balance(String store, String product, Actor owner) {
		return inventory.balance(UUID.fromString(store), UUID.fromString(product), owner.account().id()).quantity();
	}

	private static String path(String store) { return "/api/stores/" + store + "/orders"; }

	private static Map<String, Object> body(UUID id, String product, String quantity) {
		return Map.of("requestId", id.toString(), "items", List.of(item(product, quantity)));
	}

	private static Map<String, Object> item(String product, String quantity) {
		return Map.of("productId", product, "quantity", quantity);
	}

	private static void await(CountDownLatch start) {
		try { start.await(); }
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private int confirmStatus(CountDownLatch start, UUID store, UUID order, String user) {
		await(start);
		try {
			orders.confirm(store, order, user);
			return 200;
		} catch (ResponseStatusException exception) {
			return exception.getStatusCode().value();
		}
	}
}
