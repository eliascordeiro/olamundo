-- Empresa da loja: o proprietario da plataforma. Clientes pertencem a ela e valem para todas as suas lojas.
ALTER TABLE stores ADD COLUMN owner_id UUID REFERENCES platform_owners(id);

-- Lojas ja existentes herdam a empresa do proprietario ativo que e OWNER delas.
UPDATE stores s SET owner_id = o.id
FROM store_memberships m JOIN platform_owners o ON o.user_id = m.user_id
WHERE m.store_id = s.id AND m.role = 'OWNER' AND o.status = 'ACTIVE' AND s.owner_id IS NULL;

CREATE INDEX stores_owner_idx ON stores (owner_id);

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES platform_owners(id),
    name VARCHAR(120) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 120),
    registration_name VARCHAR(120) NOT NULL,
    document VARCHAR(14) CHECK (document ~ '^([0-9]{11}|[0-9]{14})$'),
    rg VARCHAR(20),
    state_registration VARCHAR(20),
    birth_date DATE CHECK (birth_date >= DATE '1900-01-01'),
    phone VARCHAR(20),
    mobile VARCHAR(20),
    email VARCHAR(254),
    zip VARCHAR(8) CHECK (zip ~ '^[0-9]{8}$'),
    street VARCHAR(100),
    number VARCHAR(20),
    complement VARCHAR(100),
    neighborhood VARCHAR(60),
    city VARCHAR(60),
    state CHAR(2) CHECK (state ~ '^[A-Z]{2}$'),
    ibge VARCHAR(10),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Um CPF/CNPJ por empresa; clientes sem documento podem coexistir.
CREATE UNIQUE INDEX customers_owner_document_idx ON customers (owner_id, document) WHERE document IS NOT NULL;
CREATE INDEX customers_owner_name_idx ON customers (owner_id, lower(name), id);

-- Codigos do sistema antigo (varios podem ter sido unidos em um unico cliente).
CREATE TABLE customer_legacy_codes (
    owner_id UUID NOT NULL REFERENCES platform_owners(id),
    source VARCHAR(20) NOT NULL,
    legacy_code VARCHAR(32) NOT NULL,
    customer_id UUID NOT NULL REFERENCES customers(id),
    PRIMARY KEY (owner_id, source, legacy_code)
);
CREATE INDEX customer_legacy_codes_customer_idx ON customer_legacy_codes (customer_id);
