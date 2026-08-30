-- Module DRH — sites operationnels, rotations de superviseurs et ordres de
-- mission (module DRH_MISSIONS).

CREATE TABLE drh_sites_operationnels (
    id           BIGSERIAL PRIMARY KEY,
    nom          VARCHAR(120) NOT NULL,
    localisation VARCHAR(200),
    actif        BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE drh_rotations_superviseurs (
    id               BIGSERIAL PRIMARY KEY,
    employe_id       BIGINT  NOT NULL REFERENCES drh_employes(id),
    site_id          BIGINT  NOT NULL REFERENCES drh_sites_operationnels(id),
    annee            INTEGER NOT NULL,
    mois             INTEGER NOT NULL,
    numero_cycle     INTEGER NOT NULL DEFAULT 1,
    prestation_debut DATE    NOT NULL,
    prestation_fin   DATE    NOT NULL,
    repos_debut      DATE    NOT NULL,
    repos_fin        DATE    NOT NULL,
    notes            VARCHAR(500),
    CONSTRAINT uq_drh_rotation_employe_site_periode UNIQUE (employe_id, site_id, annee, mois, numero_cycle),
    CONSTRAINT chk_drh_rotation_mois CHECK (mois BETWEEN 1 AND 12)
);

CREATE INDEX idx_drh_rotations_site_periode ON drh_rotations_superviseurs (site_id, prestation_debut, prestation_fin);

-- Numerotation mensuelle des ordres de mission : upsert atomique (INSERT ...
-- ON CONFLICT ... DO UPDATE ... RETURNING), jamais un simple COUNT(*)+1 qui
-- reutiliserait un numero apres suppression (bug connu de l'ancien outil).
CREATE TABLE drh_sequence_ordre_mission (
    annee          INTEGER NOT NULL,
    mois           INTEGER NOT NULL,
    dernier_numero INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (annee, mois)
);

CREATE TABLE drh_ordres_mission (
    id              BIGSERIAL PRIMARY KEY,
    numero          VARCHAR(60)   NOT NULL UNIQUE,
    lieu_mission    VARCHAR(200)  NOT NULL,
    distance_ville  NUMERIC(10,2),
    province        VARCHAR(80),
    but_mission     VARCHAR(1000) NOT NULL,
    duree_mission   VARCHAR(100),
    date_depart     DATE          NOT NULL,
    date_retour     DATE          NOT NULL,
    moyen_transport VARCHAR(150),
    frais_mission   VARCHAR(200),
    created_at      TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE TABLE drh_agents_ordre_mission (
    id               BIGSERIAL PRIMARY KEY,
    ordre_mission_id BIGINT      NOT NULL REFERENCES drh_ordres_mission(id) ON DELETE CASCADE,
    employe_id       BIGINT      NOT NULL REFERENCES drh_employes(id),
    fonction_mission VARCHAR(150),
    civilite         VARCHAR(10) NOT NULL DEFAULT 'Monsieur'
);

CREATE INDEX idx_drh_agents_ordre_mission_ordre ON drh_agents_ordre_mission (ordre_mission_id);

COMMENT ON TABLE drh_rotations_superviseurs IS
    'Rotation 14 jours de prestation (dimanches inclus) + 7 jours de repos par mois. Aucun chevauchement de prestation autorise sur un meme site.';
COMMENT ON TABLE drh_ordres_mission IS
    'Ordre de mission individuel (1 agent) ou collectif (plusieurs) — voir OrdreMission.isCollective().';
COMMENT ON COLUMN drh_ordres_mission.frais_mission IS
    'Texte libre (pas un montant) : porte tel quel depuis l''ancien outil PayMBSC.';
