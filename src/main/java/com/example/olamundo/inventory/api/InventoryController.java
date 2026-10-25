package com.example.olamundo.inventory.api;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.UUID;

import com.example.olamundo.auth.AuthService;
import com.example.olamundo.inventory.application.InventoryService;
import com.example.olamundo.inventory.domain.StockBalance;
import com.example.olamundo.inventory.domain.StockMovement;
import com.example.olamundo.inventory.domain.StockMovement.MovementType;
import com.example.olamundo.shared.PageSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
@RequestMapping("/api/stores/{storeId}/products/{productId}/stock")
@Tag(name = "Estoque", description = "Unidades inteiras por loja; sem reservas ou baixa automatica por venda.")
public class InventoryController {
	private final InventoryService inventory;
	private final AuthService accounts;

	public InventoryController(InventoryService inventory, AuthService accounts) {
		this.inventory = inventory;
		this.accounts = accounts;
	}

	@GetMapping
	@Operation(summary = "Consultar saldo; todos os membros; produtos sem entradas tem saldo zero")
	public StockBalance balance(Principal principal, @PathVariable UUID storeId, @PathVariable UUID productId) {
		return inventory.balance(storeId, productId, accounts.currentUser(principal.getName()).id());
	}

	@GetMapping("/movements")
	@Operation(summary = "Historico mais recente primeiro; todos os membros; size de 1 a 100")
	public PageSlice<StockMovement> history(Principal principal, @PathVariable UUID storeId, @PathVariable UUID productId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return inventory.history(storeId, productId, accounts.currentUser(principal.getName()).id(), page, size);
	}

	@PostMapping("/movements")
	@SecurityRequirement(name = "csrf")
	@Operation(summary = "Entrada/saida; OWNER e MANAGER; repetir requestId e dados retorna a mesma movimentacao (200)")
	public StockMovement move(Principal principal, @PathVariable UUID storeId, @PathVariable UUID productId,
			@Valid @RequestBody NewMovement request) {
		return inventory.move(storeId, productId, accounts.currentUser(principal.getName()).id(), request.requestId(),
				request.type(), request.quantity(), request.reason());
	}

	public record NewMovement(
			@NotNull UUID requestId,
			@NotNull MovementType type,
			@NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0) BigDecimal quantity,
			@NotBlank @Size(min = 2, max = 500) String reason) {
	}
}
