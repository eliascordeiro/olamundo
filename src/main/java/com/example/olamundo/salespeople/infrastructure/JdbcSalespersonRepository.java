package com.example.olamundo.salespeople.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.salespeople.application.SalespersonRepository;
import com.example.olamundo.salespeople.domain.Salesperson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSalespersonRepository implements SalespersonRepository {
	private final JdbcClient jdbc;

	public JdbcSalespersonRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean create(UUID storeId, UUID id, String name, String actorId, Instant now) {
		return jdbc.sql("""
				INSERT INTO salespeople (store_id, id, name, registration_name, created_by, created_at, updated_by, updated_at)
				VALUES (:store, :id, :name, :name, :actor, :now, :actor, :now)
				ON CONFLICT (store_id, id) DO NOTHING
				""").param("store", storeId).param("id", id).param("name", name)
				.param("actor", actorId).param("now", Timestamp.from(now)).update() == 1;
	}

	@Override
	public boolean matchesRegistration(UUID storeId, UUID id, String name, String actorId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM salespeople
				WHERE store_id = :store AND id = :id AND registration_name = :name AND created_by = :actor)
				""").param("store", storeId).param("id", id).param("name", name)
				.param("actor", actorId).query(Boolean.class).single();
	}

	@Override
	public Optional<Salesperson> find(UUID storeId, UUID id) {
		return jdbc.sql("SELECT * FROM salespeople WHERE store_id = :store AND id = :id")
				.param("store", storeId).param("id", id).query(this::map).optional();
	}

	@Override
	public List<Salesperson> list(UUID storeId, int limit, long offset) {
		return jdbc.sql("""
				SELECT * FROM salespeople WHERE store_id = :store
				ORDER BY created_at, id LIMIT :limit OFFSET :offset
				""").param("store", storeId).param("limit", limit).param("offset", offset).query(this::map).list();
	}

	@Override
	public Optional<Salesperson> update(UUID storeId, UUID id, String name, boolean active,
			long version, String actorId, Instant now) {
		return jdbc.sql("""
				UPDATE salespeople SET name = :name, active = :active, version = version + 1,
				updated_by = :actor, updated_at = :now
				WHERE store_id = :store AND id = :id AND version = :version RETURNING *
				""").param("store", storeId).param("id", id).param("name", name).param("active", active)
				.param("version", version).param("actor", actorId).param("now", Timestamp.from(now)).query(this::map).optional();
	}

	@Override
	public Optional<Salesperson> link(UUID storeId, UUID id, String userId, long version, String actorId, Instant now) {
		return jdbc.sql("""
				UPDATE salespeople SET user_id = :user, version = version + 1,
				updated_by = :actor, updated_at = :now
				WHERE store_id = :store AND id = :id AND version = :version RETURNING *
				""").param("store", storeId).param("id", id).param("user", userId)
				.param("version", version).param("actor", actorId).param("now", Timestamp.from(now)).query(this::map).optional();
	}

	private Salesperson map(ResultSet row, int rowNum) throws SQLException {
		return new Salesperson(row.getObject("id", UUID.class), row.getObject("store_id", UUID.class),
				row.getString("name"), row.getBoolean("active"), row.getString("user_id"), row.getLong("version"),
				row.getString("created_by"), row.getTimestamp("created_at").toInstant(),
				row.getString("updated_by"), row.getTimestamp("updated_at").toInstant());
	}
}
