package com.example.olamundo.salespeople.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.salespeople.domain.Salesperson;

public interface SalespersonRepository {
	boolean create(UUID storeId, UUID id, String name, String actorId, Instant now);
	boolean matchesRegistration(UUID storeId, UUID id, String name, String actorId);
	Optional<Salesperson> find(UUID storeId, UUID id);
	List<Salesperson> list(UUID storeId, int limit, long offset);
	Optional<Salesperson> update(UUID storeId, UUID id, String name, boolean active,
			long version, String actorId, Instant now);
	Optional<Salesperson> link(UUID storeId, UUID id, String userId, long version, String actorId, Instant now);
}
