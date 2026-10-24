package com.example.olamundo.auth;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("users")
public record UserAccount(
		@Id String id,
		String name,
		String email,
		String passwordHash,
		Instant createdAt) {
}
