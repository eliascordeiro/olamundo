package com.example.olamundo.suppliers.api;

import java.security.Principal;
import java.util.UUID;

import com.example.olamundo.auth.AuthService;
import com.example.olamundo.shared.PageSlice;
import com.example.olamundo.suppliers.application.SupplierService;
import com.example.olamundo.suppliers.domain.Supplier;
import com.example.olamundo.suppliers.domain.SupplierData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@RequestMapping("/api/stores/{storeId}/suppliers")
@Tag(name = "Fornecedores", description = "Fornecedores da empresa; qualquer membro consulta, OWNER e MANAGER cadastram e editam.")
public class SupplierController {
	private final SupplierService suppliers;
	private final AuthService accounts;

	public SupplierController(SupplierService suppliers, AuthService accounts) {
		this.suppliers = suppliers;
		this.accounts = accounts;
	}

	@GetMapping
	@Operation(summary = "Listar/buscar fornecedores da empresa (nome, CNPJ/CPF ou telefone); qualquer membro da loja")
	public PageSlice<Supplier> list(Principal principal, @PathVariable UUID storeId,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return suppliers.list(storeId, userId(principal), q, page, size);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Consultar fornecedor; qualquer membro da loja")
	public Supplier get(Principal principal, @PathVariable UUID storeId, @PathVariable UUID id) {
		return suppliers.get(storeId, userId(principal), id);
	}

	@PostMapping
	@Operation(summary = "Cadastrar fornecedor, com reenvio seguro por requestId; OWNER ou MANAGER")
	@SecurityRequirement(name = "csrf")
	public Supplier create(Principal principal, @PathVariable UUID storeId, @Valid @RequestBody NewSupplier request) {
		return suppliers.create(storeId, userId(principal), request.requestId(), request.data());
	}

	@PostMapping("/{id}")
	@Operation(summary = "Editar fornecedor por versao (inclui ativar/inativar); OWNER ou MANAGER")
	@SecurityRequirement(name = "csrf")
	public Supplier update(Principal principal, @PathVariable UUID storeId, @PathVariable UUID id,
			@Valid @RequestBody EditSupplier request) {
		return suppliers.update(storeId, userId(principal), id, request.version(), request.data());
	}

	private String userId(Principal principal) {
		return accounts.currentUser(principal.getName()).id();
	}

	public record NewSupplier(@NotNull UUID requestId, @NotBlank @Size(max = 200) String name,
			@Size(max = 30) String document, @Size(max = 30) String stateRegistration, @Size(max = 300) String email,
			@Size(max = 30) String phone, @Size(max = 30) String fax, @Size(max = 100) String contact,
			@Size(max = 20) String zip, @Size(max = 200) String street, @Size(max = 100) String neighborhood,
			@Size(max = 100) String city, @Size(max = 10) String state) {
		SupplierData data() {
			return new SupplierData(name, document, stateRegistration, email, phone, fax, contact, zip, street,
					neighborhood, city, state, true);
		}
	}

	public record EditSupplier(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 200) String name,
			@Size(max = 30) String document, @Size(max = 30) String stateRegistration, @Size(max = 300) String email,
			@Size(max = 30) String phone, @Size(max = 30) String fax, @Size(max = 100) String contact,
			@Size(max = 20) String zip, @Size(max = 200) String street, @Size(max = 100) String neighborhood,
			@Size(max = 100) String city, @Size(max = 10) String state, @NotNull Boolean active) {
		SupplierData data() {
			return new SupplierData(name, document, stateRegistration, email, phone, fax, contact, zip, street,
					neighborhood, city, state, active);
		}
	}
}
