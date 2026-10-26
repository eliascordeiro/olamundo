package com.example.olamundo.orders.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.orders.domain.Order;

public interface OrderRepository {
	boolean insertDraft(UUID storeId, UUID id, String userId, Instant createdAt);
	void saveLines(UUID storeId, UUID id, List<Order.Line> items, BigDecimal total);
	Optional<Order> find(UUID storeId, UUID id, boolean lock);
	List<Order.Summary> list(UUID storeId, int limit, long offset);
	void confirm(UUID storeId, UUID id, String userId, Instant confirmedAt);
	void cancel(UUID storeId, UUID id, String userId, Instant cancelledAt, String reason);
}
