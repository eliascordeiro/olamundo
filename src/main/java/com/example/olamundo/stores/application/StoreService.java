package com.example.olamundo.stores.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.olamundo.auth.AccountDirectory;
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

	public StoreService(StoreRepository stores, AccountDirectory accounts) {
		this.stores = stores;
		this.accounts = accounts;
	}

	@Transactional
	public Store create(String userId, String name) {
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
	public Page<StoreRepository.StoreAccess> list(String userId, int page, int size) {
		validatePage(page, size);
		return slice(stores.findStores(userId, size + 1, (long) page * size), page, size);
	}

	@Transactional(readOnly = true)
	public StoreRepository.StoreAccess get(UUID storeId, String userId) {
		var role = requireMembership(storeId, userId);
		var store = stores.findStore(storeId).orElseThrow(StoreService::notFound);
		return new StoreRepository.StoreAccess(store.id(), store.name(), store.createdAt(), role);
	}

	@Transactional(readOnly = true)
	public Page<StoreMembership> members(UUID storeId, String userId, int page, int size) {
		requireOwner(storeId, userId);
		validatePage(page, size);
		return slice(stores.findMembers(storeId, size + 1, (long) page * size), page, size);
	}

	@Transactional
	public StoreMembership addMember(UUID storeId, String actorId, String email, StoreRole role) {
		requireOwner(storeId, actorId);
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

	private StoreRole requireMembership(UUID storeId, String userId) {
		// Do not disclose whether another tenant's store exists.
		return stores.findRole(storeId, userId).orElseThrow(StoreService::notFound);
	}

	private void requireOwner(UUID storeId, String userId) {
		if (requireMembership(storeId, userId) != StoreRole.OWNER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Somente o proprietario pode gerenciar membros.");
		}
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja nao encontrada.");
	}

	private static void validatePage(int page, int size) {
		if (page < 0 || size < 1 || size > 100) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use page >= 0 e size entre 1 e 100.");
		}
	}

	private static <T> Page<T> slice(List<T> results, int page, int size) {
		return new Page<>(List.copyOf(results.subList(0, Math.min(results.size(), size))),
				page, size, results.size() > size);
	}

	public record Page<T>(List<T> content, int page, int size, boolean hasNext) {
	}
}
