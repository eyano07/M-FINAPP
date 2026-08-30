-- ============================================================
-- MBSC Finapp - V11 : Pièces comptables (Journal Entry)
-- ============================================================

CREATE SEQUENCE IF NOT EXISTS seq_piece_comptable START WITH 1 INCREMENT BY 1;

CREATE TABLE pieces_comptables (
    id            BIGSERIAL PRIMARY KEY,
    reference     VARCHAR(30) NOT NULL UNIQUE,
    date_piece    DATE NOT NULL,
    journal       VARCHAR(30) NOT NULL,
    libelle       VARCHAR(255),
    statut        VARCHAR(20) NOT NULL,
    total_debit   NUMERIC(15,2) NOT NULL DEFAULT 0,
    total_credit  NUMERIC(15,2) NOT NULL DEFAULT 0,
    created_by_id BIGINT REFERENCES users(id),
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    piece_origine_id BIGINT REFERENCES pieces_comptables(id)
);

CREATE INDEX idx_pieces_comptables_date ON pieces_comptables(date_piece);
CREATE INDEX idx_pieces_comptables_statut ON pieces_comptables(statut);

ALTER TABLE grand_livre
    ADD COLUMN piece_id BIGINT REFERENCES pieces_comptables(id);

CREATE INDEX idx_grand_livre_piece ON grand_livre(piece_id);
