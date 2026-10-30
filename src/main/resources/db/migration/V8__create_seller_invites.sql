-- Convite de vendedor sem conta: guarda o que sera concedido quando ele definir a senha.
-- Somente o hash do token e armazenado; o token em claro e exibido uma unica vez ao proprietario.
CREATE TABLE seller_invites (
    token_hash VARCHAR(64) PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    seller_name VARCHAR(120) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX seller_invites_email_idx ON seller_invites (email, created_by);

CREATE TABLE seller_invite_grants (
    token_hash VARCHAR(64) NOT NULL REFERENCES seller_invites(token_hash) ON DELETE CASCADE,
    store_id UUID NOT NULL REFERENCES stores(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('MANAGER', 'STAFF')),
    salesperson_id UUID,
    PRIMARY KEY (token_hash, store_id)
);
