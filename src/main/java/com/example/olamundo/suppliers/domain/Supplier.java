package com.example.olamundo.suppliers.domain;

import java.time.Instant;
import java.util.UUID;

public record Supplier(UUID id, UUID ownerId, String name, String document, String stateRegistration, String email,
		String phone, String fax, String contact, String zip, String street, String neighborhood, String city,
		String state, boolean active, long version, String createdBy, Instant createdAt, String updatedBy,
		Instant updatedAt) {
}
