package com.example.olamundo.shared;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Normalizacao e validacao de campos de cadastro; erros de entrada viram 400 com mensagem em portugues. */
public final class Fields {
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final Pattern PHONE = Pattern.compile("^[0-9 ()+./-]{3,20}$");

	private Fields() {
	}

	public static String blank(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	public static String squash(String value) {
		return blank(value) == null ? null : value.strip().replaceAll("\\s+", " ");
	}

	public static String required(String value, int min, int max, String label) {
		String text = squash(value);
		if (text == null || text.length() < min || text.length() > max) {
			throw bad(label + " deve ter de " + min + " a " + max + " caracteres.");
		}
		return text;
	}

	public static String limited(String value, int max, String label) {
		String text = squash(value);
		if (text != null && text.length() > max) {
			throw bad(label + " deve ter no maximo " + max + " caracteres.");
		}
		return text;
	}

	public static String phone(String value) {
		String text = squash(value);
		if (text != null && !PHONE.matcher(text).matches()) {
			throw bad("Telefone invalido. Use ate 20 caracteres entre digitos, espacos e ( ) + - . /");
		}
		return text;
	}

	public static String email(String value) {
		String text = blank(value) == null ? null : value.strip().toLowerCase(Locale.ROOT);
		if (text != null && (text.length() > 254 || !EMAIL.matcher(text).matches())) {
			throw bad("E-mail invalido.");
		}
		return text;
	}

	public static String zip(String value) {
		String text = blank(value) == null ? null : value.replaceAll("\\D", "");
		if (text != null && text.length() != 8) {
			throw bad("CEP deve ter 8 digitos.");
		}
		return text;
	}

	public static String state(String value) {
		String text = blank(value) == null ? null : value.strip().toUpperCase(Locale.ROOT);
		if (text != null && !text.matches("[A-Z]{2}")) {
			throw bad("UF deve ter 2 letras.");
		}
		return text;
	}

	/** Percentual com ate duas casas decimais no intervalo [0, max]; ausente vale zero. */
	public static BigDecimal percent(BigDecimal value, int max, String label) {
		if (value == null) {
			return BigDecimal.ZERO.setScale(2);
		}
		if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0 || value.stripTrailingZeros().scale() > 2) {
			throw bad(label + " deve estar entre 0 e " + max + ", com ate duas casas decimais.");
		}
		return value.setScale(2);
	}

	public static ResponseStatusException bad(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}
}
