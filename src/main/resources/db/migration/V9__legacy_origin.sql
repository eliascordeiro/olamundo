-- Origem no legado: permite repetir a carga sem duplicar e rastrear o codigo antigo.
ALTER TABLE stores ADD COLUMN legacy_code VARCHAR(32);
ALTER TABLE salespeople ADD COLUMN legacy_code VARCHAR(32);

CREATE UNIQUE INDEX stores_legacy_code_idx ON stores (legacy_code) WHERE legacy_code IS NOT NULL;
CREATE UNIQUE INDEX salespeople_legacy_code_idx ON salespeople (store_id, legacy_code) WHERE legacy_code IS NOT NULL;
