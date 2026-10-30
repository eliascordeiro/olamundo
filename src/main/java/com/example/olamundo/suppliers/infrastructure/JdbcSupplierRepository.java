package com.example.olamundo.suppliers.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.suppliers.application.SupplierRepository;
import com.example.olamundo.suppliers.domain.Supplier;
import com.example.olamundo.suppliers.domain.SupplierData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSupplierRepository implements SupplierRepository {
	private final JdbcClient jdbc;

	public JdbcSupplierRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean create(UUID ownerId, UUID id, SupplierData data, String actorId, Instant now) {
		var statement = jdbc.sql("""
				INSERT INTO suppliers (id, owner_id, name, registration_name, document, state_registration, email, phone,
				fax, contact, zip, street, neighborhood, city, state, active, created_by, created_at, updated_by, updated_at)
				VALUES (:id, :owner, :name, :name, :document, :stateRegistration, :email, :phone, :fax, :contact, :zip,
				:street, :neighborhood, :city, :state, :active, :actor, :now, :actor, :now)
				ON CONFLICT (id) DO NOTHING
				""").param("id", id).param("owner", ownerId).param("actor", actorId).param("now", Timestamp.from(now));
		return bind(statement, data).update() == 1;
	}

	@Override
	public boolean matchesRegistration(UUID ownerId, UUID id, String name, String actorId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM suppliers WHERE owner_id = :owner AND id = :id
				AND registration_name = :name AND created_by = :actor)
				""").param("owner", ownerId).param("id", id).param("name", name).param("actor", actorId)
				.query(Boolean.class).single();
	}

	@Override
	public Optional<Supplier> find(UUID ownerId, UUID id) {
		return jdbc.sql("SELECT * FROM suppliers WHERE owner_id = :owner AND id = :id")
				.param("owner", ownerId).param("id", id).query(this::map).optional();
	}

	@Override
	public List<Supplier> list(UUID ownerId, String query, int limit, long offset) {
		if (query == null || query.isBlank()) {
			return jdbc.sql("""
					SELECT * FROM suppliers WHERE owner_id = :owner
					ORDER BY lower(name), id LIMIT :limit OFFSET :offset
					""").param("owner", ownerId).param("limit", limit).param("offset", offset).query(this::map).list();
		}
		String text = query.strip();
		if (text.length() > 100) {
			text = text.substring(0, 100);
		}
		String digits = text.replaceAll("\\D", "");
		boolean numeric = digits.length() >= 3 && text.matches("[0-9 ()+./-]+");
		String like = "%" + text.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		String condition = numeric
				? """
				(document LIKE :digits || '%'
				OR regexp_replace(coalesce(phone, ''), '\\D', '', 'g') LIKE '%' || :digits || '%')
				"""
				: "lower(name) LIKE :like ESCAPE '\\'";
		var statement = jdbc.sql("SELECT * FROM suppliers WHERE owner_id = :owner AND " + condition
				+ " ORDER BY lower(name), id LIMIT :limit OFFSET :offset")
				.param("owner", ownerId).param("limit", limit).param("offset", offset);
		statement = numeric ? statement.param("digits", digits) : statement.param("like", like);
		return statement.query(this::map).list();
	}

	@Override
	public Optional<Supplier> update(UUID ownerId, UUID id, SupplierData data, long version, String actorId, Instant now) {
		var statement = jdbc.sql("""
				UPDATE suppliers SET name = :name, document = :document, state_registration = :stateRegistration,
				email = :email, phone = :phone, fax = :fax, contact = :contact, zip = :zip, street = :street,
				neighborhood = :neighborhood, city = :city, state = :state, active = :active,
				version = version + 1, updated_by = :actor, updated_at = :now
				WHERE owner_id = :owner AND id = :id AND version = :version RETURNING *
				""").param("owner", ownerId).param("id", id).param("version", version)
				.param("actor", actorId).param("now", Timestamp.from(now));
		return bind(statement, data).query(this::map).optional();
	}

	private static StatementSpec bind(StatementSpec statement, SupplierData data) {
		return statement.param("name", data.name()).param("document", data.document())
				.param("stateRegistration", data.stateRegistration()).param("email", data.email())
				.param("phone", data.phone()).param("fax", data.fax()).param("contact", data.contact())
				.param("zip", data.zip()).param("street", data.street()).param("neighborhood", data.neighborhood())
				.param("city", data.city()).param("state", data.state()).param("active", data.active());
	}

	private Supplier map(ResultSet row, int rowNum) throws SQLException {
		return new Supplier(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class), row.getString("name"),
				row.getString("document"), row.getString("state_registration"), row.getString("email"),
				row.getString("phone"), row.getString("fax"), row.getString("contact"), row.getString("zip"),
				row.getString("street"), row.getString("neighborhood"), row.getString("city"), row.getString("state"),
				row.getBoolean("active"), row.getLong("version"), row.getString("created_by"),
				row.getTimestamp("created_at").toInstant(), row.getString("updated_by"),
				row.getTimestamp("updated_at").toInstant());
	}
}
