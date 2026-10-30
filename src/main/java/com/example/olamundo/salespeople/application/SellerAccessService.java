package com.example.olamundo.salespeople.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.example.olamundo.auth.AccountDirectory;
import com.example.olamundo.auth.AuthService;
import com.example.olamundo.salespeople.domain.Salesperson;
import com.example.olamundo.stores.application.StoreAuthorization;
import com.example.olamundo.stores.application.StoreRepository;
import com.example.olamundo.stores.domain.StoreMembership;
import com.example.olamundo.stores.domain.StoreRole;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Concede acesso as lojas de um proprietario a um vendedor, direto ou por convite de uso unico. */
@Service
public class SellerAccessService {
	private static final Duration INVITE_TTL = Duration.ofDays(7);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SalespersonRepository salespeople;
	private final SellerInviteRepository invites;
	private final StoreAuthorization authorization;
	private final StoreRepository stores;
	private final AccountDirectory accounts;
	private final AuthService auth;

	public SellerAccessService(SalespersonRepository salespeople, SellerInviteRepository invites,
			StoreAuthorization authorization, StoreRepository stores, AccountDirectory accounts, AuthService auth) {
		this.salespeople = salespeople;
		this.invites = invites;
		this.authorization = authorization;
		this.stores = stores;
		this.accounts = accounts;
		this.auth = auth;
	}

	@Transactional
	public AccessResult grant(UUID storeId, String actorId, UUID sellerId, String email, long version,
			List<StoreGrant> requested) {
		authorization.requireOwner(storeId, actorId);
		var seller = salespeople.find(storeId, sellerId).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Vendedor nao encontrado nesta loja."));
		String normalizedEmail = normalizeEmail(email);
		var grants = validate(storeId, actorId, requested);
		var userId = accounts.findUserIdByEmail(normalizedEmail);
		var now = Instant.now();
		if (userId.isPresent()) {
			var items = grants.stream().map(grant -> grant.storeId().equals(storeId)
					? new Item(grant.storeId(), grant.role(), sellerId, version)
					: new Item(grant.storeId(), grant.role(), null, null)).toList();
			apply(userId.get(), actorId, seller.name(), now, items);
			return new AccessResult(salespeople.find(storeId, sellerId).orElseThrow(), null, null);
		}
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
		var expiresAt = now.plus(INVITE_TTL);
		invites.replace(hash(token), normalizedEmail, seller.name(), actorId, expiresAt, now,
				grants.stream().map(grant -> new SellerInviteRepository.Grant(grant.storeId(), grant.role(),
						grant.storeId().equals(storeId) ? sellerId : null)).toList());
		return new AccessResult(seller, token, expiresAt);
	}

	@Transactional
	public Accepted accept(String token, String name, String password) {
		var now = Instant.now();
		String hash = hash(token);
		var invite = invites.lock(hash, now).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite invalido ou expirado. Peca um novo convite."));
		String userId = accounts.findUserIdByEmail(invite.email()).orElse(null);
		// O convite so vale para lojas que quem o emitiu ainda administra; confere antes de criar a conta.
		var items = new ArrayList<Item>();
		var storeNames = new ArrayList<String>();
		for (var grant : invites.grants(hash)) {
			if (stores.findRole(grant.storeId(), invite.createdBy()).orElse(null) == StoreRole.OWNER) {
				items.add(new Item(grant.storeId(), grant.role(), grant.salespersonId(), null));
				storeNames.add(stores.findStore(grant.storeId()).orElseThrow().name());
			}
		}
		if (items.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.GONE, "O proprietario nao administra mais estas lojas.");
		}
		if (userId == null) {
			auth.register(name, invite.email(), password);
			userId = accounts.findUserIdByEmail(invite.email()).orElseThrow(() ->
					new IllegalStateException("Account was not created for accepted invite"));
		}
		apply(userId, invite.createdBy(), invite.sellerName(), now, items);
		invites.consume(hash, now);
		return new Accepted(invite.email(), List.copyOf(storeNames));
	}

	private List<StoreGrant> validate(UUID storeId, String actorId, List<StoreGrant> requested) {
		if (requested == null || requested.isEmpty() || requested.size() > 20) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha de 1 a 20 lojas.");
		}
		var seen = new java.util.LinkedHashMap<UUID, StoreGrant>();
		for (var grant : requested) {
			if (grant.storeId() == null || (grant.role() != StoreRole.MANAGER && grant.role() != StoreRole.STAFF)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use o perfil Gerente ou Colaborador.");
			}
			if (seen.putIfAbsent(grant.storeId(), grant) != null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loja repetida na lista.");
			}
		}
		if (!seen.containsKey(storeId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A loja deste cadastro deve estar na lista.");
		}
		seen.keySet().forEach(id -> authorization.requireOwner(id, actorId));
		return List.copyOf(seen.values());
	}

	private void apply(String userId, String actorId, String name, Instant now, List<Item> items) {
		for (var item : items) {
			// Quem ja e membro mantem o perfil atual; este fluxo nunca rebaixa nem promove.
			stores.addMember(new StoreMembership(item.storeId(), userId, item.role(), now));
			Salesperson seller = item.sellerId() == null ? null
					: salespeople.find(item.storeId(), item.sellerId()).orElse(null);
			if (seller == null) {
				seller = salespeople.findByUser(item.storeId(), userId).orElse(null);
			}
			if (seller == null) {
				UUID id = UUID.randomUUID();
				salespeople.create(item.storeId(), id, name, actorId, now);
				seller = salespeople.find(item.storeId(), id).orElseThrow();
			}
			if (userId.equals(seller.userId())) continue;
			try {
				salespeople.link(item.storeId(), seller.id(), userId,
						item.version() != null ? item.version() : seller.version(), actorId, now)
						.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
								"Cadastro alterado em outra operacao. Atualize os vendedores e tente novamente."));
			} catch (DuplicateKeyException exception) {
				throw new ResponseStatusException(HttpStatus.CONFLICT,
						"Esta conta ja esta vinculada a outro vendedor de uma das lojas escolhidas.");
			}
		}
	}

	private static String normalizeEmail(String email) {
		if (email == null || email.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o e-mail da conta.");
		}
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private static byte[] randomBytes() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return bytes;
	}

	private static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record Item(UUID storeId, StoreRole role, UUID sellerId, Long version) {
	}

	public record StoreGrant(UUID storeId, StoreRole role) {
	}

	/** O token em claro so existe nesta resposta; nao e armazenado nem recuperavel depois. */
	public record AccessResult(Salesperson seller, String inviteToken, Instant expiresAt) {
	}

	public record Accepted(String email, List<String> stores) {
	}
}
