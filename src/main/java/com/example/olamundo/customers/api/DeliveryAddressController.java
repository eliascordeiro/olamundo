package com.example.olamundo.customers.api;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import com.example.olamundo.auth.AuthService;
import com.example.olamundo.customers.application.DeliveryAddressService;
import com.example.olamundo.customers.domain.DeliveryAddress;
import com.example.olamundo.customers.domain.DeliveryAddressData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores/{storeId}/customers/{customerId}/delivery-addresses")
@Tag(name = "Enderecos de entrega", description = "Enderecos de entrega do cliente, acessiveis por qualquer membro de uma loja da empresa.")
public class DeliveryAddressController {
	private final DeliveryAddressService addresses;
	private final AuthService accounts;

	public DeliveryAddressController(DeliveryAddressService addresses, AuthService accounts) {
		this.addresses = addresses;
		this.accounts = accounts;
	}

	@GetMapping
	@Operation(summary = "Listar enderecos de entrega do cliente; qualquer membro da loja")
	public List<DeliveryAddress> list(Principal principal, @PathVariable UUID storeId, @PathVariable UUID customerId) {
		return addresses.list(storeId, userId(principal), customerId);
	}

	@PostMapping
	@Operation(summary = "Cadastrar endereco de entrega, com reenvio seguro por requestId; retorna 200")
	@SecurityRequirement(name = "csrf")
	public DeliveryAddress create(Principal principal, @PathVariable UUID storeId, @PathVariable UUID customerId,
			@Valid @RequestBody NewAddress request) {
		return addresses.create(storeId, userId(principal), customerId, request.requestId(), request.data());
	}

	@PostMapping("/{id}")
	@Operation(summary = "Editar endereco de entrega por versao; substitui todos os campos editaveis")
	@SecurityRequirement(name = "csrf")
	public DeliveryAddress update(Principal principal, @PathVariable UUID storeId, @PathVariable UUID customerId,
			@PathVariable UUID id, @Valid @RequestBody EditAddress request) {
		return addresses.update(storeId, userId(principal), customerId, id, request.version(), request.data());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Remover endereco de entrega por versao")
	@SecurityRequirement(name = "csrf")
	public void delete(Principal principal, @PathVariable UUID storeId, @PathVariable UUID customerId,
			@PathVariable UUID id, @RequestParam @PositiveOrZero long version) {
		addresses.delete(storeId, userId(principal), customerId, id, version);
	}

	private String userId(Principal principal) {
		return accounts.currentUser(principal.getName()).id();
	}

	public record NewAddress(@NotNull UUID requestId, @Size(max = 20) String zip, @NotBlank @Size(max = 200) String street,
			@Size(max = 40) String number, @Size(max = 200) String complement, @Size(max = 100) String neighborhood,
			@Size(max = 100) String city, @Size(max = 10) String state) {
		DeliveryAddressData data() {
			return new DeliveryAddressData(zip, street, number, complement, neighborhood, city, state);
		}
	}

	public record EditAddress(@NotNull @PositiveOrZero Long version, @Size(max = 20) String zip,
			@NotBlank @Size(max = 200) String street, @Size(max = 40) String number, @Size(max = 200) String complement,
			@Size(max = 100) String neighborhood, @Size(max = 100) String city, @Size(max = 10) String state) {
		DeliveryAddressData data() {
			return new DeliveryAddressData(zip, street, number, complement, neighborhood, city, state);
		}
	}
}
