package com.example.olamundo.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.example.olamundo.catalog.application.CatalogService;
import com.example.olamundo.inventory.domain.StockBalance;
import com.example.olamundo.inventory.domain.StockMovement;
import com.example.olamundo.inventory.domain.StockMovement.MovementType;
import com.example.olamundo.shared.PageSlice;
import com.example.olamundo.stores.application.StoreAuthorization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventoryService {
	private final InventoryRepository inventory;
	private final CatalogService catalog;
	private final StoreAuthorization authorization;

	public InventoryService(InventoryRepository inventory, CatalogService catalog, StoreAuthorization authorization) {
		this.inventory = inventory;
		this.catalog = catalog;
		this.authorization = authorization;
	}

	@Transactional(readOnly = true)
	public StockBalance balance(UUID storeId, UUID productId, String userId) {
		catalog.get(storeId, productId, userId);
		// Existing and newly created products have no physical stock until an entry is recorded.
		return inventory.balance(storeId, productId).orElseGet(() -> new StockBalance(storeId, productId, 0, 0));
	}

	@Transactional(readOnly = true)
	public PageSlice<StockMovement> history(UUID storeId, UUID productId, String userId, int page, int size) {
		catalog.get(storeId, productId, userId);
		PageSlice.validate(page, size);
		return PageSlice.from(inventory.history(storeId, productId, size + 1, (long) page * size), page, size);
	}

	@Transactional
	public StockMovement move(UUID storeId, UUID productId, String userId, UUID requestId,
			MovementType type, BigDecimal quantity, String reason) {
		authorization.requireStockWriter(storeId, userId);
		catalog.get(storeId, productId, userId);
		String normalizedReason = reason == null ? "" : reason.strip();
		if (requestId == null || type == null || quantity == null || quantity.signum() <= 0
				|| quantity.scale() > 0 || quantity.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0
				|| normalizedReason.length() < 2 || normalizedReason.length() > 500) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe tipo, quantidade inteira positiva e motivo de 2 a 500 caracteres.");
		}
		int units = quantity.intValueExact();
		// Serialize by product before checking idempotency, including concurrent first entries.
		var balance = inventory.lockBalance(storeId, productId);
		var previous = inventory.find(storeId, productId, requestId);
		if (previous.isPresent()) {
			var movement = previous.get();
			if (movement.type() != type || movement.quantity() != units || !movement.reason().equals(normalizedReason)
					|| !movement.userId().equals(userId)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador de operacao ja usado com outros dados.");
			}
			return movement;
		}
		long next = (long) balance.quantity() + (type == MovementType.IN ? units : -units);
		if (next < 0 || next > Integer.MAX_VALUE) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					next < 0 ? "Saldo insuficiente para esta saida." : "O saldo excederia o limite de 2147483647 unidades.");
		}
		var movement = new StockMovement(requestId, storeId, productId, balance.version() + 1, type,
				units, (int) next, normalizedReason, userId, Instant.now().truncatedTo(ChronoUnit.MICROS));
		inventory.save(movement);
		return movement;
	}
}
