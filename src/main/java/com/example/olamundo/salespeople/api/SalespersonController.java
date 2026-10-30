package com.example.olamundo.salespeople.api;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.olamundo.auth.AccountDirectory;
import com.example.olamundo.auth.AuthRateLimiter;
import com.example.olamundo.auth.AuthService;
import com.example.olamundo.salespeople.application.SalespersonService;
import com.example.olamundo.salespeople.application.SellerAccessService;
import com.example.olamundo.salespeople.domain.Salesperson;
import com.example.olamundo.shared.PageSlice;
import com.example.olamundo.stores.domain.StoreRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
@RequestMapping("/api")
@Tag(name = "Equipe e vendedores", description = "Cadastro comercial por loja e acesso do vendedor as lojas; somente OWNER.")
public class SalespersonController {
	private final SalespersonService salespeople;
	private final SellerAccessService access;
	private final AuthService accounts;
	private final AccountDirectory directory;
	private final AuthRateLimiter rateLimiter;

	public SalespersonController(SalespersonService salespeople, SellerAccessService access, AuthService accounts,
			AccountDirectory directory, AuthRateLimiter rateLimiter) {
		this.salespeople = salespeople;
		this.access = access;
		this.accounts = accounts;
		this.directory = directory;
		this.rateLimiter = rateLimiter;
	}

	@GetMapping("/stores/{storeId}/salespeople")
	@Operation(summary = "Listar vendedores ativos e inativos da loja; somente OWNER")
	public PageSlice<SalespersonView> list(Principal principal, @PathVariable UUID storeId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		var slice = salespeople.list(storeId, userId(principal), page, size);
		return new PageSlice<>(slice.content().stream().map(this::view).toList(), slice.page(), slice.size(), slice.hasNext());
	}

	@GetMapping("/stores/{storeId}/salespeople/{id}")
	@Operation(summary = "Consultar vendedor comercial; somente OWNER")
	public SalespersonView get(Principal principal, @PathVariable UUID storeId, @PathVariable UUID id) {
		return view(salespeople.get(storeId, userId(principal), id));
	}

	@PostMapping("/stores/{storeId}/salespeople")
	@Operation(summary = "Cadastrar vendedor sem conta, com reenvio seguro por requestId; retorna 200")
	@SecurityRequirement(name = "csrf")
	public SalespersonView create(Principal principal, @PathVariable UUID storeId, @Valid @RequestBody NewSalesperson request) {
		return view(salespeople.create(storeId, userId(principal), request.requestId(), request.name()));
	}

	@PostMapping("/stores/{storeId}/salespeople/{id}")
	@Operation(summary = "Editar nome e situacao comercial por versao; nao altera login ou permissoes")
	@SecurityRequirement(name = "csrf")
	public SalespersonView update(Principal principal, @PathVariable UUID storeId, @PathVariable UUID id,
			@Valid @RequestBody EditSalesperson request) {
		return view(salespeople.update(storeId, userId(principal), id, request.name(), request.active(), request.version()));
	}

	@PostMapping("/stores/{storeId}/salespeople/{id}/account")
	@Operation(summary = "Vincular conta ja membro por email; email vazio remove apenas o vinculo comercial")
	@SecurityRequirement(name = "csrf")
	public SalespersonView link(Principal principal, @PathVariable UUID storeId, @PathVariable UUID id,
			@Valid @RequestBody LinkAccount request) {
		return view(salespeople.link(storeId, userId(principal), id, request.email(), request.version()));
	}

	@PostMapping("/stores/{storeId}/salespeople/{id}/access")
	@Operation(summary = "Dar acesso as lojas escolhidas (todas do proprietario): vincula conta existente ou emite convite de uso unico")
	@SecurityRequirement(name = "csrf")
	public AccessResponse grant(Principal principal, @PathVariable UUID storeId, @PathVariable UUID id,
			@Valid @RequestBody GrantAccess request) {
		var result = access.grant(storeId, userId(principal), id, request.email(), request.version(),
				request.stores().stream().map(item -> new SellerAccessService.StoreGrant(item.storeId(), item.role())).toList());
		return new AccessResponse(view(result.seller()), result.inviteToken(), result.expiresAt());
	}

	@PostMapping("/seller-invites/accept")
	@Operation(summary = "Aceitar convite de vendedor e definir a senha; publico, protegido por token de uso unico e CSRF")
	@SecurityRequirement(name = "csrf")
	public SellerAccessService.Accepted accept(@Valid @RequestBody AcceptInvite request, HttpServletRequest http) {
		rateLimiter.check(http.getRemoteAddr());
		return access.accept(request.token(), request.name(), request.password());
	}

	private SalespersonView view(Salesperson seller) {
		return SalespersonView.from(seller, directory.findEmailByUserId(seller.userId()).orElse(null));
	}

	private String userId(Principal principal) {
		return accounts.currentUser(principal.getName()).id();
	}

	public record SalespersonView(UUID id, UUID storeId, String name, boolean active, String userId, String accountEmail,
			long version, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
		static SalespersonView from(Salesperson seller, String accountEmail) {
			return new SalespersonView(seller.id(), seller.storeId(), seller.name(), seller.active(), seller.userId(),
					accountEmail, seller.version(), seller.createdBy(), seller.createdAt(), seller.updatedBy(), seller.updatedAt());
		}
	}

	public record AccessResponse(SalespersonView seller, String inviteToken, Instant expiresAt) {
	}

	public record NewSalesperson(@NotNull UUID requestId, @NotBlank @Size(min = 2, max = 120) String name) {
	}

	public record EditSalesperson(@NotBlank @Size(min = 2, max = 120) String name,
			@NotNull Boolean active, @NotNull @PositiveOrZero Long version) {
	}

	public record LinkAccount(@Email @Size(max = 254) String email, @NotNull @PositiveOrZero Long version) {
	}

	public record StoreAccessItem(@NotNull UUID storeId, @NotNull StoreRole role) {
	}

	public record GrantAccess(@NotBlank @Email @Size(max = 254) String email, @NotNull @PositiveOrZero Long version,
			@NotEmpty @Size(max = 20) List<@Valid StoreAccessItem> stores) {
	}

	public record AcceptInvite(
			@NotBlank @Size(max = 100) String token,
			@NotBlank @Size(min = 2, max = 80) String name,
			@NotBlank @Size(min = 12, max = 72) String password) {

		@AssertTrue(message = "A senha deve ter no maximo 72 bytes.")
		public boolean isPasswordWithinByteLimit() {
			return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
		}
	}
}
