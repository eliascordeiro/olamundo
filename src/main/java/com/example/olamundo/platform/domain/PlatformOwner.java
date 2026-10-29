package com.example.olamundo.platform.domain;

import java.time.Instant;
import java.util.UUID;

public record PlatformOwner(UUID id, String name, String email, Status status, String userId,
		String createdBy, Instant createdAt, Instant updatedAt) {

	public enum Status {
		PENDING, INVITED, ACTIVE
	}
}
