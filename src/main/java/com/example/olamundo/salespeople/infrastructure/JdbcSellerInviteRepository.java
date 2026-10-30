package com.example.olamundo.salespeople.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.salespeople.application.SellerInviteRepository;
import com.example.olamundo.stores.domain.StoreRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSellerInviteRepository implements SellerInviteRepository {
	private final JdbcClient jdbc;

	public JdbcSellerInviteRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void replace(String tokenHash, String email, String sellerName, String createdBy, Instant expiresAt,
			Instant now, List<Grant> grants) {
		jdbc.sql("DELETE FROM seller_invites WHERE email = :email AND created_by = :actor AND used_at IS NULL")
				.param("email", email).param("actor", createdBy).update();
		jdbc.sql("""
				INSERT INTO seller_invites (token_hash, email, seller_name, created_by, expires_at, created_at)
				VALUES (:hash, :email, :name, :actor, :expires, :now)
				""").param("hash", tokenHash).param("email", email).param("name", sellerName)
				.param("actor", createdBy).param("expires", Timestamp.from(expiresAt))
				.param("now", Timestamp.from(now)).update();
		for (Grant grant : grants) {
			jdbc.sql("""
					INSERT INTO seller_invite_grants (token_hash, store_id, role, salesperson_id)
					VALUES (:hash, :store, :role, :seller)
					""").param("hash", tokenHash).param("store", grant.storeId())
					.param("role", grant.role().name()).param("seller", grant.salespersonId()).update();
		}
	}

	@Override
	public Optional<Invite> lock(String tokenHash, Instant now) {
		return jdbc.sql("""
				SELECT email, seller_name, created_by FROM seller_invites
				WHERE token_hash = :hash AND used_at IS NULL AND expires_at > :now FOR UPDATE
				""").param("hash", tokenHash).param("now", Timestamp.from(now))
				.query((row, index) -> new Invite(row.getString("email"), row.getString("seller_name"),
						row.getString("created_by"))).optional();
	}

	@Override
	public List<Grant> grants(String tokenHash) {
		return jdbc.sql("SELECT store_id, role, salesperson_id FROM seller_invite_grants WHERE token_hash = :hash ORDER BY store_id")
				.param("hash", tokenHash)
				.query((row, index) -> new Grant(row.getObject("store_id", UUID.class),
						StoreRole.valueOf(row.getString("role")), row.getObject("salesperson_id", UUID.class))).list();
	}

	@Override
	public void consume(String tokenHash, Instant now) {
		jdbc.sql("UPDATE seller_invites SET used_at = :now WHERE token_hash = :hash")
				.param("hash", tokenHash).param("now", Timestamp.from(now)).update();
	}
}
