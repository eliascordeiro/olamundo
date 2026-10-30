package com.example.olamundo.stores.application;

import java.util.UUID;

import com.example.olamundo.stores.domain.StoreRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StoreAuthorization {
	private final StoreRepository stores;

	public StoreAuthorization(StoreRepository stores) {
		this.stores = stores;
	}

	public StoreRole requireMember(UUID storeId, String userId) {
		// Do not disclose whether another tenant's store exists.
		return stores.findRole(storeId, userId).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja nao encontrada."));
	}

	public void requireOwner(UUID storeId, String userId) {
		if (requireMember(storeId, userId) != StoreRole.OWNER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Somente o proprietario pode gerenciar membros.");
		}
	}

	public void requireCatalogWriter(UUID storeId, String userId) {
		requireWriter(storeId, userId, "Somente OWNER ou MANAGER pode cadastrar produtos.");
	}

	public void requireStockWriter(UUID storeId, String userId) {
		requireWriter(storeId, userId, "Somente OWNER ou MANAGER pode movimentar estoque.");
	}

	public void requireWriter(UUID storeId, String userId, String message) {
		var role = requireMember(storeId, userId);
		if (role != StoreRole.OWNER && role != StoreRole.MANAGER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					message);
		}
	}

	public void requireOrderWriter(UUID storeId, String userId) {
		requireWriter(storeId, userId, "Somente OWNER ou MANAGER pode criar ou confirmar pedidos.");
	}
}
