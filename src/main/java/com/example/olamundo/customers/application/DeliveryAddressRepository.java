package com.example.olamundo.customers.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.customers.domain.DeliveryAddress;
import com.example.olamundo.customers.domain.DeliveryAddressData;

public interface DeliveryAddressRepository {
	boolean create(UUID ownerId, UUID customerId, UUID id, DeliveryAddressData data, String actorId, Instant now);

	boolean matchesRegistration(UUID ownerId, UUID customerId, UUID id, String street, String actorId);

	Optional<DeliveryAddress> find(UUID ownerId, UUID customerId, UUID id);

	List<DeliveryAddress> list(UUID ownerId, UUID customerId);

	Optional<DeliveryAddress> update(UUID ownerId, UUID customerId, UUID id, DeliveryAddressData data, long version,
			String actorId, Instant now);

	boolean delete(UUID ownerId, UUID customerId, UUID id, long version);
}
