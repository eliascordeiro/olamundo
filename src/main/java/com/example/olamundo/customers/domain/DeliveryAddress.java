package com.example.olamundo.customers.domain;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAddress(UUID id, UUID customerId, String zip, String street, String number, String complement,
		String neighborhood, String city, String state, long version, String createdBy, Instant createdAt,
		String updatedBy, Instant updatedAt) {
}
