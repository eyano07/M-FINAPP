-- ---------------------------------------------------------------------------
-- Inventaire du patrimoine (19/11/2026) : biens relevés sur une fiche d'inventaire, un par unité, chacun avec le code
-- collé dessus. Le relevé ne porte ni valeur ni date : le responsable logistique les saisit bien par bien ; un bien
-- d'au moins 100 USD entre alors en immobilisation (fiche d'amortissement SYSCOHADA, pièce d'acquisition en
-- contrepartie de 1013), un bien plus petit reste suivi comme petit matériel, sans écriture.
--
-- Le responsable logistique tient aussi les fiches d'amortissement : la logistique reçoit l'écriture sur le module
-- PATRIMOINE (même règle que V151 : seulement pour un rôle déjà configuré ; sinon DroitsParDefaut).
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS inventaire_biens (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(60)  NOT NULL UNIQUE,
    libelle             VARCHAR(200) NOT NULL,
    categorie           VARCHAR(30)  NOT NULL,
    duree_mois          INTEGER      NOT NULL CHECK (duree_mois > 0),
    localisation        VARCHAR(255),
    etat                VARCHAR(60),
    -- A_VALORISER : en attente de valeur et de date ; IMMOBILISE : fiche d'amortissement créée ; PETIT_MATERIEL : sous le seuil.
    statut              VARCHAR(20)  NOT NULL DEFAULT 'A_VALORISER'
                        CHECK (statut IN ('A_VALORISER', 'IMMOBILISE', 'PETIT_MATERIEL')),
    valeur_usd          NUMERIC(15, 2),
    date_acquisition    DATE,
    date_mise_service   DATE,
    immobilisation_id   BIGINT REFERENCES immobilisations(id),
    source              VARCHAR(120),
    ligne_source        INTEGER,
    cree_le             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    maj_le              TIMESTAMPTZ,
    maj_par             VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_inventaire_biens_statut ON inventaire_biens (statut);

-- Code d'inventaire collé sur le bien, repris sur sa fiche d'immobilisation.
ALTER TABLE immobilisations ADD COLUMN IF NOT EXISTS code_inventaire VARCHAR(60) UNIQUE;

INSERT INTO role_permissions (role, module, niveau)
SELECT DISTINCT 'LOGISTIQUE', 'PATRIMOINE', 'ECRITURE' FROM role_permissions WHERE role = 'LOGISTIQUE'
ON CONFLICT (role, module) DO UPDATE SET niveau = 'ECRITURE';
