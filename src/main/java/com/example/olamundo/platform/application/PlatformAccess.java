package com.example.olamundo.platform.application;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** O Super e uma conta comum cujo e-mail consta na configuracao; nunca herda acesso as lojas. */
@Component
public class PlatformAccess {
	private final Set<String> superEmails;

	public PlatformAccess(@Value("${app.platform.super-emails:}") String emails) {
		this.superEmails = Arrays.stream(emails.split(","))
				.map(email -> email.strip().toLowerCase(Locale.ROOT))
				.filter(email -> !email.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
	}

	public boolean isSuper(String email) {
		return email != null && superEmails.contains(email.strip().toLowerCase(Locale.ROOT));
	}

	public void requireSuper(String email) {
		if (!isSuper(email)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso restrito ao administrador da plataforma.");
		}
	}
}
