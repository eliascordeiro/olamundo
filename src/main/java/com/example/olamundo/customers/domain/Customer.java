package com.example.olamundo.customers.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Customer(UUID id, UUID ownerId, String name, String document, String rg, String stateRegistration,
		LocalDate birthDate, String phone, String mobile, String email, String zip, String street, String number,
		String complement, String neighborhood, String city, String state, String ibge,
		long version, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
}
