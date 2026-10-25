package com.example.olamundo.catalog.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.example.olamundo.catalog.domain.Product;
import com.example.olamundo.shared.PageSlice;
import com.example.olamundo.stores.application.StoreAuthorization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CatalogService {
	private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");
	private final ProductRepository products;
	private final StoreAuthorization authorization;

	public CatalogService(ProductRepository products, StoreAuthorization authorization) {
		this.products = products;
		this.authorization = authorization;
	}

	@Transactional
	public Product create(UUID storeId, String userId, String name, String sku, String description, BigDecimal price) {
		authorization.requireCatalogWriter(storeId, userId);
		String normalizedName = name == null ? "" : name.strip();
		String normalizedSku = sku == null ? "" : sku.strip().toUpperCase(Locale.ROOT);
		if (normalizedName.length() < 2 || normalizedName.length() > 120
				|| !normalizedSku.matches("[A-Z0-9][A-Z0-9._-]{0,63}")
				|| (description != null && description.length() > 2000)
				|| price == null || price.signum() < 0 || price.compareTo(MAX_PRICE) > 0 || price.scale() > 2) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confira nome, SKU, descricao e preco do produto.");
		}
		var product = new Product(UUID.randomUUID(), storeId, normalizedName, normalizedSku,
				description == null ? "" : description.strip(), price.setScale(2, RoundingMode.UNNECESSARY),
				"BRL", Instant.now());
		if (!products.insert(product)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Ja existe um produto com este SKU nesta loja.");
		}
		return product;
	}

	@Transactional(readOnly = true)
	public PageSlice<Product> list(UUID storeId, String userId, int page, int size) {
		authorization.requireMember(storeId, userId);
		PageSlice.validate(page, size);
		return PageSlice.from(products.list(storeId, size + 1, (long) page * size), page, size);
	}

	@Transactional(readOnly = true)
	public Product get(UUID storeId, UUID productId, String userId) {
		authorization.requireMember(storeId, userId);
		return products.find(storeId, productId).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto nao encontrado nesta loja."));
	}
}
