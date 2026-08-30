-- Lot 3 : module PATRIMOINE — immobilisations et consommables.
--
-- Deux besoins distincts, traites differemment :
--
--  * Les CONSOMMABLES sont des biens stockes que l'on consomme au lieu de les
--    vendre. Le moteur de stock existant (entrepots, mouvements, cout moyen
--    pondere, grand livre stock) fait deja exactement cela : une SORTIE debite
--    le compte de charge et credite le compte de stock. On ajoute donc une
--    simple valeur a TypeArticle plutot que de dupliquer toute cette
--    mecanique.
--
--  * Les IMMOBILISATIONS n'existaient nulle part : ni table, ni entite, ni
--    amortissement. Seuls les comptes de classe 2 etaient presents au plan
--    comptable, alimentes a la main. C'est l'objet des tables ci-dessous.

-- ---------------------------------------------------------------------------
-- 1. Role et module
-- ---------------------------------------------------------------------------
INSERT INTO roles (nom) VALUES ('GEST_PATRIMOINE') ON CONFLICT (nom) DO NOTHING;

INSERT INTO modules_config (module, actif) VALUES ('PATRIMOINE', TRUE)
ON CONFLICT (module) DO NOTHING;

-- Le gestionnaire du patrimoine ecrit dans son module ; les roles financiers
-- consultent (le bilan et les dotations les concernent directement).
INSERT INTO role_permissions (role, module, niveau) VALUES
    ('GEST_PATRIMOINE', 'PATRIMOINE', 'ECRITURE'),
    ('DFIN', 'PATRIMOINE', 'LECTURE'),
    ('DA',   'PATRIMOINE', 'LECTURE'),
    ('DG',   'PATRIMOINE', 'LECTURE')
ON CONFLICT (role, module) DO UPDATE SET niveau = EXCLUDED.niveau;

-- Le gestionnaire du patrimoine a besoin du referentiel article/entrepot pour
-- gerer les consommables, sans pouvoir toucher aux mouvements de stock.
INSERT INTO role_permissions (role, module, niveau) VALUES
    ('GEST_PATRIMOINE', 'LOGISTIQUE', 'LECTURE')
ON CONFLICT (role, module) DO UPDATE SET niveau = EXCLUDED.niveau;

-- ---------------------------------------------------------------------------
-- 2. Consommables : nouvelle nature d'article
-- ---------------------------------------------------------------------------
-- articles.type est un VARCHAR : aucune migration de type n'est necessaire,
-- la valeur CONSOMMABLE est simplement acceptee par l'enum Java.
--
-- Les comptes de stock de fournitures avaient ete desactives par la refonte du
-- plan comptable (V23) alors qu'ils sont imputables et indispensables ici.
UPDATE comptes_ohada SET actif = TRUE
WHERE numero IN ('3212', '3213') AND imputable = TRUE;

-- ---------------------------------------------------------------------------
-- 3. Immobilisations
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS seq_immobilisation START WITH 1 INCREMENT BY 1;

CREATE TABLE immobilisations (
    id                        BIGSERIAL PRIMARY KEY,
    reference                 VARCHAR(30)  NOT NULL UNIQUE,
    libelle                   VARCHAR(200) NOT NULL,
    categorie                 VARCHAR(30)  NOT NULL,
    description               VARCHAR(1000),

    -- Triplet de comptes OHADA : immobilisation (classe 2), amortissement
    -- cumule (28x) et dotation (6812/6813). Resolus par numero, comme partout
    -- ailleurs dans l'application.
    compte_immobilisation_id  BIGINT NOT NULL REFERENCES comptes_ohada(id),
    compte_amortissement_id   BIGINT          REFERENCES comptes_ohada(id),
    compte_dotation_id        BIGINT          REFERENCES comptes_ohada(id),

    date_acquisition          DATE           NOT NULL,
    date_mise_service         DATE           NOT NULL,
    valeur_acquisition        NUMERIC(15,2)  NOT NULL CHECK (valeur_acquisition > 0),
    valeur_residuelle         NUMERIC(15,2)  NOT NULL DEFAULT 0 CHECK (valeur_residuelle >= 0),
    duree_mois                INTEGER        NOT NULL CHECK (duree_mois > 0),
    mode_amortissement        VARCHAR(20)    NOT NULL DEFAULT 'LINEAIRE',

    statut                    VARCHAR(20)    NOT NULL DEFAULT 'EN_SERVICE',
    localisation              VARCHAR(255),
    responsable_id            BIGINT REFERENCES users(id),
    fournisseur               VARCHAR(200),
    numero_serie              VARCHAR(100),

    piece_acquisition_id      BIGINT REFERENCES pieces_comptables(id),

    -- Sortie du patrimoine (cession ou mise au rebut)
    date_sortie               DATE,
    valeur_cession            NUMERIC(15,2),
    piece_sortie_id           BIGINT REFERENCES pieces_comptables(id),

    created_by_id             BIGINT REFERENCES users(id),
    created_at                TIMESTAMP NOT NULL DEFAULT NOW(),

    -- La valeur residuelle ne peut pas depasser la valeur d'acquisition :
    -- la base amortissable deviendrait negative.
    CONSTRAINT chk_immo_residuelle CHECK (valeur_residuelle < valeur_acquisition),
    -- Un bien ne peut pas etre mis en service avant d'avoir ete acquis.
    CONSTRAINT chk_immo_dates CHECK (date_mise_service >= date_acquisition)
);
CREATE INDEX idx_immobilisations_statut    ON immobilisations(statut);
CREATE INDEX idx_immobilisations_categorie ON immobilisations(categorie);

-- ---------------------------------------------------------------------------
-- 4. Plan d'amortissement
-- ---------------------------------------------------------------------------
-- Une ligne par periode (mois). Le plan complet est calcule et stocke des la
-- creation du bien : il devient consultable et previsionnel, et la
-- comptabilisation se contente ensuite de marquer les periodes echues.
CREATE TABLE lignes_amortissement (
    id                  BIGSERIAL PRIMARY KEY,
    immobilisation_id   BIGINT NOT NULL REFERENCES immobilisations(id) ON DELETE CASCADE,
    periode             DATE          NOT NULL,   -- premier jour du mois amorti
    base_amortissable   NUMERIC(15,2) NOT NULL,
    dotation            NUMERIC(15,2) NOT NULL,
    cumul               NUMERIC(15,2) NOT NULL,
    valeur_nette        NUMERIC(15,2) NOT NULL,

    -- Idempotence de la comptabilisation : une dotation ne doit jamais etre
    -- passee deux fois. L'unicite (bien, periode) l'interdit structurellement,
    -- le drapeau evite de repasser une periode deja ecrite.
    comptabilise        BOOLEAN       NOT NULL DEFAULT FALSE,
    piece_id            BIGINT REFERENCES pieces_comptables(id),
    date_comptabilisation TIMESTAMP,

    CONSTRAINT uq_ligne_amortissement UNIQUE (immobilisation_id, periode)
);
CREATE INDEX idx_lignes_amort_periode ON lignes_amortissement(periode);
CREATE INDEX idx_lignes_amort_a_passer ON lignes_amortissement(comptabilise, periode);

COMMENT ON TABLE immobilisations IS
    'Registre des biens immobilises : valeur, plan d''amortissement, affectation et sortie.';
COMMENT ON TABLE lignes_amortissement IS
    'Plan d''amortissement mensuel. Une ligne comptabilisee genere une piece 681x/28x.';
