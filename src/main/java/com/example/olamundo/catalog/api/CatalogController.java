package com.example.olamundo.catalog.api;

import java.math.BigDecimal;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;

import com.example.olamundo.auth.AuthService;
import com.example.olamundo.catalog.application.CatalogService;
import com.example.olamundo.catalog.domain.Product;
import com.example.olamundo.shared.PageSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/api/stores/{storeId}/products")
@Tag(name = "Catalogo", description = "Produtos por loja. Precos em BRL; estoque em rotas separadas; sem checkout.")
public class CatalogController {
	private final CatalogService catalog;
	private final AuthService accounts;

	public CatalogController(CatalogService catalog, AuthService accounts) {
		this.catalog = catalog;
		this.accounts = accounts;
	}

	@PostMapping
	@Operation(summary = "Cadastrar produto; somente OWNER e MANAGER; SKU unico por loja")
	@SecurityRequirement(name = "csrf")
	public ResponseEntity<Product> create(Principal principal, @PathVariable UUID storeId,
			@Valid @RequestBody NewProduct request) {
		var product = catalog.create(storeId, accounts.currentUser(principal.getName()).id(),
				request.name(), request.sku(), request.description(), request.price());
		return ResponseEntity.created(URI.create("/api/stores/" + storeId + "/products/" + product.id()))
				.body(product);
	}

	@GetMapping
	@Operation(summary = "Listar produtos da loja; todos os membros; paginacao de 1 a 100 itens")
	public PageSlice<Product> list(Principal principal, @PathVariable UUID storeId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return catalog.list(storeId, accounts.currentUser(principal.getName()).id(), page, size);
	}

	@GetMapping("/{productId}")
	@Operation(summary = "Consultar produto apenas dentro da loja indicada")
	public Product get(Principal principal, @PathVariable UUID storeId, @PathVariable UUID productId) {
		return catalog.get(storeId, productId, accounts.currentUser(principal.getName()).id());
	}

	public record NewProduct(
			@NotBlank @Size(min = 2, max = 120) String name,
			@NotBlank @Size(max = 64) @Pattern(regexp = "\\s*[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\s*") String sku,
			@Size(max = 2000) String description,
			@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price) {
	}
}
