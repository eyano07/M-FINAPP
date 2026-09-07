ALTER TABLE drh_employes ADD COLUMN date_naissance DATE NULL;

ALTER TABLE drh_parametres_paie ADD COLUMN calcul_enfants_actif BOOLEAN NOT NULL DEFAULT true;
