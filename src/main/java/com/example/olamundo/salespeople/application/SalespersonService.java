package com.example.olamundo.salespeople.application;

import java.time.Instant;
import java.util.UUID;

import com.example.olamundo.auth.AccountDirectory;
import com.example.olamundo.salespeople.domain.Salesperson;
import com.example.olamundo.shared.PageSlice;
import com.example.olamundo.stores.application.StoreAuthorization;
import com.example.olamundo.stores.application.StoreRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SalespersonService {
	private final SalespersonRepository salespeople;
	private final StoreAuthorization authorization;
	private final StoreRepository stores;
	private final AccountDirectory accounts;

	public SalespersonService(SalespersonRepository salespeople, StoreAuthorization authorization,
			StoreRepository stores, AccountDirectory accounts) {
		this.salespeople = salespeople;
		this.authorization = authorization;
		this.stores = stores;
		this.accounts = accounts;
	}

	@Transactional(readOnly = true)
	public PageSlice<Salesperson> list(UUID storeId, String actorId, int page, int size) {
		authorization.requireOwner(storeId, actorId);
		PageSlice.validate(page, size);
		return PageSlice.from(salespeople.list(storeId, size + 1, (long) page * size), page, size);
	}

	@Transactional(readOnly = true)
	public Salesperson get(UUID storeId, String actorId, UUID id) {
		authorization.requireOwner(storeId, actorId);
		return find(storeId, id);
	}

	@Transactional
	public Salesperson create(UUID storeId, String actorId, UUID requestId, String name) {
		authorization.requireOwner(storeId, actorId);
		String normalized = normalizeName(name);
		if (requestId == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe requestId UUID.");
		}
		if (!salespeople.create(storeId, requestId, normalized, actorId, Instant.now())
				&& !salespeople.matchesRegistration(storeId, requestId, normalized, actorId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador ja usado com outro cadastro.");
		}
		return find(storeId, requestId);
	}

	@Transactional
	public Salesperson update(UUID storeId, String actorId, UUID id, String name, boolean active, long version) {
		authorization.requireOwner(storeId, actorId);
		find(storeId, id);
		return salespeople.update(storeId, id, normalizeName(name), active, version, actorId, Instant.now())
				.orElseThrow(SalespersonService::stale);
	}

	@Transactional
	public Salesperson link(UUID storeId, String actorId, UUID id, String email, long version) {
		authorization.requireOwner(storeId, actorId);
		find(storeId, id);
		String userId = null;
		if (email != null && !email.isBlank()) {
			userId = accounts.findUserIdByEmail(email).filter(candidate -> stores.findRole(storeId, candidate).isPresent())
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
							"Conta nao encontrada entre os membros desta loja. Cadastre a conta e seu vinculo primeiro."));
		}
		try {
			return salespeople.link(storeId, id, userId, version, actorId, Instant.now())
					.orElseThrow(SalespersonService::stale);
		} catch (DuplicateKeyException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Esta conta ja esta vinculada a outro vendedor desta loja, inclusive inativo.");
		}
	}

	private Salesperson find(UUID storeId, UUID id) {
		return salespeople.find(storeId, id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Vendedor nao encontrado nesta loja."));
	}

	private static ResponseStatusException stale() {
		return new ResponseStatusException(HttpStatus.CONFLICT,
				"Cadastro alterado em outra operacao. Atualize os vendedores e confira os dados antes de salvar novamente.");
	}

	private static String normalizeName(String name) {
		String normalized = name == null ? "" : name.strip();
		if (normalized.length() < 2 || normalized.length() > 120) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do vendedor deve ter de 2 a 120 caracteres.");
		}
		return normalized;
	}
}
