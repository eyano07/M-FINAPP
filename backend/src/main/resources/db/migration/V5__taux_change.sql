-- Historique des taux de change USD → FC
CREATE TABLE taux_change (
    id              BIGSERIAL PRIMARY KEY,
    taux            NUMERIC(18, 4) NOT NULL CHECK (taux > 0),
    date_effet      DATE           NOT NULL,
    note            VARCHAR(500),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- Index pour retrouver rapidement le taux le plus récent
CREATE INDEX idx_taux_change_date_effet ON taux_change (date_effet DESC);
