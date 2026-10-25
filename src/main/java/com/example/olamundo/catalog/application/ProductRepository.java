package com.example.olamundo.catalog.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.catalog.domain.Product;

public interface ProductRepository {
	boolean insert(Product product);
	List<Product> list(UUID storeId, int limit, long offset);
	Optional<Product> find(UUID storeId, UUID productId);
}
