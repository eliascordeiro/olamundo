package com.example.olamundo.inventory.domain;

import java.time.Instant;
import java.util.UUID;

public record StockMovement(UUID id, UUID storeId, UUID productId, long sequence, MovementType type,
		int quantity, int balanceAfter, String reason, String userId, Instant createdAt) {
	public enum MovementType { IN, OUT }
}
