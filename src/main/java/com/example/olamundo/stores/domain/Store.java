package com.example.olamundo.stores.domain;

import java.time.Instant;
import java.util.UUID;

public record Store(UUID id, String name, Instant createdAt) {
}
