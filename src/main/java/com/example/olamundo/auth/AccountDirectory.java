package com.example.olamundo.auth;

import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class AccountDirectory {
	private final UserAccountRepository users;

	public AccountDirectory(UserAccountRepository users) {
		this.users = users;
	}

	public Optional<String> findUserIdByEmail(String email) {
		return users.findByEmail(email.strip().toLowerCase(Locale.ROOT)).map(UserAccount::id);
	}
}
