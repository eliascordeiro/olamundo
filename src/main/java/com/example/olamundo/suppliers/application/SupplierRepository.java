package com.example.olamundo.suppliers.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.suppliers.domain.Supplier;
import com.example.olamundo.suppliers.domain.SupplierData;

public interface SupplierRepository {
	boolean create(UUID ownerId, UUID id, SupplierData data, String actorId, Instant now);

	boolean matchesRegistration(UUID ownerId, UUID id, String name, String actorId);

	Optional<Supplier> find(UUID ownerId, UUID id);

	List<Supplier> list(UUID ownerId, String query, int limit, long offset);

	Optional<Supplier> update(UUID ownerId, UUID id, SupplierData data, long version, String actorId, Instant now);
}
