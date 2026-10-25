package com.example.olamundo.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Product(UUID id, UUID storeId, String name, String sku, String description,
		BigDecimal price, String currency, Instant createdAt) {
}
