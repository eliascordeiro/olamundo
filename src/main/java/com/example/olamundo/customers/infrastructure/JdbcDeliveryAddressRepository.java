package com.example.olamundo.customers.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.customers.application.DeliveryAddressRepository;
import com.example.olamundo.customers.domain.DeliveryAddress;
import com.example.olamundo.customers.domain.DeliveryAddressData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeliveryAddressRepository implements DeliveryAddressRepository {
	private final JdbcClient jdbc;

	public JdbcDeliveryAddressRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean create(UUID ownerId, UUID customerId, UUID id, DeliveryAddressData data, String actorId, Instant now) {
		var statement = jdbc.sql("""
				INSERT INTO customer_delivery_addresses (id, owner_id, customer_id, registration_name, zip, street, number,
				complement, neighborhood, city, state, created_by, created_at, updated_by, updated_at)
				VALUES (:id, :owner, :customer, :street, :zip, :street, :number, :complement, :neighborhood, :city, :state,
				:actor, :now, :actor, :now)
				ON CONFLICT (id) DO NOTHING
				""").param("id", id).param("owner", ownerId).param("customer", customerId).param("actor", actorId)
				.param("now", Timestamp.from(now));
		return bind(statement, data).update() == 1;
	}

	@Override
	public boolean matchesRegistration(UUID ownerId, UUID customerId, UUID id, String street, String actorId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM customer_delivery_addresses WHERE owner_id = :owner AND customer_id = :customer
				AND id = :id AND registration_name = :street AND created_by = :actor)
				""").param("owner", ownerId).param("customer", customerId).param("id", id).param("street", street)
				.param("actor", actorId).query(Boolean.class).single();
	}

	@Override
	public Optional<DeliveryAddress> find(UUID ownerId, UUID customerId, UUID id) {
		return jdbc.sql("SELECT * FROM customer_delivery_addresses WHERE owner_id = :owner AND customer_id = :customer AND id = :id")
				.param("owner", ownerId).param("customer", customerId).param("id", id).query(this::map).optional();
	}

	@Override
	public List<DeliveryAddress> list(UUID ownerId, UUID customerId) {
		return jdbc.sql("SELECT * FROM customer_delivery_addresses WHERE owner_id = :owner AND customer_id = :customer ORDER BY created_at, id")
				.param("owner", ownerId).param("customer", customerId).query(this::map).list();
	}

	@Override
	public Optional<DeliveryAddress> update(UUID ownerId, UUID customerId, UUID id, DeliveryAddressData data, long version,
			String actorId, Instant now) {
		var statement = jdbc.sql("""
				UPDATE customer_delivery_addresses SET zip = :zip, street = :street, number = :number,
				complement = :complement, neighborhood = :neighborhood, city = :city, state = :state,
				version = version + 1, updated_by = :actor, updated_at = :now
				WHERE owner_id = :owner AND customer_id = :customer AND id = :id AND version = :version RETURNING *
				""").param("owner", ownerId).param("customer", customerId).param("id", id).param("version", version)
				.param("actor", actorId).param("now", Timestamp.from(now));
		return bind(statement, data).query(this::map).optional();
	}

	@Override
	public boolean delete(UUID ownerId, UUID customerId, UUID id, long version) {
		return jdbc.sql("DELETE FROM customer_delivery_addresses WHERE owner_id = :owner AND customer_id = :customer AND id = :id AND version = :version")
				.param("owner", ownerId).param("customer", customerId).param("id", id).param("version", version).update() == 1;
	}

	private static StatementSpec bind(StatementSpec statement, DeliveryAddressData data) {
		return statement.param("zip", data.zip()).param("street", data.street()).param("number", data.number())
				.param("complement", data.complement()).param("neighborhood", data.neighborhood())
				.param("city", data.city()).param("state", data.state());
	}

	private DeliveryAddress map(ResultSet row, int rowNum) throws SQLException {
		return new DeliveryAddress(row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class),
				row.getString("zip"), row.getString("street"), row.getString("number"), row.getString("complement"),
				row.getString("neighborhood"), row.getString("city"), row.getString("state"), row.getLong("version"),
				row.getString("created_by"), row.getTimestamp("created_at").toInstant(), row.getString("updated_by"),
				row.getTimestamp("updated_at").toInstant());
	}
}
