-- =====================================================================
-- V138 : régularisations, inventaires physiques, archives de clôture, composants d'immobilisation,
-- double authentification et verrou de connexion persistant
-- (audit SYSCOHADA du 10/11/2026 : C-11, C-12, C-13, C-18, C-22, C-23)
-- =====================================================================

-- ---------------------------------------------------------------------
-- C-11 : charges et produits constatés d'avance, factures non parvenues, produits à recevoir. Chaque
-- régularisation écrit sa pièce à la date d'arrêté et son extourne au lendemain (premier jour de
-- l'exercice suivant pour une régularisation de clôture).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS regularisations (
    id                          BIGSERIAL     PRIMARY KEY,
    type_regularisation         VARCHAR(10)   NOT NULL,
    libelle                     VARCHAR(255)  NOT NULL,
    compte_gestion_id           BIGINT        NOT NULL REFERENCES comptes_ohada(id),
    compte_regularisation_id    BIGINT        NOT NULL REFERENCES comptes_ohada(id),
    montant                     NUMERIC(15,2) NOT NULL CHECK (montant > 0),
    date_regularisation         DATE          NOT NULL,
    date_extourne               DATE          NOT NULL,
    piece_id                    BIGINT        REFERENCES pieces_comptables(id),
    piece_extourne_id           BIGINT        REFERENCES pieces_comptables(id),
    statut                      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_by_id               BIGINT        REFERENCES users(id),
    created_at                  TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT ck_regularisations_type CHECK (type_regularisation IN ('CCA', 'PCA', 'FNP', 'FAE')),
    CONSTRAINT ck_regularisations_statut CHECK (statut IN ('ACTIVE', 'ANNULEE')),
    CONSTRAINT ck_regularisations_dates CHECK (date_extourne > date_regularisation)
);
CREATE INDEX IF NOT EXISTS idx_regularisations_date ON regularisations (date_regularisation);

-- C-12 : pièces de réévaluation générale des soldes en devise (et leur extourne d'ouverture).
ALTER TABLE pieces_comptables ADD COLUMN IF NOT EXISTS extourne_ouverture BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------
-- C-22 : inventaire physique des stocks (comptage, écart, ajustement, procès-verbal) et des
-- immobilisations (présence, état, procès-verbal).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inventaires_stock (
    id                BIGSERIAL    PRIMARY KEY,
    reference         VARCHAR(30)  NOT NULL UNIQUE,
    entrepot_id       BIGINT       NOT NULL REFERENCES entrepots(id),
    date_inventaire   DATE         NOT NULL,
    statut            VARCHAR(20)  NOT NULL DEFAULT 'EN_COURS',
    observations      VARCHAR(1000),
    created_by_id     BIGINT       REFERENCES users(id),
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    valide_par_id     BIGINT       REFERENCES users(id),
    valide_le         TIMESTAMP,
    CONSTRAINT ck_inventaires_stock_statut CHECK (statut IN ('EN_COURS', 'VALIDE', 'ANNULE'))
);
CREATE TABLE IF NOT EXISTS inventaires_stock_lignes (
    id                   BIGSERIAL      PRIMARY KEY,
    inventaire_id        BIGINT         NOT NULL REFERENCES inventaires_stock(id) ON DELETE CASCADE,
    article_id           BIGINT         NOT NULL REFERENCES articles(id),
    quantite_theorique   NUMERIC(15,3)  NOT NULL,
    quantite_comptee     NUMERIC(15,3),
    cout_unitaire        NUMERIC(15,4)  NOT NULL DEFAULT 0,
    mouvement_id         BIGINT         REFERENCES mouvements_stock(id),
    commentaire          VARCHAR(500),
    CONSTRAINT uq_inventaire_stock_article UNIQUE (inventaire_id, article_id)
);
CREATE SEQUENCE IF NOT EXISTS seq_inventaire START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS inventaires_immobilisations (
    id                BIGSERIAL    PRIMARY KEY,
    reference         VARCHAR(30)  NOT NULL UNIQUE,
    date_inventaire   DATE         NOT NULL,
    statut            VARCHAR(20)  NOT NULL DEFAULT 'EN_COURS',
    observations      VARCHAR(1000),
    created_by_id     BIGINT       REFERENCES users(id),
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    valide_par_id     BIGINT       REFERENCES users(id),
    valide_le         TIMESTAMP,
    CONSTRAINT ck_inventaires_immo_statut CHECK (statut IN ('EN_COURS', 'VALIDE', 'ANNULE'))
);
CREATE TABLE IF NOT EXISTS inventaires_immobilisations_lignes (
    id                      BIGSERIAL     PRIMARY KEY,
    inventaire_id           BIGINT        NOT NULL REFERENCES inventaires_immobilisations(id) ON DELETE CASCADE,
    immobilisation_id       BIGINT        NOT NULL REFERENCES immobilisations(id),
    present                 BOOLEAN,
    etat                    VARCHAR(20),
    localisation_constatee  VARCHAR(255),
    commentaire             VARCHAR(500),
    CONSTRAINT uq_inventaire_immo UNIQUE (inventaire_id, immobilisation_id),
    CONSTRAINT ck_inventaire_immo_etat CHECK (etat IS NULL OR etat IN ('BON', 'MOYEN', 'MAUVAIS', 'HORS_USAGE'))
);

-- ---------------------------------------------------------------------
-- C-13 : archive figée des livres et états à chaque clôture d'exercice, avec son empreinte SHA-256.
-- En ajout seul : une archive ne se modifie ni ne se supprime.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS archives_cloture (
    id               BIGSERIAL    PRIMARY KEY,
    date_cloture     DATE         NOT NULL,
    nom_fichier      VARCHAR(200) NOT NULL,
    contenu          BYTEA        NOT NULL,
    taille           BIGINT       NOT NULL,
    empreinte        CHAR(64)     NOT NULL,
    cree_par_id      BIGINT       REFERENCES users(id),
    cree_le          TIMESTAMP    NOT NULL DEFAULT now()
);
DROP TRIGGER IF EXISTS ajout_seul ON archives_cloture;
CREATE TRIGGER ajout_seul BEFORE UPDATE OR DELETE ON archives_cloture
    FOR EACH ROW EXECUTE FUNCTION interdire_reecriture();

-- ---------------------------------------------------------------------
-- C-18 : composants d'une immobilisation (chacun sa durée) et nature de la cession.
-- ---------------------------------------------------------------------
ALTER TABLE immobilisations ADD COLUMN IF NOT EXISTS parent_id BIGINT REFERENCES immobilisations(id);
ALTER TABLE immobilisations ADD COLUMN IF NOT EXISTS type_cession VARCHAR(20);
CREATE INDEX IF NOT EXISTS idx_immobilisations_parent ON immobilisations (parent_id);

-- ---------------------------------------------------------------------
-- C-23 : double authentification (code à usage unique, RFC 6238) et verrou de connexion qui survit
-- au redémarrage du serveur. Le secret est chiffré avec la clé du serveur : la table n'est pas
-- recopiée vers la copie en ligne (chaque serveur a ses propres enrôlements).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS double_authentification (
    user_id          BIGINT       PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret_chiffre   VARCHAR(300) NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT FALSE,
    activee_le       TIMESTAMP,
    dernier_pas      BIGINT
);

CREATE TABLE IF NOT EXISTS tentatives_connexion (
    identifiant      VARCHAR(150) PRIMARY KEY,
    echecs           INTEGER      NOT NULL,
    dernier_echec    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS parametres_securite (
    id                        BIGINT   PRIMARY KEY DEFAULT 1,
    double_auth_obligatoire   BOOLEAN  NOT NULL DEFAULT FALSE,
    maj_par_id                BIGINT   REFERENCES users(id),
    maj_le                    TIMESTAMP,
    CONSTRAINT ck_parametres_securite_singleton CHECK (id = 1)
);
INSERT INTO parametres_securite (id) VALUES (1) ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- C-20 : le caissier ne pointe ni ne régularise plus le rapprochement (lecture seule) ; le DA, qui seul rouvre un
-- mois rapproché et validé, reçoit l'écriture sur le module (ses autres actions restent réservées à la comptabilité).
-- ---------------------------------------------------------------------
UPDATE role_permissions SET niveau = 'LECTURE' WHERE role = 'CAISSIER' AND module = 'RAPPROCHEMENT' AND niveau = 'ECRITURE';
UPDATE role_permissions SET niveau = 'ECRITURE' WHERE role = 'DA' AND module = 'RAPPROCHEMENT' AND niveau = 'LECTURE';

-- C-06 : le comptable saisit les pièces comptables, le DFIN les valide ; le comptable reçoit donc l'écriture sur le
-- module Comptabilité (la validation reste réservée au DFIN par le serveur).
UPDATE role_permissions SET niveau = 'ECRITURE' WHERE role = 'COMPTABLE' AND module = 'COMPTABILITE' AND niveau = 'LECTURE';
