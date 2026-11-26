-- ---------------------------------------------------------------------------
-- Emplacements (affectations) du patrimoine et photos des biens (20/11/2026).
--
-- La logistique crée les emplacements : bureaux, salles, magasins… et, pour l'hôtellerie, les chambres, unités de
-- motel et appartements, chacun lié à l'unité correspondante de l'application d'hôtellerie. Un bien d'inventaire peut
-- y être affecté (facultatif) ; l'hôtellerie lit alors les biens de chaque unité (inventaire type des logements).
-- Les unités de l'hôtellerie sont recopiées ici par l'hôtellerie elle-même (appel signé), pour être proposées au choix.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS hotellerie_unites (
    pms_id             BIGINT PRIMARY KEY,
    numero             VARCHAR(30)  NOT NULL,
    type_nom           VARCHAR(100),
    etablissement_nom  VARCHAR(150),
    etablissement_type VARCHAR(20),
    -- MEUBLE / NON_MEUBLE pour un appartement ; vide sinon.
    ameublement        VARCHAR(12),
    actif              BOOLEAN      NOT NULL DEFAULT TRUE,
    maj_le             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS emplacements (
    id            BIGSERIAL PRIMARY KEY,
    nom           VARCHAR(120) NOT NULL,
    type          VARCHAR(20)  NOT NULL DEFAULT 'BUREAU'
                  CHECK (type IN ('BUREAU', 'SALLE', 'MAGASIN', 'ATELIER', 'CHAMBRE', 'APPARTEMENT', 'UNITE_MOTEL',
                                  'PARTIE_COMMUNE', 'EXTERIEUR', 'AUTRE')),
    description   VARCHAR(255),
    -- Unité de l'hôtellerie (chambre, unité de motel, appartement) : une seule affectation par unité.
    pms_unite_id  BIGINT UNIQUE,
    actif         BOOLEAN      NOT NULL DEFAULT TRUE,
    cree_par      VARCHAR(255),
    cree_le       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    maj_le        TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS emplacements_nom_unique ON emplacements (lower(nom));

ALTER TABLE inventaire_biens ADD COLUMN IF NOT EXISTS emplacement_id BIGINT REFERENCES emplacements(id);
CREATE INDEX IF NOT EXISTS idx_inventaire_biens_emplacement ON inventaire_biens (emplacement_id);

-- Photos des biens : fichiers dans le stockage de M-FINAPP (patrimoine/photos), la base n'en garde que le chemin.
CREATE TABLE IF NOT EXISTS inventaire_photos (
    id            BIGSERIAL PRIMARY KEY,
    bien_id       BIGINT       NOT NULL REFERENCES inventaire_biens(id) ON DELETE CASCADE,
    chemin        VARCHAR(500) NOT NULL,
    nom_origine   VARCHAR(255),
    type_contenu  VARCHAR(100),
    cree_par      VARCHAR(255),
    cree_le       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_inventaire_photos_bien ON inventaire_photos (bien_id);

-- Reprise : chaque lieu relevé à l'inventaire (texte libre du fichier, « Salle de réunion », « Administration »…)
-- devient un emplacement, que la logistique renomme ou retype ensuite ; les biens y sont affectés.
INSERT INTO emplacements (nom, type, cree_par)
SELECT DISTINCT ON (lower(trim(localisation))) trim(localisation),
       CASE WHEN localisation ILIKE '%salle%' THEN 'SALLE'
            WHEN localisation ILIKE '%magasin%' OR localisation ILIKE '%d_p_t%' OR localisation ILIKE '%stock%' THEN 'MAGASIN'
            WHEN localisation ILIKE '%atelier%' THEN 'ATELIER'
            WHEN localisation ILIKE '%hall%' OR localisation ILIKE '%couloir%' OR localisation ILIKE '%toilette%'
              OR localisation ILIKE '%entr_e%' OR localisation ILIKE '%escalier%' THEN 'PARTIE_COMMUNE'
            WHEN trim(localisation) ILIKE 'cour%' OR localisation ILIKE '%parking%' OR localisation ILIKE '%jardin%'
              OR localisation ILIKE '%ext_rieur%' THEN 'EXTERIEUR'
            ELSE 'BUREAU' END,
       'reprise de l''inventaire'
FROM inventaire_biens
WHERE localisation IS NOT NULL AND trim(localisation) <> ''
ORDER BY lower(trim(localisation)), trim(localisation)
ON CONFLICT DO NOTHING;

UPDATE inventaire_biens b SET emplacement_id = e.id
FROM emplacements e
WHERE b.emplacement_id IS NULL AND b.localisation IS NOT NULL AND lower(trim(b.localisation)) = lower(e.nom);
