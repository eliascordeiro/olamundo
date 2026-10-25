package com.example.olamundo.inventory.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.inventory.application.InventoryRepository;
import com.example.olamundo.inventory.domain.StockBalance;
import com.example.olamundo.inventory.domain.StockMovement;
import com.example.olamundo.inventory.domain.StockMovement.MovementType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInventoryRepository implements InventoryRepository {
	private final JdbcClient jdbc;

	public JdbcInventoryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<StockBalance> balance(UUID storeId, UUID productId) {
		return jdbc.sql("SELECT quantity, version FROM inventory_balances WHERE store_id = :store AND product_id = :product")
				.param("store", storeId).param("product", productId)
				.query((row, index) -> new StockBalance(storeId, productId, row.getInt("quantity"), row.getLong("version")))
				.optional();
	}

	@Override
	public StockBalance lockBalance(UUID storeId, UUID productId) {
		jdbc.sql("""
				INSERT INTO inventory_balances (store_id, product_id) VALUES (:store, :product)
				ON CONFLICT (store_id, product_id) DO NOTHING
				""").param("store", storeId).param("product", productId).update();
		return jdbc.sql("""
				SELECT quantity, version FROM inventory_balances
				WHERE store_id = :store AND product_id = :product FOR UPDATE
				""").param("store", storeId).param("product", productId)
				.query((row, index) -> new StockBalance(storeId, productId, row.getInt("quantity"), row.getLong("version")))
				.single();
	}

	@Override
	public Optional<StockMovement> find(UUID storeId, UUID productId, UUID requestId) {
		return jdbc.sql("""
				SELECT * FROM inventory_movements
				WHERE store_id = :store AND product_id = :product AND id = :id
				""").param("store", storeId).param("product", productId).param("id", requestId)
				.query((row, index) -> movement(row)).optional();
	}

	@Override
	public void save(StockMovement movement) {
		int updated = jdbc.sql("""
				UPDATE inventory_balances SET quantity = :balance, version = :sequence
				WHERE store_id = :store AND product_id = :product
				""").param("balance", movement.balanceAfter()).param("sequence", movement.sequence())
				.param("store", movement.storeId()).param("product", movement.productId()).update();
		if (updated != 1) {
			throw new IllegalStateException("Saldo bloqueado nao encontrado ao persistir movimentacao.");
		}
		jdbc.sql("""
				INSERT INTO inventory_movements
				(store_id, product_id, id, sequence, type, quantity, balance_after, reason, user_id, created_at)
				VALUES (:store, :product, :id, :sequence, :type, :quantity, :balance, :reason, :user, :created)
				""").param("store", movement.storeId()).param("product", movement.productId())
				.param("id", movement.id()).param("sequence", movement.sequence()).param("type", movement.type().name())
				.param("quantity", movement.quantity()).param("balance", movement.balanceAfter())
				.param("reason", movement.reason()).param("user", movement.userId())
				.param("created", Timestamp.from(movement.createdAt())).update();
	}

	@Override
	public List<StockMovement> history(UUID storeId, UUID productId, int limit, long offset) {
		return jdbc.sql("""
				SELECT * FROM inventory_movements WHERE store_id = :store AND product_id = :product
				ORDER BY sequence DESC LIMIT :limit OFFSET :offset
				""").param("store", storeId).param("product", productId).param("limit", limit).param("offset", offset)
				.query((row, index) -> movement(row)).list();
	}

	private static StockMovement movement(ResultSet row) throws SQLException {
		return new StockMovement(row.getObject("id", UUID.class), row.getObject("store_id", UUID.class),
				row.getObject("product_id", UUID.class), row.getLong("sequence"), MovementType.valueOf(row.getString("type")),
				row.getInt("quantity"), row.getInt("balance_after"), row.getString("reason"), row.getString("user_id"),
				row.getTimestamp("created_at").toInstant());
	}
}
