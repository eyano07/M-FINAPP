-- ---------------------------------------------------------------------------
-- Module HOTELLERIE (19/11/2026) : M-FINAPP Hôtellerie, application à part (PMS : hôtel, motel, appartements) qui
-- s'appuie sur M-FINAPP pour la comptabilité, la caisse, le stock, les notes de frais, l'administration et la RH.
--
-- 1. Module, coupé par défaut : l'administrateur l'active puis saisit l'adresse et le port du PMS.
-- 2. Rôles de l'hébergement. Comme V124, seules les lignes de la table roles : les droits d'origine sont posés à la
--    création du premier compte qui porte le rôle (DroitsParDefaut).
-- 3. Lecture du module pour la direction et la comptabilité déjà configurées (même règle que V151).
-- 4. Analytique : activité 986 Hébergement.
-- 5. Ventes : origine (CAISSE, HOTELLERIE) ; articles : drapeau hébergement.
-- 6. Connexion au PMS (adresse, port, clé d'appairage chiffrée), codes de connexion unique, opérations idempotentes.
--
-- Retour arrière : l'ancienne version ne connaît pas ces rôles ; restaurer la sauvegarde prise avant la migration.
-- ---------------------------------------------------------------------------

INSERT INTO modules_config (module, actif, parent_module) VALUES ('HOTELLERIE', FALSE, NULL)
ON CONFLICT (module) DO NOTHING;

INSERT INTO roles (nom) VALUES ('RECEPTIONNISTE') ON CONFLICT (nom) DO NOTHING;
INSERT INTO roles (nom) VALUES ('GOUVERNANTE') ON CONFLICT (nom) DO NOTHING;
INSERT INTO roles (nom) VALUES ('TECHNICIEN') ON CONFLICT (nom) DO NOTHING;
INSERT INTO roles (nom) VALUES ('DIR_HEBERGEMENT') ON CONFLICT (nom) DO NOTHING;

INSERT INTO role_permissions (role, module, niveau)
SELECT DISTINCT role, 'HOTELLERIE', 'LECTURE' FROM role_permissions WHERE role IN ('DG', 'DFIN', 'DA', 'COMPTABLE')
ON CONFLICT (role, module) DO NOTHING;

INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel, contenu) VALUES
    ('986', 'Résultat analytique — Hébergement', NULL, 9, FALSE, TRUE, FALSE,
     'Résultat analytique de l''hébergement (hôtel, motel, appartements) : chiffre d''affaires HT des séjours (9861) moins leur coût (9862). Le solde créditeur est la marge.'),
    ('9861', 'Hébergement — chiffre d''affaires HT', NULL, 9, TRUE, TRUE, FALSE,
     'Chiffre d''affaires HT des nuitées, heures, semaines et mois de location, reflété automatiquement.'),
    ('9862', 'Hébergement — coût des ventes', NULL, 9, TRUE, TRUE, FALSE,
     'Coût des séjours vendus : une prestation n''a pas de stock, ce compte ne reçoit que des écritures manuelles.')
ON CONFLICT (numero) DO NOTHING;

UPDATE comptes_ohada c
SET parent_id = (
    SELECT p.id FROM comptes_ohada p
    WHERE p.numero <> c.numero
      AND c.numero LIKE p.numero || '%'
    ORDER BY length(p.numero) DESC
    LIMIT 1
)
WHERE c.numero IN ('986', '9861', '9862');

ALTER TABLE ventes ADD COLUMN IF NOT EXISTS origine VARCHAR(20) NOT NULL DEFAULT 'CAISSE';
ALTER TABLE articles ADD COLUMN IF NOT EXISTS hebergement BOOLEAN NOT NULL DEFAULT FALSE;

-- Connexion au PMS : une seule ligne (id = 1).
CREATE TABLE IF NOT EXISTS integration_pms (
    id              BIGINT PRIMARY KEY,
    adresse         VARCHAR(255),
    port            INTEGER,
    cle_chiffree    TEXT,
    cle_creee_le    TIMESTAMPTZ,
    dernier_contact TIMESTAMPTZ,
    maj_le          TIMESTAMPTZ,
    maj_par         VARCHAR(255)
);

-- Codes de connexion unique (SSO) : empreinte seulement, valables une minute, à usage unique.
CREATE TABLE IF NOT EXISTS pms_codes_sso (
    id              BIGSERIAL PRIMARY KEY,
    empreinte       VARCHAR(64) NOT NULL UNIQUE,
    utilisateur_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expire_le       TIMESTAMPTZ NOT NULL,
    utilise_le      TIMESTAMPTZ
);

-- Opérations reçues du PMS : la même clé d'idempotence ne produit jamais deux écritures (file rejouée).
CREATE TABLE IF NOT EXISTS pms_operations (
    id              BIGSERIAL PRIMARY KEY,
    cle             VARCHAR(80) NOT NULL UNIQUE,
    type_operation  VARCHAR(30) NOT NULL,
    resultat        TEXT NOT NULL,
    utilisateur_id  BIGINT REFERENCES users(id),
    cree_le         TIMESTAMPTZ NOT NULL DEFAULT now()
);
