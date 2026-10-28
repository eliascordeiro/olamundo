package com.example.olamundo.salespeople.domain;

import java.time.Instant;
import java.util.UUID;

public record Salesperson(UUID id, UUID storeId, String name, boolean active, String userId,
		long version, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
}
