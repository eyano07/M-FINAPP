-- Nouveau role COMPTABLE : cree (et soumet) les notes de frais aux cotes du
-- caissier, a la place des directeurs (DA/DFIN/DG), de DIRECTEUR et d'ADMIN
-- (voir NoteFraisService.creer/modifier/soumettre). En dehors des notes de
-- frais, le comptable est en LECTURE SEULE sur les modules metier utiles a
-- son activite : tresorerie (caisse), comptabilite generale, ventes,
-- logistique et patrimoine. Aucun droit sur la banque, le mobile money ou le
-- transport, non demandes.
INSERT INTO roles (nom) VALUES ('COMPTABLE') ON CONFLICT (nom) DO NOTHING;

INSERT INTO role_permissions (role, module, niveau) VALUES
    ('COMPTABLE', 'CAISSE', 'LECTURE'),
    ('COMPTABLE', 'COMPTABILITE', 'LECTURE'),
    ('COMPTABLE', 'VENTES', 'LECTURE'),
    ('COMPTABLE', 'LOGISTIQUE', 'LECTURE'),
    ('COMPTABLE', 'PATRIMOINE', 'LECTURE')
ON CONFLICT (role, module) DO UPDATE SET niveau = EXCLUDED.niveau;
