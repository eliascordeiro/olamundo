package com.example.olamundo.platform.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import com.example.olamundo.auth.AccountDirectory;
import com.example.olamundo.auth.AuthService;
import com.example.olamundo.platform.domain.PlatformOwner;
import com.example.olamundo.shared.PageSlice;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlatformOwnerService {
	private static final Duration INVITE_TTL = Duration.ofDays(7);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final PlatformOwnerRepository owners;
	private final PlatformAccess access;
	private final AccountDirectory accounts;
	private final AuthService auth;

	public PlatformOwnerService(PlatformOwnerRepository owners, PlatformAccess access,
			AccountDirectory accounts, AuthService auth) {
		this.owners = owners;
		this.access = access;
		this.accounts = accounts;
		this.auth = auth;
	}

	@Transactional(readOnly = true)
	public PageSlice<PlatformOwner> list(String actorEmail, int page, int size) {
		access.requireSuper(actorEmail);
		PageSlice.validate(page, size);
		return PageSlice.from(owners.list(size + 1, (long) page * size), page, size);
	}

	@Transactional
	public OwnerInvite create(String actorEmail, UUID requestId, String name, String email) {
		access.requireSuper(actorEmail);
		if (requestId == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe requestId UUID.");
		}
		String normalizedName = normalizeName(name);
		String normalizedEmail = email == null || email.isBlank() ? null : normalizeEmail(email);
		String actorId = actorId(actorEmail);
		if (!owners.create(requestId, normalizedName, normalizedEmail, actorId, Instant.now())) {
			if (!owners.matchesRegistration(requestId, normalizedName, normalizedEmail, actorId)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador ja usado com outro cadastro.");
			}
			return new OwnerInvite(find(requestId), null, null);
		}
		return normalizedEmail == null ? new OwnerInvite(find(requestId), null, null) : issue(requestId, normalizedEmail);
	}

	@Transactional
	public OwnerInvite invite(String actorEmail, UUID id, String email) {
		access.requireSuper(actorEmail);
		find(id);
		return issue(id, normalizeEmail(email));
	}

	@Transactional
	public PlatformOwner accept(String token, String name, String password) {
		var now = Instant.now();
		String hash = hash(token);
		var invite = owners.lockInvite(hash, now).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite invalido ou expirado. Peca um novo convite."));
		String userId = accounts.findUserIdByEmail(invite.email()).orElse(null);
		if (userId == null) {
			auth.register(name, invite.email(), password);
			userId = accounts.findUserIdByEmail(invite.email()).orElseThrow(() ->
					new IllegalStateException("Account was not created for accepted invite"));
		}
		var owner = activate(invite.ownerId(), invite.email(), userId, now);
		owners.consumeInvite(hash, now);
		return owner;
	}

	private OwnerInvite issue(UUID id, String email) {
		var now = Instant.now();
		var existing = accounts.findUserIdByEmail(email);
		try {
			if (existing.isPresent()) {
				return new OwnerInvite(activate(id, email, existing.get(), now), null, null);
			}
			var owner = owners.markInvited(id, email, now).orElseThrow(PlatformOwnerService::alreadyActive);
			String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
			var expiresAt = now.plus(INVITE_TTL);
			owners.replaceInvite(id, hash(token), expiresAt, now);
			return new OwnerInvite(owner, token, expiresAt);
		} catch (DuplicateKeyException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Este e-mail ou conta ja esta associado a outro proprietario.");
		}
	}

	private PlatformOwner activate(UUID id, String email, String userId, Instant now) {
		try {
			return owners.activate(id, email, userId, now).orElseThrow(PlatformOwnerService::alreadyActive);
		} catch (DuplicateKeyException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Este e-mail ou conta ja esta associado a outro proprietario.");
		}
	}

	private PlatformOwner find(UUID id) {
		return owners.find(id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Proprietario nao encontrado."));
	}

	private String actorId(String actorEmail) {
		return accounts.findUserIdByEmail(actorEmail).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Conta indisponivel. Entre novamente."));
	}

	private static ResponseStatusException alreadyActive() {
		return new ResponseStatusException(HttpStatus.CONFLICT, "Este proprietario ja esta ativo.");
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

	private static String normalizeName(String name) {
		String normalized = name == null ? "" : name.strip();
		if (normalized.length() < 2 || normalized.length() > 120) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do proprietario deve ter de 2 a 120 caracteres.");
		}
		return normalized;
	}

	private static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	/** O token em claro so existe nesta resposta; nao e armazenado nem recuperavel depois. */
	public record OwnerInvite(PlatformOwner owner, String inviteToken, Instant expiresAt) {
	}
}
