CREATE TABLE stores (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 100),
    created_at TIMESTAMPTZ NOT NULL
);

-- User IDs reference existing MongoDB accounts; there is no cross-database FK.
CREATE TABLE store_memberships (
    store_id UUID NOT NULL REFERENCES stores(id),
    user_id VARCHAR(255) NOT NULL CHECK (char_length(user_id) > 0),
    role VARCHAR(16) NOT NULL CHECK (role IN ('OWNER', 'MANAGER', 'STAFF')),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (store_id, user_id)
);

CREATE INDEX store_memberships_user_store_idx ON store_memberships(user_id, store_id);
