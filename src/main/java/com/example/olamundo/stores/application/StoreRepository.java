package com.example.olamundo.stores.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.stores.domain.Store;
import com.example.olamundo.stores.domain.StoreMembership;
import com.example.olamundo.stores.domain.StoreRole;

public interface StoreRepository {
	void create(Store store);
	void assignOwner(UUID storeId, UUID ownerId);
	Optional<UUID> findOwnerId(UUID storeId);
	boolean addMember(StoreMembership membership);
	Optional<StoreRole> findRole(UUID storeId, String userId);
	Optional<Store> findStore(UUID storeId);
	List<StoreAccess> findStores(String userId, int limit, long offset);
	List<StoreMembership> findMembers(UUID storeId, int limit, long offset);

	record StoreAccess(UUID id, String name, java.time.Instant createdAt, StoreRole role) {
	}
}
