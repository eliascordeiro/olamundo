package com.example.olamundo.platform.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.platform.application.PlatformOwnerRepository;
import com.example.olamundo.platform.domain.PlatformOwner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPlatformOwnerRepository implements PlatformOwnerRepository {
	private final JdbcClient jdbc;

	public JdbcPlatformOwnerRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean create(UUID id, String name, String email, String actorId, Instant now) {
		return jdbc.sql("""
				INSERT INTO platform_owners (id, name, registration_email, status, created_by, created_at, updated_at)
				VALUES (:id, :name, :email, 'PENDING', :actor, :now, :now)
				ON CONFLICT (id) DO NOTHING
				""").param("id", id).param("name", name).param("email", email)
				.param("actor", actorId).param("now", Timestamp.from(now)).update() == 1;
	}

	@Override
	public boolean matchesRegistration(UUID id, String name, String email, String actorId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM platform_owners WHERE id = :id AND name = :name
				AND registration_email IS NOT DISTINCT FROM CAST(:email AS VARCHAR) AND created_by = :actor)
				""").param("id", id).param("name", name).param("email", email)
				.param("actor", actorId).query(Boolean.class).single();
	}

	@Override
	public Optional<PlatformOwner> find(UUID id) {
		return jdbc.sql("SELECT * FROM platform_owners WHERE id = :id").param("id", id).query(this::map).optional();
	}

	@Override
	public boolean existsActiveByUserId(String userId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform_owners WHERE user_id = :user AND status = 'ACTIVE')")
				.param("user", userId).query(Boolean.class).single();
	}

	@Override
	public Optional<UUID> findActiveIdByUserId(String userId) {
		return jdbc.sql("SELECT id FROM platform_owners WHERE user_id = :user AND status = 'ACTIVE'")
				.param("user", userId).query((row, index) -> row.getObject("id", UUID.class)).optional();
	}

	@Override
	public List<PlatformOwner> list(int limit, long offset) {
		return jdbc.sql("SELECT * FROM platform_owners ORDER BY created_at, id LIMIT :limit OFFSET :offset")
				.param("limit", limit).param("offset", offset).query(this::map).list();
	}

	@Override
	public Optional<PlatformOwner> markInvited(UUID id, String email, Instant now) {
		return jdbc.sql("""
				UPDATE platform_owners SET email = :email, status = 'INVITED', updated_at = :now
				WHERE id = :id AND status <> 'ACTIVE' RETURNING *
				""").param("id", id).param("email", email).param("now", Timestamp.from(now)).query(this::map).optional();
	}

	@Override
	public Optional<PlatformOwner> activate(UUID id, String email, String userId, Instant now) {
		return jdbc.sql("""
				UPDATE platform_owners SET email = :email, user_id = :user, status = 'ACTIVE', updated_at = :now
				WHERE id = :id AND status <> 'ACTIVE' RETURNING *
				""").param("id", id).param("email", email).param("user", userId)
				.param("now", Timestamp.from(now)).query(this::map).optional();
	}

	@Override
	public void replaceInvite(UUID ownerId, String tokenHash, Instant expiresAt, Instant now) {
		jdbc.sql("DELETE FROM platform_owner_invites WHERE owner_id = :owner AND used_at IS NULL")
				.param("owner", ownerId).update();
		jdbc.sql("""
				INSERT INTO platform_owner_invites (token_hash, owner_id, expires_at, created_at)
				VALUES (:hash, :owner, :expires, :now)
				""").param("hash", tokenHash).param("owner", ownerId)
				.param("expires", Timestamp.from(expiresAt)).param("now", Timestamp.from(now)).update();
	}

	@Override
	public Optional<PendingInvite> lockInvite(String tokenHash, Instant now) {
		return jdbc.sql("""
				SELECT i.owner_id, o.email FROM platform_owner_invites i
				JOIN platform_owners o ON o.id = i.owner_id
				WHERE i.token_hash = :hash AND i.used_at IS NULL AND i.expires_at > :now
				AND o.status = 'INVITED' AND o.email IS NOT NULL
				FOR UPDATE OF i, o
				""").param("hash", tokenHash).param("now", Timestamp.from(now))
				.query((row, rowNum) -> new PendingInvite(row.getObject("owner_id", UUID.class), row.getString("email")))
				.optional();
	}

	@Override
	public void consumeInvite(String tokenHash, Instant now) {
		jdbc.sql("UPDATE platform_owner_invites SET used_at = :now WHERE token_hash = :hash")
				.param("hash", tokenHash).param("now", Timestamp.from(now)).update();
	}

	private PlatformOwner map(ResultSet row, int rowNum) throws SQLException {
		return new PlatformOwner(row.getObject("id", UUID.class), row.getString("name"), row.getString("email"),
				PlatformOwner.Status.valueOf(row.getString("status")), row.getString("user_id"),
				row.getString("created_by"), row.getTimestamp("created_at").toInstant(),
				row.getTimestamp("updated_at").toInstant());
	}
}
