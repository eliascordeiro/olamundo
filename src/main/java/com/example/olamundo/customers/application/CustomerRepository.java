package com.example.olamundo.customers.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.customers.domain.Customer;
import com.example.olamundo.customers.domain.CustomerData;

public interface CustomerRepository {
	boolean create(UUID ownerId, UUID id, CustomerData data, String actorId, Instant now);

	boolean matchesRegistration(UUID ownerId, UUID id, String name, String actorId);

	Optional<Customer> find(UUID ownerId, UUID id);

	List<Customer> list(UUID ownerId, String query, int limit, long offset);

	Optional<Customer> update(UUID ownerId, UUID id, CustomerData data, long version, String actorId, Instant now);
}
