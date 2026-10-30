package com.example.olamundo.salespeople.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.stores.domain.StoreRole;

public interface SellerInviteRepository {
	/** Substitui convites ainda nao usados do mesmo proprietario para o mesmo e-mail. */
	void replace(String tokenHash, String email, String sellerName, String createdBy, Instant expiresAt, Instant now,
			List<Grant> grants);

	Optional<Invite> lock(String tokenHash, Instant now);

	List<Grant> grants(String tokenHash);

	void consume(String tokenHash, Instant now);

	record Grant(UUID storeId, StoreRole role, UUID salespersonId) {
	}

	record Invite(String email, String sellerName, String createdBy) {
	}
}
