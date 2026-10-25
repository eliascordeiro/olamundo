CREATE TABLE sales_orders (
    store_id UUID NOT NULL REFERENCES stores(id),
    id UUID NOT NULL,
    status VARCHAR(9) NOT NULL CHECK (status IN ('DRAFT', 'CONFIRMED')),
    currency CHAR(3) NOT NULL CHECK (currency = 'BRL'),
    total NUMERIC(24,2) NOT NULL CHECK (total >= 0),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    confirmed_by VARCHAR(255),
    confirmed_at TIMESTAMPTZ,
    PRIMARY KEY (store_id, id),
    CHECK ((status = 'DRAFT' AND confirmed_at IS NULL AND confirmed_by IS NULL)
        OR (status = 'CONFIRMED' AND confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL))
);

CREATE INDEX sales_orders_store_page_idx ON sales_orders(store_id, created_at DESC, id DESC);

CREATE TABLE sales_order_lines (
    store_id UUID NOT NULL,
    order_id UUID NOT NULL,
    product_id UUID NOT NULL,
    product_name VARCHAR(120) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    line_total NUMERIC(24,2) NOT NULL CHECK (line_total = unit_price * quantity),
    PRIMARY KEY (store_id, order_id, product_id),
    FOREIGN KEY (store_id, order_id) REFERENCES sales_orders(store_id, id),
    FOREIGN KEY (store_id, product_id) REFERENCES catalog_products(store_id, id)
);
