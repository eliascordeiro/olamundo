ALTER TABLE catalog_products ADD CONSTRAINT catalog_products_store_id_unique UNIQUE (store_id, id);

CREATE TABLE inventory_balances (
    store_id UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (store_id, product_id),
    FOREIGN KEY (store_id, product_id) REFERENCES catalog_products(store_id, id)
);

CREATE TABLE inventory_movements (
    store_id UUID NOT NULL,
    product_id UUID NOT NULL,
    id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    type VARCHAR(3) NOT NULL CHECK (type IN ('IN', 'OUT')),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    balance_after INTEGER NOT NULL CHECK (balance_after >= 0),
    reason VARCHAR(500) NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 2 AND 500),
    user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (store_id, product_id, id),
    UNIQUE (store_id, product_id, sequence),
    FOREIGN KEY (store_id, product_id) REFERENCES inventory_balances(store_id, product_id)
);
