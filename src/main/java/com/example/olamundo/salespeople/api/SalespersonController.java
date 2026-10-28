package com.example.olamundo.salespeople.api;

import java.security.Principal;
import java.util.UUID;

import com.example.olamundo.auth.AuthService;
import com.example.olamundo.salespeople.application.SalespersonService;
import com.example.olamundo.salespeople.domain.Salesperson;
import com.example.olamundo.shared.PageSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores/{storeId}/salespeople")
@Tag(name = "Equipe e vendedores", description = "Cadastro comercial por loja; somente OWNER. Nao concede acesso.")
public class SalespersonController {
	private final SalespersonService salespeople;
	private final AuthService accounts;

	public SalespersonController(SalespersonService salespeople, AuthService accounts) {
		this.salespeople = salespeople;
		this.accounts = accounts;
	}

	@GetMapping
	@Operation(summary = "Listar vendedores ativos e inativos da loja; somente OWNER")
	public PageSlice<Salesperson> list(Principal principal, @PathVariable UUID storeId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return salespeople.list(storeId, userId(principal), page, size);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Consultar vendedor comercial; somente OWNER")
	public Salesperson get(Principal principal, @PathVariable UUID storeId, @PathVariable UUID id) {
		return salespeople.get(storeId, userId(principal), id);
	}

	@PostMapping
	@Operation(summary = "Cadastrar vendedor sem conta, com reenvio seguro por requestId; retorna 200")
	@SecurityRequirement(name = "csrf")
	public Salesperson create(Principal principal, @PathVariable UUID storeId, @Valid @RequestBody NewSalesperson request) {
		return salespeople.create(storeId, userId(principal), request.requestId(), request.name());
	}

	@PostMapping("/{id}")
	@Operation(summary = "Editar nome e situacao comercial por versao; nao altera login ou permissoes")
	@SecurityRequirement(name = "csrf")
	public Salesperson update(Principal principal, @PathVariable UUID storeId, @PathVariable UUID id,
			@Valid @RequestBody EditSalesperson request) {
		return salespeople.update(storeId, userId(principal), id, request.name(), request.active(), request.version());
	}

	@PostMapping("/{id}/account")
	@Operation(summary = "Vincular conta ja membro por email; email vazio remove apenas o vinculo comercial")
	@SecurityRequirement(name = "csrf")
	public Salesperson link(Principal principal, @PathVariable UUID storeId, @PathVariable UUID id,
			@Valid @RequestBody LinkAccount request) {
		return salespeople.link(storeId, userId(principal), id, request.email(), request.version());
	}

	private String userId(Principal principal) {
		return accounts.currentUser(principal.getName()).id();
	}

	public record NewSalesperson(@NotNull UUID requestId, @NotBlank @Size(min = 2, max = 120) String name) {
	}

	public record EditSalesperson(@NotBlank @Size(min = 2, max = 120) String name,
			@NotNull Boolean active, @NotNull @PositiveOrZero Long version) {
	}

	public record LinkAccount(@Email @Size(max = 254) String email, @NotNull @PositiveOrZero Long version) {
	}
}
