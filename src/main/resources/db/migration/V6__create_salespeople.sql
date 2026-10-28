CREATE TABLE salespeople (
    store_id UUID NOT NULL REFERENCES stores(id),
    id UUID NOT NULL,
    name VARCHAR(120) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 120),
    registration_name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    user_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (store_id, id),
    UNIQUE (store_id, user_id),
    FOREIGN KEY (store_id, user_id) REFERENCES store_memberships(store_id, user_id)
);

CREATE INDEX salespeople_store_created_idx ON salespeople (store_id, created_at, id);
