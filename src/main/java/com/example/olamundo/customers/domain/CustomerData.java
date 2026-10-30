package com.example.olamundo.customers.domain;

import java.time.LocalDate;

/** Campos editaveis de um cliente, ja normalizados (ver CustomerService). */
public record CustomerData(String name, String document, String rg, String stateRegistration,
		LocalDate birthDate, String phone, String mobile, String email, String zip, String street, String number,
		String complement, String neighborhood, String city, String state, String ibge) {
}
