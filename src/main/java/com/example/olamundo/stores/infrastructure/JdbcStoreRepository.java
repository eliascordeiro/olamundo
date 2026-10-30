package com.example.olamundo.stores.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.stores.application.StoreRepository;
import com.example.olamundo.stores.domain.Store;
import com.example.olamundo.stores.domain.StoreMembership;
import com.example.olamundo.stores.domain.StoreRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStoreRepository implements StoreRepository {
	private final JdbcClient jdbc;

	public JdbcStoreRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void create(Store store) {
		jdbc.sql("INSERT INTO stores (id, name, created_at) VALUES (:id, :name, :createdAt)")
				.param("id", store.id()).param("name", store.name())
				.param("createdAt", Timestamp.from(store.createdAt())).update();
	}

	@Override
	public void assignOwner(UUID storeId, UUID ownerId) {
		jdbc.sql("UPDATE stores SET owner_id = :owner WHERE id = :id").param("owner", ownerId).param("id", storeId).update();
	}

	@Override
	public Optional<UUID> findOwnerId(UUID storeId) {
		return jdbc.sql("SELECT owner_id FROM stores WHERE id = :id AND owner_id IS NOT NULL").param("id", storeId)
				.query((row, index) -> row.getObject("owner_id", UUID.class)).optional();
	}

	@Override
	public boolean addMember(StoreMembership member) {
		return jdbc.sql("""
				INSERT INTO store_memberships (store_id, user_id, role, created_at)
				VALUES (:storeId, :userId, :role, :createdAt)
				ON CONFLICT (store_id, user_id) DO NOTHING
				""")
				.param("storeId", member.storeId()).param("userId", member.userId())
				.param("role", member.role().name()).param("createdAt", Timestamp.from(member.createdAt()))
				.update() == 1;
	}

	@Override
	public Optional<StoreRole> findRole(UUID storeId, String userId) {
		return jdbc.sql("SELECT role FROM store_memberships WHERE store_id = :storeId AND user_id = :userId")
				.param("storeId", storeId).param("userId", userId)
				.query((row, index) -> StoreRole.valueOf(row.getString("role"))).optional();
	}

	@Override
	public Optional<Store> findStore(UUID storeId) {
		return jdbc.sql("SELECT id, name, created_at FROM stores WHERE id = :id").param("id", storeId)
				.query((row, index) -> new Store(row.getObject("id", UUID.class), row.getString("name"),
						row.getTimestamp("created_at").toInstant())).optional();
	}

	@Override
	public List<StoreAccess> findStores(String userId, int limit, long offset) {
		return jdbc.sql("""
				SELECT s.id, s.name, s.created_at, m.role
				FROM stores s JOIN store_memberships m ON m.store_id = s.id
				WHERE m.user_id = :userId ORDER BY s.created_at, s.id LIMIT :limit OFFSET :offset
				""").param("userId", userId).param("limit", limit).param("offset", offset)
				.query((row, index) -> new StoreAccess(row.getObject("id", UUID.class), row.getString("name"),
						row.getTimestamp("created_at").toInstant(), StoreRole.valueOf(row.getString("role")))).list();
	}

	@Override
	public List<StoreMembership> findMembers(UUID storeId, int limit, long offset) {
		return jdbc.sql("""
				SELECT store_id, user_id, role, created_at FROM store_memberships
				WHERE store_id = :storeId ORDER BY created_at, user_id LIMIT :limit OFFSET :offset
				""").param("storeId", storeId).param("limit", limit).param("offset", offset)
				.query((row, index) -> membership(row)).list();
	}

	private static StoreMembership membership(ResultSet row) throws SQLException {
		return new StoreMembership(row.getObject("store_id", UUID.class), row.getString("user_id"),
				StoreRole.valueOf(row.getString("role")), row.getTimestamp("created_at").toInstant());
	}
}
