-- ---------------------------------------------------------------------------
-- V133 : synchronisation du serveur local vers le serveur en ligne.
--
-- journal_replication : chaque insertion, modification ou suppression faite sur le serveur LOCAL (mode LOCAL de
--   parametres_instance) dans une table recopiee y est notee par un declencheur (ligne complete en JSON). Le serveur
--   local envoie ces changements par lots au serveur en ligne, qui les applique tels quels (memes cles primaires, memes
--   references), puis les efface. Hors du mode LOCAL, le declencheur ne note rien.
--   Les declencheurs eux-memes sont poses (et les cles etrangeres rendues differables) au demarrage par
--   ReplicationSchemaVerifier, pour toutes les tables, y compris celles des migrations futures.
-- actions_distantes (serveur en ligne) : actions faites sur la copie en ligne (validations DFIN / DA, creation et
--   soumission de notes, activation d'un compte), en attente d'etre appliquees par le serveur local.
-- actions_distantes_appliquees (serveur local) : actions deja appliquees, pour ne jamais en appliquer une deux fois.
-- audit_a_envoyer (serveur local) : lignes du journal d'audit a recopier sur le serveur en ligne (copie hors site).
-- Ces quatre tables, comme parametres_instance, sont propres a chaque serveur : jamais recopiees.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS journal_replication (
    id          BIGSERIAL    PRIMARY KEY,
    nom_table   VARCHAR(80)  NOT NULL,
    operation   CHAR(1)      NOT NULL,
    ligne       JSONB        NOT NULL,
    cree_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_journal_replication_operation CHECK (operation IN ('I', 'U', 'D'))
);

CREATE OR REPLACE FUNCTION replication_capturer() RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM parametres_instance WHERE id = 1 AND mode = 'LOCAL') THEN
        RETURN NULL;
    END IF;
    IF TG_OP = 'DELETE' THEN
        INSERT INTO journal_replication (nom_table, operation, ligne) VALUES (TG_TABLE_NAME, 'D', to_jsonb(OLD));
    ELSIF TG_OP = 'UPDATE' THEN
        INSERT INTO journal_replication (nom_table, operation, ligne) VALUES (TG_TABLE_NAME, 'U', to_jsonb(NEW));
    ELSE
        INSERT INTO journal_replication (nom_table, operation, ligne) VALUES (TG_TABLE_NAME, 'I', to_jsonb(NEW));
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE IF NOT EXISTS actions_distantes (
    uuid                 UUID          PRIMARY KEY,
    type                 VARCHAR(30)   NOT NULL,
    note_id              BIGINT,
    cible_id             BIGINT,
    contenu              JSONB,
    fichiers             JSONB,
    auteur_id            BIGINT        NOT NULL,
    auteur_identifiant   VARCHAR(150)  NOT NULL,
    statut_note_vu       VARCHAR(40),
    date_maj_note_vue    TIMESTAMPTZ,
    resume               VARCHAR(300)  NOT NULL,
    statut               VARCHAR(20)   NOT NULL DEFAULT 'EN_ATTENTE',
    message              VARCHAR(1000),
    resultat_note_id     BIGINT,
    cree_le              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    traite_le            TIMESTAMPTZ,
    CONSTRAINT ck_actions_distantes_statut CHECK (statut IN ('EN_ATTENTE', 'APPLIQUEE', 'REFUSEE'))
);
CREATE INDEX IF NOT EXISTS idx_actions_distantes_statut ON actions_distantes (statut, cree_le);
CREATE INDEX IF NOT EXISTS idx_actions_distantes_note ON actions_distantes (note_id);

CREATE TABLE IF NOT EXISTS actions_distantes_appliquees (
    uuid               UUID          PRIMARY KEY,
    type               VARCHAR(30)   NOT NULL,
    statut             VARCHAR(20)   NOT NULL,
    message            VARCHAR(1000),
    resultat_note_id   BIGINT,
    applique_le        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS audit_a_envoyer (
    id        BIGSERIAL    PRIMARY KEY,
    ligne     TEXT         NOT NULL,
    cree_le   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
