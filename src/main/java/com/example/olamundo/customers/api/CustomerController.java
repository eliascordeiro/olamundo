package com.example.olamundo.customers.api;

import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;

import com.example.olamundo.auth.AuthService;
import com.example.olamundo.customers.application.CustomerService;
import com.example.olamundo.customers.domain.Customer;
import com.example.olamundo.customers.domain.CustomerData;
import com.example.olamundo.shared.PageSlice;
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
@RequestMapping("/api/stores/{storeId}/customers")
@Tag(name = "Clientes", description = "Clientes da empresa, acessiveis por qualquer membro de uma loja dela.")
public class CustomerController {
	private final CustomerService customers;
	private final AuthService accounts;

	public CustomerController(CustomerService customers, AuthService accounts) {
		this.customers = customers;
		this.accounts = accounts;
	}

	@GetMapping
	@Operation(summary = "Listar/buscar clientes da empresa (nome, CPF/CNPJ ou telefone); qualquer membro da loja")
	public PageSlice<Customer> list(Principal principal, @PathVariable UUID storeId,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return customers.list(storeId, userId(principal), q, page, size);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Consultar cliente; qualquer membro da loja")
	public Customer get(Principal principal, @PathVariable UUID storeId, @PathVariable UUID id) {
		return customers.get(storeId, userId(principal), id);
	}

	@PostMapping
	@Operation(summary = "Cadastrar cliente, com reenvio seguro por requestId; retorna 200")
	@SecurityRequirement(name = "csrf")
	public Customer create(Principal principal, @PathVariable UUID storeId, @Valid @RequestBody NewCustomer request) {
		return customers.create(storeId, userId(principal), request.requestId(), request.data());
	}

	@PostMapping("/{id}")
	@Operation(summary = "Editar cliente por versao; substitui todos os campos editaveis")
	@SecurityRequirement(name = "csrf")
	public Customer update(Principal principal, @PathVariable UUID storeId, @PathVariable UUID id,
			@Valid @RequestBody EditCustomer request) {
		return customers.update(storeId, userId(principal), id, request.version(), request.data());
	}

	private String userId(Principal principal) {
		return accounts.currentUser(principal.getName()).id();
	}

	public record NewCustomer(@NotNull UUID requestId, @NotBlank @Size(max = 200) String name,
			@Size(max = 30) String document, @Size(max = 30) String rg, @Size(max = 30) String stateRegistration,
			LocalDate birthDate, @Size(max = 30) String phone, @Size(max = 30) String mobile, @Size(max = 300) String email,
			@Size(max = 20) String zip, @Size(max = 200) String street, @Size(max = 40) String number,
			@Size(max = 200) String complement, @Size(max = 100) String neighborhood, @Size(max = 100) String city,
			@Size(max = 10) String state, @Size(max = 20) String ibge) {
		CustomerData data() {
			return new CustomerData(name, document, rg, stateRegistration, birthDate, phone, mobile, email, zip, street,
					number, complement, neighborhood, city, state, ibge);
		}
	}

	public record EditCustomer(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 200) String name,
			@Size(max = 30) String document, @Size(max = 30) String rg, @Size(max = 30) String stateRegistration,
			LocalDate birthDate, @Size(max = 30) String phone, @Size(max = 30) String mobile, @Size(max = 300) String email,
			@Size(max = 20) String zip, @Size(max = 200) String street, @Size(max = 40) String number,
			@Size(max = 200) String complement, @Size(max = 100) String neighborhood, @Size(max = 100) String city,
			@Size(max = 10) String state, @Size(max = 20) String ibge) {
		CustomerData data() {
			return new CustomerData(name, document, rg, stateRegistration, birthDate, phone, mobile, email, zip, street,
					number, complement, neighborhood, city, state, ibge);
		}
	}
}
