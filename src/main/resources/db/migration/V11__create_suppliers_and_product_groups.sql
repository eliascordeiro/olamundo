-- Fornecedores, grupos e subgrupos de produtos: cadastros da empresa (proprietario), usados por todas as lojas dela.
CREATE TABLE suppliers (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES platform_owners(id),
    name VARCHAR(120) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 120),
    registration_name VARCHAR(120) NOT NULL,
    document VARCHAR(14) CHECK (document ~ '^([0-9]{11}|[0-9]{14})$'),
    state_registration VARCHAR(20),
    email VARCHAR(254),
    phone VARCHAR(20),
    fax VARCHAR(20),
    contact VARCHAR(60),
    zip VARCHAR(8) CHECK (zip ~ '^[0-9]{8}$'),
    street VARCHAR(100),
    neighborhood VARCHAR(60),
    city VARCHAR(60),
    state CHAR(2) CHECK (state ~ '^[A-Z]{2}$'),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX suppliers_owner_document_idx ON suppliers (owner_id, document) WHERE document IS NOT NULL;
CREATE INDEX suppliers_owner_name_idx ON suppliers (owner_id, lower(name), id);

CREATE TABLE product_groups (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES platform_owners(id),
    name VARCHAR(60) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 60),
    registration_name VARCHAR(60) NOT NULL,
    ncm VARCHAR(20),
    commission NUMERIC(5, 2) NOT NULL DEFAULT 0 CHECK (commission BETWEEN 0 AND 100),
    assembly_commission NUMERIC(5, 2) NOT NULL DEFAULT 0 CHECK (assembly_commission BETWEEN 0 AND 100),
    assembly_discount NUMERIC(5, 2) NOT NULL DEFAULT 0 CHECK (assembly_discount BETWEEN 0 AND 100),
    markup NUMERIC(7, 2) NOT NULL DEFAULT 0 CHECK (markup BETWEEN 0 AND 1000),
    pauta_discount NUMERIC(5, 2) NOT NULL DEFAULT 0 CHECK (pauta_discount BETWEEN 0 AND 100),
    flexible_discount NUMERIC(5, 2) NOT NULL DEFAULT 0 CHECK (flexible_discount BETWEEN 0 AND 100),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX product_groups_owner_name_idx ON product_groups (owner_id, lower(name));

CREATE TABLE product_subgroups (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES platform_owners(id),
    group_id UUID NOT NULL REFERENCES product_groups(id),
    name VARCHAR(60) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 60),
    registration_name VARCHAR(60) NOT NULL,
    markup NUMERIC(7, 2) NOT NULL DEFAULT 0 CHECK (markup BETWEEN 0 AND 1000),
    margin NUMERIC(7, 2) NOT NULL DEFAULT 0 CHECK (margin BETWEEN 0 AND 1000),
    pauta_discount NUMERIC(5, 2) NOT NULL DEFAULT 0 CHECK (pauta_discount BETWEEN 0 AND 100),
    flexible_discount NUMERIC(5, 2) NOT NULL DEFAULT 0 CHECK (flexible_discount BETWEEN 0 AND 100),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX product_subgroups_group_idx ON product_subgroups (group_id, lower(name), id);

ALTER TABLE catalog_products ADD COLUMN group_id UUID REFERENCES product_groups(id);
ALTER TABLE catalog_products ADD COLUMN subgroup_id UUID REFERENCES product_subgroups(id);

-- Codigos do sistema antigo para qualquer cadastro migrado (varios podem apontar para um registro unido).
CREATE TABLE legacy_codes (
    owner_id UUID NOT NULL REFERENCES platform_owners(id),
    source VARCHAR(20) NOT NULL,
    entity VARCHAR(20) NOT NULL,
    legacy_code VARCHAR(40) NOT NULL,
    entity_id UUID NOT NULL,
    PRIMARY KEY (owner_id, source, entity, legacy_code)
);
CREATE INDEX legacy_codes_entity_idx ON legacy_codes (entity, entity_id);
