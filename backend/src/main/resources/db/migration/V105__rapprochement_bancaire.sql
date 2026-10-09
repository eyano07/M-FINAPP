-- ---------------------------------------------------------------------------
-- Rapprochement bancaire (module RAPPROCHEMENT, sous-module de COMPTABILITE).
--
-- Chaque releve d'un etablissement (banque 521x ou operateur mobile money
-- 552x) pour un mois est pointe contre les ecritures de son compte au grand
-- livre. Un pointage regroupe une ou plusieurs lignes de releve et une ou
-- plusieurs ecritures de meme total. Un releve VALIDE verrouille le compte
-- jusqu'a la fin de son mois (aucune nouvelle ecriture datee de ce mois).
-- ---------------------------------------------------------------------------

-- Devise de tenue du compte (le releve de la banque est dans cette devise).
ALTER TABLE etablissements_tresorerie ADD COLUMN devise VARCHAR(3) NOT NULL DEFAULT 'USD';

CREATE TABLE releves_bancaires (
    id               BIGSERIAL PRIMARY KEY,
    etablissement_id BIGINT NOT NULL REFERENCES etablissements_tresorerie(id),
    mois             INTEGER NOT NULL CHECK (mois BETWEEN 1 AND 12),
    annee            INTEGER NOT NULL,
    devise           VARCHAR(3) NOT NULL,
    solde_ouverture  NUMERIC(18, 2) NOT NULL,
    solde_cloture    NUMERIC(18, 2) NOT NULL,
    statut           VARCHAR(20) NOT NULL DEFAULT 'EN_COURS',
    source           VARCHAR(255),
    cree_par_id      BIGINT REFERENCES users(id),
    valide_par_id    BIGINT REFERENCES users(id),
    date_validation  TIMESTAMP,
    motif_devalidation VARCHAR(500),
    date_creation    TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (etablissement_id, annee, mois)
);

CREATE TABLE pointages_rapprochement (
    id            BIGSERIAL PRIMARY KEY,
    releve_id     BIGINT NOT NULL REFERENCES releves_bancaires(id) ON DELETE CASCADE,
    automatique   BOOLEAN NOT NULL DEFAULT FALSE,
    piece_regularisation_id BIGINT REFERENCES pieces_comptables(id),
    date_creation TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE lignes_releve (
    id             BIGSERIAL PRIMARY KEY,
    releve_id      BIGINT NOT NULL REFERENCES releves_bancaires(id) ON DELETE CASCADE,
    ordre          INTEGER NOT NULL,
    date_operation DATE NOT NULL,
    libelle        VARCHAR(500) NOT NULL,
    reference      VARCHAR(100),
    entree         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    sortie         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    pointage_id    BIGINT REFERENCES pointages_rapprochement(id) ON DELETE SET NULL
);
CREATE INDEX idx_lignes_releve_releve ON lignes_releve (releve_id);

ALTER TABLE grand_livre ADD COLUMN pointage_id BIGINT REFERENCES pointages_rapprochement(id) ON DELETE SET NULL;
CREATE INDEX idx_grand_livre_pointage ON grand_livre (pointage_id);

-- Module et droits : caissier et comptable importent et pointent, le DFIN
-- valide, DA et DG consultent.
INSERT INTO modules_config (module, actif, parent_module) VALUES ('RAPPROCHEMENT', TRUE, 'COMPTABILITE')
ON CONFLICT (module) DO UPDATE SET parent_module = EXCLUDED.parent_module;

INSERT INTO role_permissions (role, module, niveau) VALUES
    ('CAISSIER',  'RAPPROCHEMENT', 'ECRITURE'),
    ('COMPTABLE', 'RAPPROCHEMENT', 'ECRITURE'),
    ('DFIN',      'RAPPROCHEMENT', 'ECRITURE'),
    ('DA',        'RAPPROCHEMENT', 'LECTURE'),
    ('DG',        'RAPPROCHEMENT', 'LECTURE')
ON CONFLICT (role, module) DO NOTHING;
