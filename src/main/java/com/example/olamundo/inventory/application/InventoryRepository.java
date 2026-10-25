package com.example.olamundo.inventory.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.inventory.domain.StockBalance;
import com.example.olamundo.inventory.domain.StockMovement;

public interface InventoryRepository {
	Optional<StockBalance> balance(UUID storeId, UUID productId);
	StockBalance lockBalance(UUID storeId, UUID productId);
	Optional<StockMovement> find(UUID storeId, UUID productId, UUID requestId);
	void save(StockMovement movement);
	List<StockMovement> history(UUID storeId, UUID productId, int limit, long offset);
}
