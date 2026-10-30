package com.example.olamundo.productgroups.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductSubgroup(UUID id, UUID ownerId, UUID groupId, String name, BigDecimal markup, BigDecimal margin,
		BigDecimal pautaDiscount, BigDecimal flexibleDiscount, long version, String createdBy, Instant createdAt,
		String updatedBy, Instant updatedAt) {
}
