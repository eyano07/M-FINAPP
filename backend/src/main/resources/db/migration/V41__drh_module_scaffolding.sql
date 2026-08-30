-- Module DRH (paie et ressources humaines) — socle module/sous-module.
--
-- DRH est le premier module HIERARCHIQUE de l'application : un conteneur
-- (DRH) que l'administrateur peut activer/desactiver globalement, et quatre
-- sous-modules (DRH_PERSONNEL, DRH_PRESENCES, DRH_PAIE, DRH_MISSIONS) que
-- l'administrateur active/desactive independamment, mais qui restent coupes
-- pour tout le monde des que le conteneur lui-meme est desactive (voir la
-- colonne parent_module ci-dessous et PermissionService.niveauEffectif).
--
-- Les 4 sous-modules sont crees INACTIFS par defaut : le module DRH n'est
-- utilisable qu'une fois la reprise des employes/parametres de paie verifiee
-- (V42+), pas des l'application de cette migration.

-- ---------------------------------------------------------------------------
-- 1. Hierarchie des modules
-- ---------------------------------------------------------------------------
ALTER TABLE modules_config ADD COLUMN IF NOT EXISTS parent_module VARCHAR(30);

COMMENT ON COLUMN modules_config.parent_module IS
    'Module conteneur dont l''etat actif/inactif prevaut en cascade sur celui-ci (NULL pour un module autonome).';

-- ---------------------------------------------------------------------------
-- 2. Role
-- ---------------------------------------------------------------------------
INSERT INTO roles (nom) VALUES ('RESP_DRH') ON CONFLICT (nom) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. Modules (conteneur + 4 sous-modules, inactifs par defaut)
-- ---------------------------------------------------------------------------
INSERT INTO modules_config (module, actif, parent_module) VALUES
    ('DRH',           FALSE, NULL),
    ('DRH_PERSONNEL', FALSE, 'DRH'),
    ('DRH_PRESENCES', FALSE, 'DRH'),
    ('DRH_PAIE',      FALSE, 'DRH'),
    ('DRH_MISSIONS',  FALSE, 'DRH')
ON CONFLICT (module) DO UPDATE SET parent_module = EXCLUDED.parent_module;

-- ---------------------------------------------------------------------------
-- 4. Grille de permissions par defaut
-- ---------------------------------------------------------------------------
-- RESP_DRH ecrit dans les 4 sous-modules. DFIN consulte DRH_PAIE : il doit
-- voir le contexte (bulletin, employe, montants) avant de comptabiliser la
-- piece BROUILLON que la cloture de paie depose dans Pieces comptables.
INSERT INTO role_permissions (role, module, niveau) VALUES
    ('RESP_DRH', 'DRH_PERSONNEL', 'ECRITURE'),
    ('RESP_DRH', 'DRH_PRESENCES', 'ECRITURE'),
    ('RESP_DRH', 'DRH_PAIE',      'ECRITURE'),
    ('RESP_DRH', 'DRH_MISSIONS',  'ECRITURE'),
    ('DFIN',     'DRH_PAIE',      'LECTURE')
ON CONFLICT (role, module) DO UPDATE SET niveau = EXCLUDED.niveau;

COMMENT ON TABLE modules_config IS
    'Etat actif/inactif de chaque module metier, avec hierarchie optionnelle (parent_module) pour les modules a sous-modules comme DRH.';
