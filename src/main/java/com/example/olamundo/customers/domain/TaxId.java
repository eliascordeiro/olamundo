package com.example.olamundo.customers.domain;

/** Validacao de CPF e CNPJ pelos digitos verificadores. Recebe somente digitos. */
public final class TaxId {
	private TaxId() {
	}

	public static boolean isValid(String digits) {
		if (digits == null || !digits.matches("\\d+") || digits.chars().distinct().count() == 1) {
			return false;
		}
		return switch (digits.length()) {
			case 11 -> cpf(digits);
			case 14 -> cnpj(digits);
			default -> false;
		};
	}

	private static boolean cpf(String d) {
		return d.charAt(9) - '0' == check(d, 9, 10) && d.charAt(10) - '0' == check(d, 10, 11);
	}

	private static int check(String d, int length, int weight) {
		int sum = 0;
		for (int i = 0; i < length; i++) {
			sum += (d.charAt(i) - '0') * (weight - i);
		}
		return (sum * 10 % 11) % 10;
	}

	private static boolean cnpj(String d) {
		int[] first = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
		int[] second = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
		return d.charAt(12) - '0' == cnpjDigit(d, first) && d.charAt(13) - '0' == cnpjDigit(d, second);
	}

	private static int cnpjDigit(String d, int[] weights) {
		int sum = 0;
		for (int i = 0; i < weights.length; i++) {
			sum += (d.charAt(i) - '0') * weights[i];
		}
		int rest = sum % 11;
		return rest < 2 ? 0 : 11 - rest;
	}
}
