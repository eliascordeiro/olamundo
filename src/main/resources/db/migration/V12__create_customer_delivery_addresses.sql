CREATE TABLE customer_delivery_addresses (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES platform_owners(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    registration_name VARCHAR(100) NOT NULL,
    zip VARCHAR(8) CHECK (zip ~ '^[0-9]{8}$'),
    street VARCHAR(100) NOT NULL CHECK (char_length(btrim(street)) >= 2),
    number VARCHAR(20),
    complement VARCHAR(100),
    neighborhood VARCHAR(60),
    city VARCHAR(60),
    state CHAR(2) CHECK (state ~ '^[A-Z]{2}$'),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX customer_delivery_addresses_customer_idx ON customer_delivery_addresses (owner_id, customer_id, created_at, id);
