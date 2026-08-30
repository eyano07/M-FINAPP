-- ============================================================
-- MBSC Finapp - Schema initial (OHADA + Workflow notes de frais)
-- ============================================================

-- Roles ------------------------------------------------------
CREATE TABLE roles (
    id  BIGSERIAL PRIMARY KEY,
    nom VARCHAR(30) NOT NULL UNIQUE
);

-- Utilisateurs ----------------------------------------------
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(150) NOT NULL UNIQUE,
    mot_de_passe  VARCHAR(255) NOT NULL,
    nom           VARCHAR(100),
    prenom        VARCHAR(100),
    actif         BOOLEAN NOT NULL DEFAULT TRUE,
    date_creation TIMESTAMP,
    date_maj      TIMESTAMP
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- Plan comptable OHADA --------------------------------------
CREATE TABLE comptes_ohada (
    id        BIGSERIAL PRIMARY KEY,
    numero    VARCHAR(20) NOT NULL UNIQUE,
    libelle   VARCHAR(200) NOT NULL,
    type      VARCHAR(20),
    classe    INTEGER,
    parent_id BIGINT REFERENCES comptes_ohada(id)
);

-- Notes de frais --------------------------------------------
CREATE TABLE notes_frais (
    id                    BIGSERIAL PRIMARY KEY,
    reference             VARCHAR(30) NOT NULL UNIQUE,
    objet                 VARCHAR(200) NOT NULL,
    description           VARCHAR(2000),
    montant               NUMERIC(15,2) NOT NULL,
    statut                VARCHAR(30) NOT NULL,
    createur_id           BIGINT NOT NULL REFERENCES users(id),
    compte_imputation_id  BIGINT REFERENCES comptes_ohada(id),
    date_creation         TIMESTAMP,
    date_maj              TIMESTAMP
);
CREATE INDEX idx_notes_frais_statut    ON notes_frais(statut);
CREATE INDEX idx_notes_frais_createur  ON notes_frais(createur_id);

-- Observations (tracabilite du workflow) --------------------
CREATE TABLE observations_note (
    id               BIGSERIAL PRIMARY KEY,
    note_id          BIGINT NOT NULL REFERENCES notes_frais(id) ON DELETE CASCADE,
    auteur_id        BIGINT NOT NULL REFERENCES users(id),
    statut_au_moment VARCHAR(30),
    commentaire      VARCHAR(1000),
    date_action      TIMESTAMP
);

-- Pieces jointes --------------------------------------------
CREATE TABLE pieces_jointes (
    id              BIGSERIAL PRIMARY KEY,
    note_id         BIGINT NOT NULL REFERENCES notes_frais(id) ON DELETE CASCADE,
    nom_fichier     VARCHAR(255) NOT NULL,
    type_mime       VARCHAR(100),
    taille          BIGINT,
    chemin_stockage VARCHAR(500) NOT NULL
);

-- Transactions de caisse ------------------------------------
CREATE TABLE transactions_caisse (
    id                  BIGSERIAL PRIMARY KEY,
    uuid                UUID NOT NULL UNIQUE,
    reference           VARCHAR(30) NOT NULL UNIQUE,
    note_id             BIGINT REFERENCES notes_frais(id),
    montant             NUMERIC(15,2) NOT NULL,
    sens                VARCHAR(20) NOT NULL,
    caissier_id         BIGINT NOT NULL REFERENCES users(id),
    numero_recu         VARCHAR(30),
    date_operation      TIMESTAMP,
    date_enregistrement TIMESTAMP
);

-- Grand livre -----------------------------------------------
CREATE TABLE grand_livre (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES transactions_caisse(id) ON DELETE CASCADE,
    compte_id      BIGINT NOT NULL REFERENCES comptes_ohada(id),
    debit          NUMERIC(15,2) NOT NULL DEFAULT 0,
    credit         NUMERIC(15,2) NOT NULL DEFAULT 0,
    libelle        VARCHAR(255),
    date_ecriture  DATE NOT NULL
);
CREATE INDEX idx_grand_livre_compte ON grand_livre(compte_id);

-- Budgets ---------------------------------------------------
CREATE TABLE budgets (
    id              BIGSERIAL PRIMARY KEY,
    intitule        VARCHAR(200) NOT NULL,
    exercice        INTEGER NOT NULL,
    statut          VARCHAR(20) NOT NULL,
    elabore_par_id  BIGINT NOT NULL REFERENCES users(id),
    approuve_par_id BIGINT REFERENCES users(id),
    observation     VARCHAR(1000),
    date_creation   TIMESTAMP
);

CREATE TABLE lignes_budget (
    id              BIGSERIAL PRIMARY KEY,
    budget_id       BIGINT NOT NULL REFERENCES budgets(id) ON DELETE CASCADE,
    compte_id       BIGINT NOT NULL REFERENCES comptes_ohada(id),
    montant_prevu   NUMERIC(15,2) NOT NULL,
    montant_realise NUMERIC(15,2) NOT NULL DEFAULT 0
);

-- Donnees de reference : roles ------------------------------
INSERT INTO roles (nom) VALUES
    ('ADMIN'), ('DG'), ('DA'), ('DFIN'), ('DIRECTEUR'), ('CAISSIER');
