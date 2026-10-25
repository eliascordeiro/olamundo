package com.example.olamundo.orders.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

public record Order(Summary summary, List<Line> items) {
	public enum Status { DRAFT, CONFIRMED }

	public record Summary(UUID id, UUID storeId, Status status, String currency,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
			String createdBy, Instant createdAt, String confirmedBy, Instant confirmedAt) {
	}

	public record Line(UUID productId, String productName, String sku, int quantity,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lineTotal) {
	}
}
