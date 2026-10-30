package com.example.olamundo.platform.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.platform.domain.PlatformOwner;

public interface PlatformOwnerRepository {
	boolean create(UUID id, String name, String email, String actorId, Instant now);

	boolean matchesRegistration(UUID id, String name, String email, String actorId);

	Optional<PlatformOwner> find(UUID id);

	boolean existsActiveByUserId(String userId);

	List<PlatformOwner> list(int limit, long offset);

	/** Define o e-mail e deixa o proprietario aguardando convite; vazio se ja estiver ativo. */
	Optional<PlatformOwner> markInvited(UUID id, String email, Instant now);

	/** Vincula uma conta existente e ativa o proprietario; vazio se ja estiver ativo. */
	Optional<PlatformOwner> activate(UUID id, String email, String userId, Instant now);

	void replaceInvite(UUID ownerId, String tokenHash, Instant expiresAt, Instant now);

	Optional<PendingInvite> lockInvite(String tokenHash, Instant now);

	void consumeInvite(String tokenHash, Instant now);

	record PendingInvite(UUID ownerId, String email) {
	}
}
