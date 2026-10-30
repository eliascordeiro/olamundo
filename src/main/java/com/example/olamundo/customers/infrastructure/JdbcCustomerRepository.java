package com.example.olamundo.customers.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.customers.application.CustomerRepository;
import com.example.olamundo.customers.domain.Customer;
import com.example.olamundo.customers.domain.CustomerData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCustomerRepository implements CustomerRepository {
	private final JdbcClient jdbc;

	public JdbcCustomerRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean create(UUID ownerId, UUID id, CustomerData data, String actorId, Instant now) {
		var statement = jdbc.sql("""
				INSERT INTO customers (id, owner_id, name, registration_name, document, rg, state_registration,
				birth_date, phone, mobile, email, zip, street, number, complement, neighborhood, city, state, ibge,
				created_by, created_at, updated_by, updated_at)
				VALUES (:id, :owner, :name, :name, :document, :rg, :stateRegistration, :birthDate, :phone, :mobile,
				:email, :zip, :street, :number, :complement, :neighborhood, :city, :state, :ibge,
				:actor, :now, :actor, :now)
				ON CONFLICT (id) DO NOTHING
				""").param("id", id).param("owner", ownerId).param("actor", actorId).param("now", Timestamp.from(now));
		return bind(statement, data).update() == 1;
	}

	@Override
	public boolean matchesRegistration(UUID ownerId, UUID id, String name, String actorId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM customers WHERE owner_id = :owner AND id = :id
				AND registration_name = :name AND created_by = :actor)
				""").param("owner", ownerId).param("id", id).param("name", name).param("actor", actorId)
				.query(Boolean.class).single();
	}

	@Override
	public Optional<Customer> find(UUID ownerId, UUID id) {
		return jdbc.sql("SELECT * FROM customers WHERE owner_id = :owner AND id = :id")
				.param("owner", ownerId).param("id", id).query(this::map).optional();
	}

	@Override
	public List<Customer> list(UUID ownerId, String query, int limit, long offset) {
		if (query == null || query.isBlank()) {
			return jdbc.sql("""
					SELECT * FROM customers WHERE owner_id = :owner
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
				OR regexp_replace(coalesce(phone, ''), '\\D', '', 'g') LIKE '%' || :digits || '%'
				OR regexp_replace(coalesce(mobile, ''), '\\D', '', 'g') LIKE '%' || :digits || '%')
				"""
				: "lower(name) LIKE :like ESCAPE '\\'";
		var statement = jdbc.sql("SELECT * FROM customers WHERE owner_id = :owner AND " + condition
				+ " ORDER BY lower(name), id LIMIT :limit OFFSET :offset")
				.param("owner", ownerId).param("limit", limit).param("offset", offset);
		statement = numeric ? statement.param("digits", digits) : statement.param("like", like);
		return statement.query(this::map).list();
	}

	@Override
	public Optional<Customer> update(UUID ownerId, UUID id, CustomerData data, long version, String actorId, Instant now) {
		var statement = jdbc.sql("""
				UPDATE customers SET name = :name, document = :document, rg = :rg, state_registration = :stateRegistration,
				birth_date = :birthDate, phone = :phone, mobile = :mobile, email = :email, zip = :zip, street = :street,
				number = :number, complement = :complement, neighborhood = :neighborhood, city = :city, state = :state,
				ibge = :ibge, version = version + 1, updated_by = :actor, updated_at = :now
				WHERE owner_id = :owner AND id = :id AND version = :version RETURNING *
				""").param("owner", ownerId).param("id", id).param("version", version)
				.param("actor", actorId).param("now", Timestamp.from(now));
		return bind(statement, data).query(this::map).optional();
	}

	private static StatementSpec bind(StatementSpec statement, CustomerData data) {
		return statement.param("name", data.name()).param("document", data.document()).param("rg", data.rg())
				.param("stateRegistration", data.stateRegistration())
				.param("birthDate", data.birthDate() == null ? null : Date.valueOf(data.birthDate()))
				.param("phone", data.phone()).param("mobile", data.mobile()).param("email", data.email())
				.param("zip", data.zip()).param("street", data.street()).param("number", data.number())
				.param("complement", data.complement()).param("neighborhood", data.neighborhood())
				.param("city", data.city()).param("state", data.state()).param("ibge", data.ibge());
	}

	private Customer map(ResultSet row, int rowNum) throws SQLException {
		Date birth = row.getDate("birth_date");
		return new Customer(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class), row.getString("name"),
				row.getString("document"), row.getString("rg"), row.getString("state_registration"),
				birth == null ? null : birth.toLocalDate(), row.getString("phone"), row.getString("mobile"),
				row.getString("email"), row.getString("zip"), row.getString("street"), row.getString("number"),
				row.getString("complement"), row.getString("neighborhood"), row.getString("city"),
				row.getString("state"), row.getString("ibge"), row.getLong("version"), row.getString("created_by"),
				row.getTimestamp("created_at").toInstant(), row.getString("updated_by"),
				row.getTimestamp("updated_at").toInstant());
	}
}
