-- ============================================================
-- MBSC Finapp - V19 : module Vente (marchandises + services) + TVA
--
--   * taux de TVA versionne par date (pilote par l'administrateur)
--   * repertoire clients (le nom libre reste possible sur la vente)
--   * articles : type MARCHANDISE/SERVICE, prix de vente, compte
--     de produit, assujettissement a la TVA
--   * ventes + lignes de vente
--
--   Comptes OHADA vises (tous deja presents et imputables) :
--     7011 Ventes de marchandises · 7061 Services vendus
--     4111 Clients locaux · 4431 TVA facturee
--     3111 Marchandises · 6012 Variation de stocks (via le stock)
-- ============================================================

CREATE SEQUENCE IF NOT EXISTS seq_vente START WITH 1 INCREMENT BY 1;

-- ── 1. Taux de TVA ───────────────────────────────────────────
-- Historique append-only : une facture emise conserve le taux en
-- vigueur a sa date, meme apres un changement de taux legal.
CREATE TABLE taux_tva (
    id            BIGSERIAL PRIMARY KEY,
    taux          NUMERIC(5,2) NOT NULL CHECK (taux >= 0 AND taux <= 100),
    date_effet    DATE NOT NULL,
    note          VARCHAR(500),
    created_by_id BIGINT REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_taux_tva_date_effet ON taux_tva (date_effet DESC);

-- Taux legal en vigueur en RDC.
INSERT INTO taux_tva (taux, date_effet, note)
VALUES (16.00, DATE '2000-01-01', 'Taux initial (16 %)');

-- ── 2. Clients ───────────────────────────────────────────────
CREATE TABLE clients (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(40) NOT NULL UNIQUE,
    nom           VARCHAR(200) NOT NULL,
    telephone     VARCHAR(40),
    email         VARCHAR(150),
    adresse       VARCHAR(255),
    actif         BOOLEAN NOT NULL DEFAULT TRUE,
    date_creation TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_clients_nom ON clients (nom);

-- ── 3. Articles : vendables ──────────────────────────────────
ALTER TABLE articles
    ADD COLUMN type              VARCHAR(20) NOT NULL DEFAULT 'MARCHANDISE',
    ADD COLUMN prix_vente        NUMERIC(15,2),
    ADD COLUMN compte_produit_id BIGINT REFERENCES comptes_ohada(id),
    ADD COLUMN soumis_tva        BOOLEAN NOT NULL DEFAULT TRUE;

-- Les articles existants sont tous des marchandises stockees.
UPDATE articles SET type = 'MARCHANDISE' WHERE type IS NULL;

-- ── 4. Ventes ────────────────────────────────────────────────
CREATE TABLE ventes (
    id                 BIGSERIAL PRIMARY KEY,
    reference          VARCHAR(30) NOT NULL UNIQUE,
    date_vente         DATE NOT NULL,
    client_id          BIGINT REFERENCES clients(id),
    client_nom         VARCHAR(200),
    statut             VARCHAR(20) NOT NULL,
    mode_reglement     VARCHAR(20) NOT NULL,
    etablissement_id   BIGINT REFERENCES etablissements_tresorerie(id),
    entrepot_id        BIGINT REFERENCES entrepots(id),
    total_ht           NUMERIC(15,2) NOT NULL DEFAULT 0,
    total_tva          NUMERIC(15,2) NOT NULL DEFAULT 0,
    total_ttc          NUMERIC(15,2) NOT NULL DEFAULT 0,
    taux_tva_applique  NUMERIC(5,2),
    piece_id           BIGINT REFERENCES pieces_comptables(id),
    mouvement_id       BIGINT REFERENCES mouvements_stock(id),
    created_by_id      BIGINT REFERENCES users(id),
    created_at         TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ventes_statut ON ventes (statut);
CREATE INDEX idx_ventes_date   ON ventes (date_vente DESC);
CREATE INDEX idx_ventes_client ON ventes (client_id);

CREATE TABLE lignes_vente (
    id             BIGSERIAL PRIMARY KEY,
    vente_id       BIGINT NOT NULL REFERENCES ventes(id) ON DELETE CASCADE,
    article_id     BIGINT NOT NULL REFERENCES articles(id),
    designation    VARCHAR(200) NOT NULL,
    quantite       NUMERIC(15,3) NOT NULL CHECK (quantite > 0),
    prix_unitaire  NUMERIC(15,2) NOT NULL CHECK (prix_unitaire >= 0),
    soumis_tva     BOOLEAN NOT NULL DEFAULT TRUE,
    montant_ht     NUMERIC(15,2) NOT NULL DEFAULT 0,
    montant_tva    NUMERIC(15,2) NOT NULL DEFAULT 0,
    ordre          INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX idx_lignes_vente_vente ON lignes_vente (vente_id);
