package com.example.olamundo.stores.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Resolve a empresa (proprietario) de uma loja, validando antes o acesso do usuario a ela. */
@Service
public class CompanyAccess {
	private final StoreAuthorization authorization;
	private final StoreRepository stores;

	public CompanyAccess(StoreAuthorization authorization, StoreRepository stores) {
		this.authorization = authorization;
		this.stores = stores;
	}

	/** Qualquer membro da loja; nao membros recebem 404. */
	public UUID companyOf(UUID storeId, String userId) {
		authorization.requireMember(storeId, userId);
		return companyId(storeId);
	}

	/** Somente OWNER ou MANAGER da loja. */
	public UUID companyForWriter(UUID storeId, String userId, String message) {
		authorization.requireWriter(storeId, userId, message);
		return companyId(storeId);
	}

	private UUID companyId(UUID storeId) {
		return stores.findOwnerId(storeId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
				"Esta loja ainda nao pertence a uma empresa. Fale com o administrador da plataforma."));
	}
}
