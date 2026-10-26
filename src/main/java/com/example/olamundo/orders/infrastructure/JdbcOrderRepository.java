package com.example.olamundo.orders.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.orders.application.OrderRepository;
import com.example.olamundo.orders.domain.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrderRepository implements OrderRepository {
	private final JdbcClient jdbc;

	public JdbcOrderRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean insertDraft(UUID storeId, UUID id, String userId, Instant createdAt) {
		return jdbc.sql("""
				INSERT INTO sales_orders (store_id, id, status, currency, total, created_by, created_at)
				VALUES (:store, :id, 'DRAFT', 'BRL', 0, :user, :created)
				ON CONFLICT (store_id, id) DO NOTHING
				""").param("store", storeId).param("id", id).param("user", userId)
				.param("created", Timestamp.from(createdAt)).update() == 1;
	}

	@Override
	public void saveLines(UUID storeId, UUID id, List<Order.Line> items, BigDecimal total) {
		for (var line : items) {
			jdbc.sql("""
					INSERT INTO sales_order_lines
					(store_id, order_id, product_id, product_name, sku, quantity, unit_price, line_total)
					VALUES (:store, :order, :product, :name, :sku, :quantity, :price, :total)
					""").param("store", storeId).param("order", id).param("product", line.productId())
					.param("name", line.productName()).param("sku", line.sku()).param("quantity", line.quantity())
					.param("price", line.unitPrice()).param("total", line.lineTotal()).update();
		}
		requireOne(jdbc.sql("UPDATE sales_orders SET total = :total WHERE store_id = :store AND id = :id AND status = 'DRAFT'")
				.param("total", total).param("store", storeId).param("id", id).update());
	}

	@Override
	public Optional<Order> find(UUID storeId, UUID id, boolean lock) {
		var header = jdbc.sql("SELECT * FROM sales_orders WHERE store_id = :store AND id = :id" + (lock ? " FOR UPDATE" : ""))
				.param("store", storeId).param("id", id).query((row, index) -> summary(row)).optional();
		return header.map(summary -> {
			var items = jdbc.sql("""
					SELECT * FROM sales_order_lines WHERE store_id = :store AND order_id = :id ORDER BY product_id
					""").param("store", storeId).param("id", id).query((row, index) ->
					new Order.Line(row.getObject("product_id", UUID.class), row.getString("product_name"),
							row.getString("sku"), row.getInt("quantity"), row.getBigDecimal("unit_price"),
							row.getBigDecimal("line_total"))).list();
			return new Order(summary, items);
		});
	}

	@Override
	public List<Order.Summary> list(UUID storeId, int limit, long offset) {
		return jdbc.sql("""
				SELECT * FROM sales_orders WHERE store_id = :store ORDER BY created_at DESC, id DESC
				LIMIT :limit OFFSET :offset
				""").param("store", storeId).param("limit", limit).param("offset", offset)
				.query((row, index) -> summary(row)).list();
	}

	@Override
	public void confirm(UUID storeId, UUID id, String userId, Instant confirmedAt) {
		requireOne(jdbc.sql("""
				UPDATE sales_orders SET status = 'CONFIRMED', confirmed_by = :user, confirmed_at = :confirmed
				WHERE store_id = :store AND id = :id AND status = 'DRAFT'
				""").param("user", userId).param("confirmed", Timestamp.from(confirmedAt))
				.param("store", storeId).param("id", id).update());
	}

	@Override
	public void cancel(UUID storeId, UUID id, String userId, Instant cancelledAt, String reason) {
		requireOne(jdbc.sql("""
				UPDATE sales_orders SET status = 'CANCELLED', cancelled_by = :user,
				cancelled_at = :cancelled, cancellation_reason = :reason
				WHERE store_id = :store AND id = :id AND status IN ('DRAFT', 'CONFIRMED')
				""").param("user", userId).param("cancelled", Timestamp.from(cancelledAt)).param("reason", reason)
				.param("store", storeId).param("id", id).update());
	}

	private static void requireOne(int updated) {
		if (updated != 1) throw new IllegalStateException("Pedido esperado nao encontrado ao persistir alteracao.");
	}

	private static Order.Summary summary(ResultSet row) throws SQLException {
		var confirmed = row.getTimestamp("confirmed_at");
		var cancelled = row.getTimestamp("cancelled_at");
		return new Order.Summary(row.getObject("id", UUID.class), row.getObject("store_id", UUID.class),
				Order.Status.valueOf(row.getString("status")), row.getString("currency"), row.getBigDecimal("total"),
				row.getString("created_by"), row.getTimestamp("created_at").toInstant(), row.getString("confirmed_by"),
				confirmed == null ? null : confirmed.toInstant(), row.getString("cancelled_by"),
				cancelled == null ? null : cancelled.toInstant(), row.getString("cancellation_reason"));
	}
}
