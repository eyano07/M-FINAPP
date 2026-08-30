-- Parametres de l'entreprise (une seule ligne, identite visuelle de l'app).
CREATE TABLE parametres_entreprise (
    id                    BIGSERIAL PRIMARY KEY,
    nom                   VARCHAR(100) NOT NULL DEFAULT 'MBSC Finapp',
    nom_complet           VARCHAR(255),
    slogan                VARCHAR(255),
    logo_chemin_stockage  VARCHAR(500),
    logo_type_mime        VARCHAR(100),
    date_maj              TIMESTAMP NOT NULL DEFAULT now()
);
INSERT INTO parametres_entreprise (nom) VALUES ('MBSC Finapp');

-- Modules metier activables/desactivables globalement par l'admin.
CREATE TABLE modules_config (
    module VARCHAR(30) PRIMARY KEY,
    actif  BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO modules_config (module, actif) VALUES
    ('CAISSE', TRUE), ('BANQUE', TRUE), ('MOBILE_MONEY', TRUE),
    ('COMPTABILITE', TRUE), ('VENTES', TRUE), ('LOGISTIQUE', TRUE), ('TRANSPORT', TRUE);

-- Permissions par role et par module (AUCUN si aucune ligne : refus par defaut).
-- ADMIN n'apparait pas ici : acces complet garanti par le code, non editable,
-- pour eviter qu'un administrateur ne se verrouille lui-meme hors de l'appli
-- en modifiant la grille.
CREATE TABLE role_permissions (
    id     BIGSERIAL PRIMARY KEY,
    role   VARCHAR(30) NOT NULL,
    module VARCHAR(30) NOT NULL,
    niveau VARCHAR(20) NOT NULL DEFAULT 'AUCUN',
    UNIQUE (role, module)
);

-- Seed reproduisant au plus pres les listes de roles jusqu'ici figees dans
-- NavigationDrawer.vue / definePageMeta : les roles qui operaient le module
-- (Caissier en caisse, Logistique en stock...) recoivent ECRITURE, ceux qui
-- ne faisaient que consulter des rapports lies (DFIN/DA/DG) recoivent
-- LECTURE, afin qu'aucun utilisateur ne perde une capacite qu'il avait avant
-- cette migration.
INSERT INTO role_permissions (role, module, niveau) VALUES
    ('CAISSIER', 'CAISSE', 'ECRITURE'), ('DFIN', 'CAISSE', 'LECTURE'), ('DA', 'CAISSE', 'LECTURE'), ('DG', 'CAISSE', 'LECTURE'),
    ('CAISSIER', 'BANQUE', 'ECRITURE'), ('DFIN', 'BANQUE', 'LECTURE'), ('DA', 'BANQUE', 'LECTURE'), ('DG', 'BANQUE', 'LECTURE'),
    ('CAISSIER', 'MOBILE_MONEY', 'ECRITURE'), ('DFIN', 'MOBILE_MONEY', 'LECTURE'), ('DA', 'MOBILE_MONEY', 'LECTURE'), ('DG', 'MOBILE_MONEY', 'LECTURE'),
    ('DFIN', 'COMPTABILITE', 'ECRITURE'), ('DA', 'COMPTABILITE', 'ECRITURE'), ('DG', 'COMPTABILITE', 'ECRITURE'), ('CAISSIER', 'COMPTABILITE', 'LECTURE'), ('LOGISTIQUE', 'COMPTABILITE', 'LECTURE'),
    ('CAISSIER', 'VENTES', 'ECRITURE'), ('DFIN', 'VENTES', 'LECTURE'), ('DA', 'VENTES', 'LECTURE'), ('DG', 'VENTES', 'LECTURE'),
    ('LOGISTIQUE', 'LOGISTIQUE', 'ECRITURE'), ('DFIN', 'LOGISTIQUE', 'LECTURE'), ('DA', 'LOGISTIQUE', 'LECTURE'), ('DG', 'LOGISTIQUE', 'LECTURE'),
    ('LOGISTIQUE', 'TRANSPORT', 'ECRITURE'), ('DFIN', 'TRANSPORT', 'LECTURE'), ('DA', 'TRANSPORT', 'LECTURE'), ('DG', 'TRANSPORT', 'LECTURE');
