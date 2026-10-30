package com.example.olamundo.catalog.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.catalog.application.ProductRepository;
import com.example.olamundo.catalog.domain.Product;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductRepository implements ProductRepository {
	private final JdbcClient jdbc;

	public JdbcProductRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean insert(Product product) {
		return jdbc.sql("""
				INSERT INTO catalog_products (id, store_id, name, sku, description, price, currency, created_at)
				VALUES (:id, :storeId, :name, :sku, :description, :price, :currency, :createdAt)
				ON CONFLICT (store_id, sku) DO NOTHING
				""").param("id", product.id()).param("storeId", product.storeId()).param("name", product.name())
				.param("sku", product.sku()).param("description", product.description()).param("price", product.price())
				.param("currency", product.currency()).param("createdAt", Timestamp.from(product.createdAt()))
				.update() == 1;
	}

	@Override
	public List<Product> list(UUID storeId, int limit, long offset) {
		return jdbc.sql("""
				SELECT p.*, g.name AS group_name, s.name AS subgroup_name FROM catalog_products p
				LEFT JOIN product_groups g ON g.id = p.group_id
				LEFT JOIN product_subgroups s ON s.id = p.subgroup_id
				WHERE p.store_id = :storeId
				ORDER BY p.created_at, p.id LIMIT :limit OFFSET :offset
				""").param("storeId", storeId).param("limit", limit).param("offset", offset)
				.query((row, index) -> product(row)).list();
	}

	@Override
	public Optional<Product> find(UUID storeId, UUID productId) {
		return jdbc.sql("""
				SELECT p.*, g.name AS group_name, s.name AS subgroup_name FROM catalog_products p
				LEFT JOIN product_groups g ON g.id = p.group_id
				LEFT JOIN product_subgroups s ON s.id = p.subgroup_id
				WHERE p.store_id = :storeId AND p.id = :productId
				""")
				.param("storeId", storeId).param("productId", productId)
				.query((row, index) -> product(row)).optional();
	}

	private static Product product(ResultSet row) throws SQLException {
		return new Product(row.getObject("id", UUID.class), row.getObject("store_id", UUID.class),
				row.getString("name"), row.getString("sku"), row.getString("description"),
				row.getBigDecimal("price"), row.getString("currency"), row.getTimestamp("created_at").toInstant(),
				row.getObject("group_id", UUID.class), row.getString("group_name"),
				row.getObject("subgroup_id", UUID.class), row.getString("subgroup_name"));
	}
}
