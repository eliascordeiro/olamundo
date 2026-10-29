package com.example.olamundo.platform.api;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;

import com.example.olamundo.auth.AuthRateLimiter;
import com.example.olamundo.platform.application.PlatformOwnerService;
import com.example.olamundo.platform.domain.PlatformOwner;
import com.example.olamundo.shared.PageSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform")
@Tag(name = "Plataforma", description = "Administracao de proprietarios; somente o Super da plataforma. Nao acessa dados de lojas.")
public class PlatformOwnerController {
	private final PlatformOwnerService owners;
	private final AuthRateLimiter rateLimiter;

	public PlatformOwnerController(PlatformOwnerService owners, AuthRateLimiter rateLimiter) {
		this.owners = owners;
		this.rateLimiter = rateLimiter;
	}

	@GetMapping("/owners")
	@Operation(summary = "Listar proprietarios da plataforma; somente Super")
	public PageSlice<PlatformOwner> list(Principal principal,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return owners.list(principal.getName(), page, size);
	}

	@PostMapping("/owners")
	@Operation(summary = "Cadastrar proprietario, com reenvio seguro por requestId; e-mail opcional emite convite")
	@SecurityRequirement(name = "csrf")
	public PlatformOwnerService.OwnerInvite create(Principal principal, @Valid @RequestBody NewOwner request) {
		return owners.create(principal.getName(), request.requestId(), request.name(), request.email());
	}

	@PostMapping("/owners/{id}/invite")
	@Operation(summary = "Definir e-mail e emitir novo convite de uso unico; invalida convites anteriores")
	@SecurityRequirement(name = "csrf")
	public PlatformOwnerService.OwnerInvite invite(Principal principal, @PathVariable UUID id,
			@Valid @RequestBody InviteRequest request) {
		return owners.invite(principal.getName(), id, request.email());
	}

	@PostMapping("/invites/accept")
	@Operation(summary = "Aceitar convite e definir a senha; publico, protegido por token de uso unico e CSRF")
	@SecurityRequirement(name = "csrf")
	public AcceptedInvite accept(@Valid @RequestBody AcceptInvite request, HttpServletRequest http) {
		rateLimiter.check(http.getRemoteAddr());
		var owner = owners.accept(request.token(), request.name(), request.password());
		return new AcceptedInvite(owner.name(), owner.email());
	}

	public record AcceptedInvite(String name, String email) {
	}

	public record NewOwner(@NotNull UUID requestId, @NotBlank @Size(min = 2, max = 120) String name,
			@Email @Size(max = 254) String email) {
	}

	public record InviteRequest(@NotBlank @Email @Size(max = 254) String email) {
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
