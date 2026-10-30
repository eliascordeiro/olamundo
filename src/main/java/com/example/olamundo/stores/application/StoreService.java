package com.example.olamundo.stores.application;

import java.time.Instant;
import java.util.UUID;

import com.example.olamundo.auth.AccountDirectory;
import com.example.olamundo.platform.application.OwnerAccess;
import com.example.olamundo.shared.PageSlice;
import com.example.olamundo.stores.domain.Store;
import com.example.olamundo.stores.domain.StoreMembership;
import com.example.olamundo.stores.domain.StoreRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StoreService {
	private final StoreRepository stores;
	private final AccountDirectory accounts;
	private final StoreAuthorization authorization;
	private final OwnerAccess ownerAccess;

	public StoreService(StoreRepository stores, AccountDirectory accounts, StoreAuthorization authorization,
			OwnerAccess ownerAccess) {
		this.stores = stores;
		this.accounts = accounts;
		this.authorization = authorization;
		this.ownerAccess = ownerAccess;
	}

	@Transactional
	public Store create(String userId, String name) {
		ownerAccess.requireActiveOwner(userId);
		String normalizedName = name.strip();
		if (normalizedName.length() < 2 || normalizedName.length() > 100) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome da loja deve ter de 2 a 100 caracteres.");
		}
		var store = new Store(UUID.randomUUID(), normalizedName, Instant.now());
		stores.create(store);
		if (!stores.addMember(new StoreMembership(store.id(), userId, StoreRole.OWNER, store.createdAt()))) {
			throw new IllegalStateException("New store already has its owner membership");
		}
		return store;
	}

	@Transactional(readOnly = true)
	public PageSlice<StoreRepository.StoreAccess> list(String userId, int page, int size) {
		PageSlice.validate(page, size);
		return PageSlice.from(stores.findStores(userId, size + 1, (long) page * size), page, size);
	}

	@Transactional(readOnly = true)
	public StoreRepository.StoreAccess get(UUID storeId, String userId) {
		var role = authorization.requireMember(storeId, userId);
		var store = stores.findStore(storeId).orElseThrow(StoreService::notFound);
		return new StoreRepository.StoreAccess(store.id(), store.name(), store.createdAt(), role);
	}

	@Transactional(readOnly = true)
	public PageSlice<StoreMembership> members(UUID storeId, String userId, int page, int size) {
		authorization.requireOwner(storeId, userId);
		PageSlice.validate(page, size);
		return PageSlice.from(stores.findMembers(storeId, size + 1, (long) page * size), page, size);
	}

	@Transactional
	public StoreMembership addMember(UUID storeId, String actorId, String email, StoreRole role) {
		authorization.requireOwner(storeId, actorId);
		if (role == null || role == StoreRole.OWNER) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Nesta etapa, novos membros podem ser MANAGER ou STAFF.");
		}
		String memberId = accounts.findUserIdByEmail(email).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Conta nao encontrada. O usuario precisa se cadastrar."));
		var membership = new StoreMembership(storeId, memberId, role, Instant.now());
		if (!stores.addMember(membership)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Usuario ja participa desta loja.");
		}
		return membership;
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja nao encontrada.");
	}

}
