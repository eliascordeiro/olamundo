package com.example.olamundo.stores.api;

import java.net.URI;
import java.security.Principal;
import java.util.UUID;

import com.example.olamundo.auth.AuthService;
import com.example.olamundo.shared.PageSlice;
import com.example.olamundo.stores.application.StoreRepository.StoreAccess;
import com.example.olamundo.stores.application.StoreService;
import com.example.olamundo.stores.domain.Store;
import com.example.olamundo.stores.domain.StoreMembership;
import com.example.olamundo.stores.domain.StoreRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
@Tag(name = "Lojas", description = "Lojas e vinculos. Acesso sempre validado pela sessao e pela loja.")
public class StoreController {
	private final StoreService stores;
	private final AuthService accounts;

	public StoreController(StoreService stores, AuthService accounts) {
		this.stores = stores;
		this.accounts = accounts;
	}

	@PostMapping
	@Operation(summary = "Criar loja; somente proprietario ativo da plataforma, que se torna OWNER")
	@SecurityRequirement(name = "csrf")
	public ResponseEntity<Store> create(Principal principal, @Valid @RequestBody NewStore request) {
		var store = stores.create(userId(principal), request.name());
		return ResponseEntity.created(URI.create("/api/stores/" + store.id())).body(store);
	}

	@GetMapping
	@Operation(summary = "Listar somente as lojas do usuario atual")
	public PageSlice<StoreAccess> list(Principal principal,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return stores.list(userId(principal), page, size);
	}

	@GetMapping("/{storeId}")
	@Operation(summary = "Consultar loja; nao membros recebem 404")
	public StoreAccess get(Principal principal, @PathVariable UUID storeId) {
		return stores.get(storeId, userId(principal));
	}

	@GetMapping("/{storeId}/members")
	@Operation(summary = "Listar membros; somente OWNER")
	public PageSlice<StoreMembership> members(Principal principal, @PathVariable UUID storeId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return stores.members(storeId, userId(principal), page, size);
	}

	@PostMapping("/{storeId}/members")
	@Operation(summary = "Vincular conta existente como MANAGER ou STAFF; somente OWNER")
	@SecurityRequirement(name = "csrf")
	public ResponseEntity<StoreMembership> addMember(Principal principal, @PathVariable UUID storeId,
			@Valid @RequestBody NewMember request) {
		return ResponseEntity.status(201).body(
				stores.addMember(storeId, userId(principal), request.email(), request.role()));
	}

	private String userId(Principal principal) {
		return accounts.currentUser(principal.getName()).id();
	}

	public record NewStore(@NotBlank @Size(min = 2, max = 100) String name) {
	}

	public record NewMember(@NotBlank @Email @Size(max = 254) String email, @NotNull StoreRole role) {
	}
}
