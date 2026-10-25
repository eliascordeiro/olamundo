package com.example.olamundo.orders.api;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

import com.example.olamundo.auth.AuthService;
import com.example.olamundo.orders.application.OrderService;
import com.example.olamundo.orders.domain.Order;
import com.example.olamundo.shared.PageSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
@RequestMapping("/api/stores/{storeId}/orders")
@Tag(name = "Pedidos", description = "Pedidos internos em BRL, sem pagamento, frete, cliente ou cancelamento.")
public class OrderController {
	private final OrderService orders;
	private final AuthService accounts;

	public OrderController(OrderService orders, AuthService accounts) {
		this.orders = orders;
		this.accounts = accounts;
	}

	@PostMapping
	@SecurityRequirement(name = "csrf")
	@Operation(summary = "Criar rascunho com precos do catalogo; OWNER/MANAGER; requestId idempotente; retorna 200")
	public Order create(Principal principal, @PathVariable UUID storeId, @Valid @RequestBody NewOrder request) {
		return orders.create(storeId, accounts.currentUser(principal.getName()).id(), request.requestId(),
				request.items().stream().map(item -> new OrderService.RequestedItem(item.productId(), item.quantity())).toList());
	}

	@GetMapping
	@Operation(summary = "Listar pedidos da loja; todos os membros; size de 1 a 100")
	public PageSlice<Order.Summary> list(Principal principal, @PathVariable UUID storeId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return orders.list(storeId, accounts.currentUser(principal.getName()).id(), page, size);
	}

	@GetMapping("/{orderId}")
	public Order get(Principal principal, @PathVariable UUID storeId, @PathVariable UUID orderId) {
		return orders.get(storeId, orderId, accounts.currentUser(principal.getName()).id());
	}

	@PostMapping("/{orderId}/confirm")
	@SecurityRequirement(name = "csrf")
	@Operation(summary = "Confirmar e baixar estoque atomicamente; OWNER/MANAGER; repetir nao baixa novamente; retorna 200")
	public Order confirm(Principal principal, @PathVariable UUID storeId, @PathVariable UUID orderId) {
		return orders.confirm(storeId, orderId, accounts.currentUser(principal.getName()).id());
	}

	public record NewOrder(@NotNull UUID requestId,
			@NotNull @Size(min = 1, max = 50) List<@NotNull @Valid NewItem> items) {
	}

	public record NewItem(@NotNull UUID productId,
			@NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0) BigDecimal quantity) {
	}
}
