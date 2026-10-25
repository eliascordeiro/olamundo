CREATE TABLE catalog_products (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id),
    name VARCHAR(120) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 120),
    sku VARCHAR(64) NOT NULL CHECK (sku ~ '^[A-Z0-9][A-Z0-9._-]{0,63}$'),
    description VARCHAR(2000) NOT NULL DEFAULT '',
    price NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    currency CHAR(3) NOT NULL CHECK (currency = 'BRL'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT catalog_products_store_sku_unique UNIQUE (store_id, sku)
);

CREATE INDEX catalog_products_store_page_idx ON catalog_products(store_id, created_at, id);
