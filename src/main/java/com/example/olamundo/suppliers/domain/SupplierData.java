package com.example.olamundo.suppliers.domain;

/** Campos editaveis de um fornecedor, ja normalizados. */
public record SupplierData(String name, String document, String stateRegistration, String email, String phone,
		String fax, String contact, String zip, String street, String neighborhood, String city, String state,
		boolean active) {
}
