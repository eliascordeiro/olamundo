package com.example.olamundo.orders.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.example.olamundo.catalog.application.CatalogService;
import com.example.olamundo.inventory.application.InventoryService;
import com.example.olamundo.inventory.domain.StockMovement.MovementType;
import com.example.olamundo.orders.domain.Order;
import com.example.olamundo.shared.PageSlice;
import com.example.olamundo.stores.application.StoreAuthorization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {
	private final OrderRepository orders;
	private final CatalogService catalog;
	private final InventoryService inventory;
	private final StoreAuthorization authorization;

	public OrderService(OrderRepository orders, CatalogService catalog, InventoryService inventory,
			StoreAuthorization authorization) {
		this.orders = orders;
		this.catalog = catalog;
		this.inventory = inventory;
		this.authorization = authorization;
	}

	public record RequestedItem(UUID productId, BigDecimal quantity) {
	}

	@Transactional
	public Order create(UUID storeId, String userId, UUID requestId, List<RequestedItem> requested) {
		authorization.requireOrderWriter(storeId, userId);
		validate(requestId, requested);
		var sorted = requested.stream().sorted(Comparator.comparing(item -> item.productId().toString())).toList();
		boolean inserted = orders.insertDraft(storeId, requestId, userId, now());
		if (!inserted) {
			var previous = find(storeId, requestId, false);
			boolean same = previous.summary().createdBy().equals(userId) && previous.items().size() == sorted.size();
			for (int i = 0; same && i < sorted.size(); i++) {
				same = previous.items().get(i).productId().equals(sorted.get(i).productId())
						&& previous.items().get(i).quantity() == sorted.get(i).quantity().intValueExact();
			}
			if (!same) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador de pedido ja usado com outros itens ou responsavel.");
			}
			return previous;
		}
		var lines = new ArrayList<Order.Line>();
		BigDecimal total = new BigDecimal("0.00");
		for (var item : sorted) {
			var product = catalog.get(storeId, item.productId(), userId);
			int units = item.quantity().intValueExact();
			var lineTotal = product.price().multiply(BigDecimal.valueOf(units));
			lines.add(new Order.Line(product.id(), product.name(), product.sku(), units, product.price(), lineTotal));
			total = total.add(lineTotal);
		}
		orders.saveLines(storeId, requestId, lines, total);
		return find(storeId, requestId, false);
	}

	@Transactional(readOnly = true)
	public Order get(UUID storeId, UUID id, String userId) {
		authorization.requireMember(storeId, userId);
		return find(storeId, id, false);
	}

	@Transactional(readOnly = true)
	public PageSlice<Order.Summary> list(UUID storeId, String userId, int page, int size) {
		authorization.requireMember(storeId, userId);
		PageSlice.validate(page, size);
		return PageSlice.from(orders.list(storeId, size + 1, (long) page * size), page, size);
	}

	@Transactional
	public Order confirm(UUID storeId, UUID id, String userId) {
		authorization.requireOrderWriter(storeId, userId);
		var order = find(storeId, id, true);
		if (order.summary().status() == Order.Status.CONFIRMED) return order;
		// Lock order first, then stock rows in canonical product order across every order.
		for (var line : order.items()) {
			inventory.move(storeId, line.productId(), userId, UUID.randomUUID(), MovementType.OUT,
					BigDecimal.valueOf(line.quantity()), "Confirmacao do pedido " + id);
		}
		orders.confirm(storeId, id, userId, now());
		return find(storeId, id, false);
	}

	private Order find(UUID storeId, UUID id, boolean lock) {
		return orders.find(storeId, id, lock).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido nao encontrado nesta loja."));
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	private static void validate(UUID requestId, List<RequestedItem> items) {
		if (requestId == null || items == null || items.isEmpty() || items.size() > 50) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe identificador e de 1 a 50 produtos distintos.");
		}
		var ids = new HashSet<UUID>();
		for (var item : items) {
			if (item == null || item.productId() == null || !ids.add(item.productId()) || item.quantity() == null
					|| item.quantity().signum() <= 0 || item.quantity().scale() > 0
					|| item.quantity().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use produtos distintos e quantidades inteiras de 1 a 2147483647.");
			}
		}
	}
}
