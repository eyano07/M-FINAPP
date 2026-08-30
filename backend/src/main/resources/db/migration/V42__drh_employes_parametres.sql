-- Module DRH — socle de donnees : parametres de paie (ligne unique) et
-- employes. Aucune donnee n'est inseree ici : la reprise des employes et des
-- parametres depuis l'ancien outil (PayMBSC) se fait via un outil de
-- migration dedie, pas par cette migration Flyway (voir la tache "reprise
-- H2 -> PostgreSQL" du plan DRH).

-- ---------------------------------------------------------------------------
-- 1. Parametres de paie (singleton, id = 1)
-- ---------------------------------------------------------------------------
CREATE TABLE drh_parametres_paie (
    id                          BIGINT PRIMARY KEY,
    taux_logement               NUMERIC(6,4)  NOT NULL,
    taux_transport               NUMERIC(6,4)  NOT NULL,
    taux_cnss_ouvriere           NUMERIC(6,4)  NOT NULL,
    taux_cnss_patronale          NUMERIC(6,4)  NOT NULL,
    taux_onem                    NUMERIC(6,4)  NOT NULL,
    taux_inpp                    NUMERIC(6,4)  NOT NULL,
    reduction_ipr_par_enfant     NUMERIC(6,4)  NOT NULL,
    plafond_enfants_ipr          INTEGER       NOT NULL,
    plancher_ipr_fc              NUMERIC(15,2) NOT NULL,
    jours_ouvrables_standard     INTEGER       NOT NULL,
    directeur_drh                VARCHAR(150),
    fonction_directeur           VARCHAR(150),
    ville_signature              VARCHAR(80),
    CONSTRAINT chk_drh_parametres_paie_singleton CHECK (id = 1)
);

COMMENT ON TABLE drh_parametres_paie IS
    'Parametres de paie globaux (taux de cotisations, bareme IPR, signature des documents) — ligne unique, id=1.';

-- ---------------------------------------------------------------------------
-- 2. Employes
-- ---------------------------------------------------------------------------
CREATE TABLE drh_employes (
    id                   BIGSERIAL PRIMARY KEY,
    matricule            VARCHAR(30)   NOT NULL UNIQUE,
    nom_complet          VARCHAR(150)  NOT NULL,
    categorie            VARCHAR(30),
    affectation          VARCHAR(100),
    email                VARCHAR(150),
    telephone            VARCHAR(30),
    date_embauche        DATE,
    salaire_base_usd     NUMERIC(15,2) NOT NULL DEFAULT 0,
    situation_familiale  VARCHAR(20),
    nombre_enfants       INTEGER       NOT NULL DEFAULT 0,
    diplome              VARCHAR(100),
    anciennete_annees    INTEGER       NOT NULL DEFAULT 0,
    rendement_pct        NUMERIC(6,2)  NOT NULL DEFAULT 0,
    conforme             BOOLEAN       NOT NULL DEFAULT TRUE,
    superviseur          BOOLEAN       NOT NULL DEFAULT FALSE,
    expatrie             BOOLEAN       NOT NULL DEFAULT FALSE,
    actif                BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT chk_drh_employes_situation_familiale
        CHECK (situation_familiale IN ('CELIBATAIRE', 'MARIE', 'DIVORCE', 'VEUF'))
);

CREATE INDEX idx_drh_employes_nom_complet ON drh_employes (nom_complet);
CREATE INDEX idx_drh_employes_actif ON drh_employes (actif);

COMMENT ON TABLE drh_employes IS
    'Personnel MBSC (module DRH_PERSONNEL). salaire_base_usd est le salaire BRUT (R) — voir PayrollCalculationService.';
COMMENT ON COLUMN drh_employes.conforme IS
    'true = bulletin de paie complet ; false = simple recu de paiement (agent non declare/non standard).';
COMMENT ON COLUMN drh_employes.superviseur IS
    'Eligibilite aux rotations de sites operationnels (module DRH_MISSIONS).';
COMMENT ON COLUMN drh_employes.expatrie IS
    'Route les ecritures de paie vers les comptes "personnel non national" (6621/6642) au lieu de "national" (6611/6641).';
