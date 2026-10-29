-- Proprietarios sao cadastros da plataforma; user_id referencia a conta MongoDB (sem FK entre bancos).
CREATE TABLE platform_owners (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 120),
    registration_email VARCHAR(254),
    email VARCHAR(254) UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'INVITED', 'ACTIVE')),
    user_id VARCHAR(255) UNIQUE,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Somente o hash do token e armazenado; o token em claro e exibido uma unica vez ao Super.
CREATE TABLE platform_owner_invites (
    token_hash VARCHAR(64) PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES platform_owners(id),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX platform_owner_invites_owner_idx ON platform_owner_invites (owner_id);

-- Proprietario do legado: sem e-mail, sem conta e sem lojas ate o convite ser emitido.
INSERT INTO platform_owners (id, name, status, created_by, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-00000000a001', 'Móveis Murimel', 'PENDING', 'system', now(), now());
