package com.example.olamundo.customers.domain;

public record DeliveryAddressData(String zip, String street, String number, String complement, String neighborhood,
		String city, String state) {
}
