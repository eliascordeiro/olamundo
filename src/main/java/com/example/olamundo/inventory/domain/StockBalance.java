package com.example.olamundo.inventory.domain;

import java.util.UUID;

public record StockBalance(UUID storeId, UUID productId, int quantity, long version) {
}
