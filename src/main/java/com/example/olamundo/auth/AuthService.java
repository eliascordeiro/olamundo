package com.example.olamundo.auth;

import java.time.Instant;
import java.util.Locale;

import com.example.olamundo.platform.application.OwnerAccess;
import com.example.olamundo.platform.application.PlatformAccess;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
	private final UserAccountRepository repository;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationManager authenticationManager;
	private final PlatformAccess platformAccess;
	private final OwnerAccess ownerAccess;

	public AuthService(UserAccountRepository repository, PasswordEncoder passwordEncoder,
			AuthenticationManager authenticationManager, PlatformAccess platformAccess, OwnerAccess ownerAccess) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
		this.authenticationManager = authenticationManager;
		this.platformAccess = platformAccess;
		this.ownerAccess = ownerAccess;
	}

	public void register(String name, String email, String password) {
		try {
			repository.insert(new UserAccount(null, name.strip(), normalizeEmail(email),
					passwordEncoder.encode(password), Instant.now()));
		} catch (DuplicateKeyException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Nao foi possivel cadastrar com estes dados. Tente entrar na sua conta.");
		}
	}

	public Authentication authenticate(String email, String password) {
		return authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(normalizeEmail(email), password));
	}

	public UserView currentUser(String email) {
		return repository.findByEmail(email)
				.map(account -> UserView.from(account, platformAccess.isSuper(account.email()),
						ownerAccess.isActiveOwner(account.id())))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
						"Conta indisponivel. Entre novamente."));
	}

	private static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	public record UserView(String id, String name, String email, boolean platformAdmin, boolean platformOwner) {
		static UserView from(UserAccount account, boolean platformAdmin, boolean platformOwner) {
			return new UserView(account.id(), account.name(), account.email(), platformAdmin, platformOwner);
		}
	}
}
