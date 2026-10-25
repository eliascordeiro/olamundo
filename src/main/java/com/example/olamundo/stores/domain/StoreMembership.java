package com.example.olamundo.stores.domain;

import java.time.Instant;
import java.util.UUID;

public record StoreMembership(UUID storeId, String userId, StoreRole role, Instant createdAt) {
}
