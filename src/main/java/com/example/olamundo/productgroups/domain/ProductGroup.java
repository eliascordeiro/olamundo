package com.example.olamundo.productgroups.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductGroup(UUID id, UUID ownerId, String name, String ncm, BigDecimal commission,
		BigDecimal assemblyCommission, BigDecimal assemblyDiscount, BigDecimal markup, BigDecimal pautaDiscount,
		BigDecimal flexibleDiscount, long version, String createdBy, Instant createdAt, String updatedBy,
		Instant updatedAt) {
}
