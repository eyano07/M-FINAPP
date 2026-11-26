-- ---------------------------------------------------------------------------
-- V132 : mode de l'instance (serveur en ligne / serveur local) et appairage.
--
-- Un serveur local (machine du reseau de l'entreprise) peut devenir le serveur PRINCIPAL : on y travaille meme sans
-- Internet, et il recopie ses donnees vers le serveur en ligne des que la connexion revient. Le serveur en ligne devient
-- alors une COPIE consultable (mode MIROIR) ou seules les validations DFIN / DA des notes de frais sont possibles
-- (mises en attente puis appliquees au serveur local).
--
-- Une seule ligne (id = 1), propre a chaque serveur : jamais recopiee d'un serveur a l'autre.
--   EN_LIGNE     : fonctionnement habituel (serveur principal), par defaut.
--   LOCAL        : serveur local principal, synchronise avec le serveur en ligne (adresse_distante).
--   MIROIR       : serveur en ligne devenu copie du serveur local appaire.
--   LOCAL_ARRETE : ancien serveur local (mode local desactive ou appairage revoque) : lecture seule.
-- La cle d'appairage est generee sur le serveur en ligne et saisie sur le serveur local ; elle signe (HMAC) chaque
-- appel entre les deux serveurs et ne circule jamais. Elle est chiffree avec la cle propre a chaque serveur.
-- appairage_revoque (cote en ligne) : appairage auquel « Reprendre la main » a mis fin ; le serveur local qui l'utilise
-- encore recoit le code APPAIRAGE_REVOQUE et passe en lecture seule (jamais deux serveurs principaux).
-- Pas de cle etrangere vers users : la table ne doit dependre d'aucune donnee recopiee.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS parametres_instance (
    id                    BIGINT        PRIMARY KEY,
    mode                  VARCHAR(20)   NOT NULL DEFAULT 'EN_LIGNE',
    adresse_distante      VARCHAR(300),
    cle_chiffree          VARCHAR(1000),
    cle_fin               VARCHAR(8),
    appairage_id          VARCHAR(40),
    cle_creee_le          TIMESTAMPTZ,
    serveur_associe       VARCHAR(150),
    appaire_le            TIMESTAMPTZ,
    appairage_revoque     VARCHAR(40),
    dernier_contact       TIMESTAMPTZ,
    derniere_synchro      TIMESTAMPTZ,
    derniere_erreur       VARCHAR(1000),
    derniere_erreur_le    TIMESTAMPTZ,
    modifie_par           VARCHAR(150),
    date_maj              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_parametres_instance_mode CHECK (mode IN ('EN_LIGNE', 'LOCAL', 'MIROIR', 'LOCAL_ARRETE')),
    CONSTRAINT ck_parametres_instance_unique CHECK (id = 1)
);

INSERT INTO parametres_instance (id, mode) VALUES (1, 'EN_LIGNE') ON CONFLICT (id) DO NOTHING;
