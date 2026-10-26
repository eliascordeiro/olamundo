package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.example.olamundo.inventory.application.InventoryService;
import com.example.olamundo.inventory.domain.StockMovement.MovementType;
import com.example.olamundo.orders.application.OrderService;
import com.example.olamundo.orders.domain.Order;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {"app.api-docs.enabled=false", "springdoc.api-docs.enabled=false",
				"springdoc.swagger-ui.enabled=false", "app.cancellation-test=true"})
class OrderCancellationTests extends StoreApiTestSupport {
	@Autowired OrderService orders;
	@Autowired InventoryService inventory;
	@Autowired DataSource dataSource;

	@Test
	void cancelsDraftWithoutStockAndPreservesSnapshotAndCreationReplay() throws Exception {
		var f = fixture(2, 0);
		String path = path(f);
		var response = post(f.owner(), path + "/cancel", Map.of("reason", "  Pedido duplicado  "));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		var cancelled = json.readTree(response.body());
		assertThat(cancelled.get("summary").get("status").asText()).isEqualTo("CANCELLED");
		assertThat(cancelled.get("summary").get("cancellationReason").asText()).isEqualTo("Pedido duplicado");
		assertThat(cancelled.get("summary").get("cancelledBy").asText()).isEqualTo(f.user());
		assertThat(cancelled.get("summary").get("cancelledAt").isNull()).isFalse();
		assertThat(cancelled.get("summary").get("confirmedAt").isNull()).isTrue();
		assertThat(cancelled.get("summary").get("total").asText()).isEqualTo("0.60");
		assertThat(cancelled.get("items")).isEqualTo(json.valueToTree(f.order()).get("items"));
		assertThat(json.readTree(get(f.owner(), path).body())).isEqualTo(cancelled);
		assertThat(json.readTree(post(f.owner(), path + "/cancel", Map.of("reason", "Pedido duplicado")).body())).isEqualTo(cancelled);
		assertThat(post(f.owner(), path + "/cancel", Map.of("reason", "Outro motivo")).statusCode()).isEqualTo(409);
		assertThat(post(f.owner(), path + "/confirm", Map.of()).statusCode()).isEqualTo(409);
		var replay = orders.create(f.store(), f.user(), f.order().summary().id(), f.items());
		assertThat(replay.summary().status()).isEqualTo(Order.Status.CANCELLED);
		for (UUID product : f.products()) {
			assertThat(balance(f, product)).isZero();
			assertThat(inventory.history(f.store(), product, f.user(), 0, 20).content()).isEmpty();
		}
		assertThat(orders.list(f.store(), f.user(), 0, 20).content().get(0).status()).isEqualTo(Order.Status.CANCELLED);
	}

	@Test
	void managerReturnsConfirmedItemsOnceAndKeepsBothAuditIdentities() throws Exception {
		var f = fixture(2, 10);
		var manager = actor();
		assertThat(post(f.owner(), "/api/stores/" + f.store() + "/members",
				Map.of("email", manager.account().email(), "role", "MANAGER")).statusCode()).isEqualTo(201);
		var confirmed = orders.confirm(f.store(), f.order().summary().id(), f.user());
		for (UUID product : f.products()) assertThat(balance(f, product)).isEqualTo(7);
		var response = post(manager, path(f) + "/cancel", Map.of("reason", "Desistencia interna"));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		var result = orders.get(f.store(), f.order().summary().id(), f.user());
		assertThat(result.summary().status()).isEqualTo(Order.Status.CANCELLED);
		assertThat(result.summary().confirmedBy()).isEqualTo(f.user());
		assertThat(result.summary().confirmedAt()).isEqualTo(confirmed.summary().confirmedAt());
		assertThat(result.summary().cancelledBy()).isEqualTo(manager.account().id());
		assertThat(result.items()).isEqualTo(confirmed.items());
		assertThat(json.readTree(post(f.owner(), path(f) + "/cancel", Map.of("reason", "Desistencia interna")).body()))
				.isEqualTo(json.readTree(response.body()));
		for (UUID product : f.products()) {
			assertThat(balance(f, product)).isEqualTo(10);
			var history = inventory.history(f.store(), product, f.user(), 0, 20).content();
			assertThat(history).hasSize(3);
			assertThat(history.get(0).type()).isEqualTo(MovementType.IN);
			assertThat(history.get(0).quantity()).isEqualTo(3);
			assertThat(history.get(0).reason()).isEqualTo("Cancelamento do pedido " + f.order().summary().id());
			assertThat(history.get(0).userId()).isEqualTo(manager.account().id());
		}
		assertThat(post(f.owner(), path(f) + "/confirm", Map.of()).statusCode()).isEqualTo(409);
	}

	@Test
	void overflowInLaterItemRollsBackAllReturnsAndCancellationThenAllowsRetry() throws Exception {
		var f = fixture(2, 10);
		orders.confirm(f.store(), f.order().summary().id(), f.user());
		UUID later = f.products().get(1);
		move(f, later, Integer.MAX_VALUE - 7, MovementType.IN);
		var failed = post(f.owner(), path(f) + "/cancel", Map.of("reason", "Estorno integral"));
		assertThat(failed.statusCode()).as(failed.body()).isEqualTo(409);
		assertThat(balance(f, f.products().get(0))).isEqualTo(7);
		assertThat(balance(f, later)).isEqualTo(Integer.MAX_VALUE);
		assertThat(inventory.history(f.store(), f.products().get(0), f.user(), 0, 20).content()).hasSize(2);
		var unchanged = orders.get(f.store(), f.order().summary().id(), f.user()).summary();
		assertThat(unchanged.status()).isEqualTo(Order.Status.CONFIRMED);
		assertThat(unchanged.cancelledAt()).isNull();
		assertThat(unchanged.cancellationReason()).isNull();
		move(f, later, 3, MovementType.OUT);
		assertThat(post(f.owner(), path(f) + "/cancel", Map.of("reason", "Estorno integral")).statusCode()).isEqualTo(200);
		assertThat(balance(f, later)).isEqualTo(Integer.MAX_VALUE);
		assertThat(balance(f, f.products().get(0))).isEqualTo(10);
	}

	@Test
	void validatesReasonAndEnforcesLiveRoleTenantSessionAndCsrfChecks() throws Exception {
		var f = fixture(1, 10);
		var member = actor();
		var outsider = actor();
		var other = createStore(f.owner(), "Outra loja").get("id").asText();
		assertThat(post(f.owner(), "/api/stores/" + f.store() + "/members",
				Map.of("email", member.account().email(), "role", "MANAGER")).statusCode()).isEqualTo(201);
		for (var body : List.<Map<String, ?>>of(Map.of(), Map.of("reason", ""), Map.of("reason", "  "),
				Map.of("reason", "a"), Map.of("reason", "x".repeat(501)))) {
			assertThat(post(f.owner(), path(f) + "/cancel", body).statusCode()).isEqualTo(400);
		}
		assertThat(post(f.owner(), path(f) + "/cancel", Map.of("reason", "Motivo valido"), false).statusCode()).isEqualTo(403);
		assertThat(post(outsider, path(f) + "/cancel", Map.of("reason", "Motivo valido")).statusCode()).isEqualTo(404);
		assertThat(post(f.owner(), "/api/stores/" + other + "/orders/" + f.order().summary().id() + "/cancel",
				Map.of("reason", "Motivo valido")).statusCode()).isEqualTo(404);
		var anonymousClient = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
		var csrf = anonymousClient.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		var anonymous = anonymousClient.send(HttpRequest.newBuilder(uri(path(f) + "/cancel"))
				.header("Content-Type", "application/json").header("X-CSRF-TOKEN", json.readTree(csrf.body()).get("token").asText())
				.POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"Teste\"}")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(anonymous.statusCode()).isEqualTo(401);
		jdbc.sql("UPDATE store_memberships SET role = 'STAFF' WHERE store_id = :store AND user_id = :user")
				.param("store", f.store()).param("user", member.account().id()).update();
		assertThat(post(member, path(f) + "/cancel", Map.of("reason", "Motivo valido")).statusCode()).isEqualTo(403);
		assertThat(get(member, path(f)).statusCode()).isEqualTo(200);
		assertThat(orders.get(f.store(), f.order().summary().id(), f.user()).summary().status()).isEqualTo(Order.Status.DRAFT);
		assertThat(post(f.owner(), path(f) + "/cancel", Map.of("reason", "x".repeat(500))).statusCode()).isEqualTo(200);
		assertThat(post(member, path(f) + "/cancel", Map.of("reason", "x".repeat(500))).statusCode()).isEqualTo(403);
	}

	@Test
	void concurrentCancellationsReturnStockOnlyOnce() throws Exception {
		var f = fixture(2, 10);
		orders.confirm(f.store(), f.order().summary().id(), f.user());
		var results = race(() -> orders.cancel(f.store(), f.order().summary().id(), f.user(), "Mesmo motivo"),
				() -> orders.cancel(f.store(), f.order().summary().id(), f.user(), "Mesmo motivo"));
		assertThat(results).containsExactly(200, 200);
		for (UUID product : f.products()) {
			assertThat(balance(f, product)).isEqualTo(10);
			assertThat(inventory.history(f.store(), product, f.user(), 0, 20).content()).hasSize(3);
		}
	}

	@Test
	void concurrentConfirmAndCancelNeverLeaveCancelledOrderWithDeductedStock() throws Exception {
		var f = fixture(2, 10);
		var results = race(() -> orders.confirm(f.store(), f.order().summary().id(), f.user()),
				() -> orders.cancel(f.store(), f.order().summary().id(), f.user(), "Disputa de operacoes"));
		assertThat(results.get(0)).isIn(200, 409);
		assertThat(results.get(1)).isEqualTo(200);
		var finalOrder = orders.get(f.store(), f.order().summary().id(), f.user());
		assertThat(finalOrder.summary().status()).isEqualTo(Order.Status.CANCELLED);
		for (UUID product : f.products()) {
			assertThat(balance(f, product)).isEqualTo(10);
			assertThat(inventory.history(f.store(), product, f.user(), 0, 20).content())
					.hasSize(results.get(0) == 200 ? 3 : 1);
		}
	}

	@Test
	void concurrentReturnsFromDifferentOrdersLockProductsInTheSameOrder() throws Exception {
		var f = fixture(2, 10);
		var reversed = new ArrayList<>(f.items());
		java.util.Collections.reverse(reversed);
		var other = orders.create(f.store(), f.user(), UUID.randomUUID(), reversed);
		orders.confirm(f.store(), f.order().summary().id(), f.user());
		orders.confirm(f.store(), other.summary().id(), f.user());
		var results = race(() -> orders.cancel(f.store(), f.order().summary().id(), f.user(), "Primeiro pedido"),
				() -> orders.cancel(f.store(), other.summary().id(), f.user(), "Segundo pedido"));
		assertThat(results).containsExactly(200, 200);
		for (UUID product : f.products()) {
			assertThat(balance(f, product)).isEqualTo(10);
			assertThat(inventory.history(f.store(), product, f.user(), 0, 20).content()).hasSize(5);
		}
	}

	@Test
	void migratesExistingV4OrdersWithoutChangingSnapshotsStockOrAudit() {
		String schema = "cancel_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		try {
			Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("4").load().migrate();
			UUID store = UUID.randomUUID(), product = UUID.randomUUID();
			jdbc.sql("INSERT INTO " + schema + ".stores VALUES (:id, 'Loja existente', now())").param("id", store).update();
			jdbc.sql("INSERT INTO " + schema + ".catalog_products VALUES (:id,:store,'Produto','SKU','',19.90,'BRL',now())")
					.param("id", product).param("store", store).update();
			jdbc.sql("INSERT INTO " + schema + ".inventory_balances VALUES (:store,:product,7,1)")
					.param("store", store).param("product", product).update();
			for (String status : List.of("DRAFT", "CONFIRMED")) {
				UUID id = UUID.randomUUID();
				jdbc.sql("INSERT INTO " + schema + ".sales_orders VALUES (:store,:id,:status,'BRL',59.70,'creator',now(),"
						+ (status.equals("CONFIRMED") ? "'confirmer',now())" : "NULL,NULL)"))
						.param("store", store).param("id", id).param("status", status).update();
				jdbc.sql("INSERT INTO " + schema + ".sales_order_lines VALUES (:store,:id,:product,'Snapshot','SKU',3,19.90,59.70)")
						.param("store", store).param("id", id).param("product", product).update();
			}
			var before = jdbc.sql("SELECT * FROM " + schema + ".sales_order_lines ORDER BY order_id").query().listOfRows();
			var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
			flyway.migrate();
			flyway.migrate();
			assertThat(jdbc.sql("SELECT * FROM " + schema + ".sales_order_lines ORDER BY order_id").query().listOfRows()).isEqualTo(before);
			assertThat(jdbc.sql("SELECT quantity FROM " + schema + ".inventory_balances").query(Integer.class).single()).isEqualTo(7);
			assertThat(jdbc.sql("SELECT count(*) FROM " + schema + ".sales_orders WHERE cancelled_at IS NULL AND cancellation_reason IS NULL")
					.query(Long.class).single()).isEqualTo(2);
			assertThat(jdbc.sql("SELECT confirmed_by FROM " + schema + ".sales_orders WHERE status = 'CONFIRMED'")
					.query(String.class).single()).isEqualTo("confirmer");
			assertThatThrownBy(() -> jdbc.sql("UPDATE " + schema + ".sales_orders SET status = 'CANCELLED'").update())
					.isInstanceOf(DataIntegrityViolationException.class);
		} finally {
			jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
		}
	}

	private Fixture fixture(int count, int stock) throws Exception {
		var owner = actor();
		UUID store = UUID.fromString(createStore(owner, "Cancelamento teste").get("id").asText());
		var products = new ArrayList<UUID>();
		for (int i = 0; i < count; i++) {
			var response = post(owner, "/api/stores/" + store + "/products",
					Map.of("name", "Produto " + i, "sku", "P" + i, "price", "0.10"));
			assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
			UUID product = UUID.fromString(json.readTree(response.body()).get("id").asText());
			products.add(product);
			if (stock > 0) inventory.move(store, product, owner.account().id(), UUID.randomUUID(),
					MovementType.IN, BigDecimal.valueOf(stock), "Entrada teste");
		}
		products.sort(java.util.Comparator.comparing(UUID::toString));
		var items = products.stream().map(id -> new OrderService.RequestedItem(id, BigDecimal.valueOf(3))).toList();
		return new Fixture(owner, store, products, items, orders.create(store, owner.account().id(), UUID.randomUUID(), items));
	}

	private void move(Fixture f, UUID product, int quantity, MovementType type) {
		inventory.move(f.store(), product, f.user(), UUID.randomUUID(), type, BigDecimal.valueOf(quantity), "Ajuste teste");
	}

	private int balance(Fixture f, UUID product) {
		return inventory.balance(f.store(), product, f.user()).quantity();
	}

	private static String path(Fixture f) {
		return "/api/stores/" + f.store() + "/orders/" + f.order().summary().id();
	}

	private static List<Integer> race(Supplier<Order> first, Supplier<Order> second) throws Exception {
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var start = new CountDownLatch(1);
			var one = CompletableFuture.supplyAsync(() -> run(start, first), executor);
			var two = CompletableFuture.supplyAsync(() -> run(start, second), executor);
			start.countDown();
			return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
		}
	}

	private static int run(CountDownLatch start, Supplier<Order> operation) {
		try {
			start.await();
			operation.get();
			return 200;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		} catch (ResponseStatusException exception) {
			return exception.getStatusCode().value();
		}
	}

	private record Fixture(Actor owner, UUID store, List<UUID> products,
			List<OrderService.RequestedItem> items, Order order) {
		String user() { return owner.account().id(); }
	}
}
