package com.example.olamundo.productgroups.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.productgroups.domain.GroupData;
import com.example.olamundo.productgroups.domain.ProductGroup;
import com.example.olamundo.productgroups.domain.ProductSubgroup;
import com.example.olamundo.productgroups.domain.SubgroupData;

public interface ProductGroupRepository {
	boolean createGroup(UUID ownerId, UUID id, GroupData data, String actorId, Instant now);

	boolean matchesGroupRegistration(UUID ownerId, UUID id, String name, String actorId);

	Optional<ProductGroup> findGroup(UUID ownerId, UUID id);

	List<ProductGroup> listGroups(UUID ownerId);

	Optional<ProductGroup> updateGroup(UUID ownerId, UUID id, GroupData data, long version, String actorId, Instant now);

	boolean createSubgroup(UUID ownerId, UUID groupId, UUID id, SubgroupData data, String actorId, Instant now);

	boolean matchesSubgroupRegistration(UUID ownerId, UUID groupId, UUID id, String name, String actorId);

	Optional<ProductSubgroup> findSubgroup(UUID ownerId, UUID groupId, UUID id);

	List<ProductSubgroup> listSubgroups(UUID ownerId);

	Optional<ProductSubgroup> updateSubgroup(UUID ownerId, UUID groupId, UUID id, SubgroupData data, long version,
			String actorId, Instant now);
}
