-- Module FACTURATION_NORMALISEE, sous-module de VENTES : l'administrateur l'active ou le coupe depuis l'ecran Modules
-- (interrupteur unique). Reprend l'etat deja enregistre dans les parametres e-MCF, desactive sinon.
INSERT INTO modules_config (module, actif, parent_module)
VALUES ('FACTURATION_NORMALISEE', COALESCE((SELECT actif FROM parametres_emcf WHERE id = 1), FALSE), 'VENTES')
ON CONFLICT (module) DO UPDATE SET parent_module = EXCLUDED.parent_module;
