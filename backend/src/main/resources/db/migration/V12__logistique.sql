-- ============================================================
-- MBSC Finapp - V12 : Module Logistique (Stock + Transport)
-- ============================================================

-- Sequences de references metier
CREATE SEQUENCE IF NOT EXISTS seq_mouvement_stock  START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS seq_trajet           START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS seq_depense_vehicule START WITH 1 INCREMENT BY 1;

-- Nouveau role logistique
INSERT INTO roles (nom) VALUES ('LOGISTIQUE') ON CONFLICT (nom) DO NOTHING;

-- ------------------------------------------------------------
-- Stock : articles & entrepots
-- ------------------------------------------------------------
CREATE TABLE articles (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(40) NOT NULL UNIQUE,
    libelle          VARCHAR(200) NOT NULL,
    unite_mesure     VARCHAR(20),
    compte_stock_id  BIGINT REFERENCES comptes_ohada(id),
    compte_charge_id BIGINT REFERENCES comptes_ohada(id),
    stock_min        NUMERIC(15,3) NOT NULL DEFAULT 0,
    actif            BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE entrepots (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(40) NOT NULL UNIQUE,
    nom          VARCHAR(200) NOT NULL,
    localisation VARCHAR(255),
    actif        BOOLEAN NOT NULL DEFAULT TRUE
);

-- ------------------------------------------------------------
-- Stock : mouvements
-- ------------------------------------------------------------
CREATE TABLE mouvements_stock (
    id                     BIGSERIAL PRIMARY KEY,
    reference              VARCHAR(30) NOT NULL UNIQUE,
    type                   VARCHAR(20) NOT NULL,
    date_mouvement         DATE NOT NULL,
    libelle                VARCHAR(255),
    statut                 VARCHAR(20) NOT NULL,
    compte_contrepartie_id BIGINT REFERENCES comptes_ohada(id),
    piece_id               BIGINT REFERENCES pieces_comptables(id),
    created_by_id          BIGINT REFERENCES users(id),
    created_at             TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_mouvements_stock_statut ON mouvements_stock(statut);
CREATE INDEX idx_mouvements_stock_date   ON mouvements_stock(date_mouvement);

CREATE TABLE lignes_mouvement_stock (
    id                 BIGSERIAL PRIMARY KEY,
    mouvement_id       BIGINT NOT NULL REFERENCES mouvements_stock(id) ON DELETE CASCADE,
    article_id         BIGINT NOT NULL REFERENCES articles(id),
    entrepot_source_id BIGINT REFERENCES entrepots(id),
    entrepot_cible_id  BIGINT REFERENCES entrepots(id),
    quantite           NUMERIC(15,3) NOT NULL,
    cout_unitaire      NUMERIC(15,2) NOT NULL DEFAULT 0,
    montant            NUMERIC(15,2) NOT NULL DEFAULT 0
);
CREATE INDEX idx_lignes_mvt_stock_mvt ON lignes_mouvement_stock(mouvement_id);

-- ------------------------------------------------------------
-- Stock : niveaux (Bin) & grand livre de stock (Stock Ledger)
-- ------------------------------------------------------------
CREATE TABLE stock_niveaux (
    id            BIGSERIAL PRIMARY KEY,
    article_id    BIGINT NOT NULL REFERENCES articles(id),
    entrepot_id   BIGINT NOT NULL REFERENCES entrepots(id),
    quantite      NUMERIC(15,3) NOT NULL DEFAULT 0,
    valeur_totale NUMERIC(15,2) NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_niveau UNIQUE (article_id, entrepot_id)
);

CREATE TABLE stock_grand_livre (
    id              BIGSERIAL PRIMARY KEY,
    article_id      BIGINT NOT NULL REFERENCES articles(id),
    entrepot_id     BIGINT NOT NULL REFERENCES entrepots(id),
    date_ecriture   DATE NOT NULL,
    mouvement_id    BIGINT REFERENCES mouvements_stock(id) ON DELETE CASCADE,
    qte_entree      NUMERIC(15,3) NOT NULL DEFAULT 0,
    qte_sortie      NUMERIC(15,3) NOT NULL DEFAULT 0,
    qte_apres       NUMERIC(15,3) NOT NULL DEFAULT 0,
    valeur_unitaire NUMERIC(15,2) NOT NULL DEFAULT 0,
    valeur_apres    NUMERIC(15,2) NOT NULL DEFAULT 0
);
CREATE INDEX idx_stock_gl_article  ON stock_grand_livre(article_id);
CREATE INDEX idx_stock_gl_entrepot ON stock_grand_livre(entrepot_id);
CREATE INDEX idx_stock_gl_date     ON stock_grand_livre(date_ecriture);

-- ------------------------------------------------------------
-- Transport / Flotte
-- ------------------------------------------------------------
CREATE TABLE vehicules (
    id               BIGSERIAL PRIMARY KEY,
    immatriculation  VARCHAR(40) NOT NULL UNIQUE,
    marque           VARCHAR(100),
    modele           VARCHAR(100),
    type             VARCHAR(60),
    date_acquisition DATE,
    actif            BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE trajets (
    id            BIGSERIAL PRIMARY KEY,
    reference     VARCHAR(30) NOT NULL UNIQUE,
    vehicule_id   BIGINT NOT NULL REFERENCES vehicules(id),
    conducteur_id BIGINT REFERENCES users(id),
    date_depart   TIMESTAMP,
    date_arrivee  TIMESTAMP,
    origine       VARCHAR(200),
    destination   VARCHAR(200),
    distance_km   NUMERIC(12,2) NOT NULL DEFAULT 0,
    statut        VARCHAR(20) NOT NULL
);
CREATE INDEX idx_trajets_vehicule ON trajets(vehicule_id);

CREATE TABLE depenses_vehicule (
    id               BIGSERIAL PRIMARY KEY,
    reference        VARCHAR(30) NOT NULL UNIQUE,
    vehicule_id      BIGINT NOT NULL REFERENCES vehicules(id),
    trajet_id        BIGINT REFERENCES trajets(id),
    type             VARCHAR(20) NOT NULL,
    montant          NUMERIC(15,2) NOT NULL,
    date_depense     DATE NOT NULL,
    compte_charge_id BIGINT REFERENCES comptes_ohada(id),
    piece_id         BIGINT REFERENCES pieces_comptables(id),
    created_by_id    BIGINT REFERENCES users(id)
);
CREATE INDEX idx_depenses_vehicule_veh ON depenses_vehicule(vehicule_id);
