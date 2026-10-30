package com.example.olamundo.platform.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Consulta leve do status de proprietario, sem depender de autenticacao (evita ciclos de beans). */
@Component
public class OwnerAccess {
	private final PlatformOwnerRepository owners;

	public OwnerAccess(PlatformOwnerRepository owners) {
		this.owners = owners;
	}

	public boolean isActiveOwner(String userId) {
		return userId != null && owners.existsActiveByUserId(userId);
	}

	/** Id da empresa (proprietario ativo) do usuario; 403 se nao for proprietario ativo. */
	public UUID requireActiveOwner(String userId) {
		return (userId == null ? java.util.Optional.<UUID>empty() : owners.findActiveIdByUserId(userId))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
						"Somente proprietarios ativos podem criar lojas. Fale com o administrador da plataforma."));
	}
}
