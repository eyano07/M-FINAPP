-- Module MINE (19/11/2026), sous-module de LOGISTIQUE : minerais suivis camion par camion. Actif d'emblée seulement si
-- l'entreprise suit déjà des minerais (articles ou camions) ; sinon l'administrateur l'active depuis l'écran Modules.
INSERT INTO modules_config (module, actif, parent_module)
VALUES ('MINE',
        EXISTS (SELECT 1 FROM articles WHERE minerais) OR EXISTS (SELECT 1 FROM camions_minerai),
        'LOGISTIQUE')
ON CONFLICT (module) DO UPDATE SET parent_module = EXCLUDED.parent_module;

-- Mêmes droits que la logistique pour chaque rôle, sauf les responsables du restaurant, qui ne lisent
-- la logistique que pour leurs propres articles (grille d'origine : DroitsParDefaut).
INSERT INTO role_permissions (role, module, niveau)
SELECT role, 'MINE', niveau FROM role_permissions
WHERE module = 'LOGISTIQUE' AND role NOT IN ('RESP_CUISINE', 'RESP_BAR', 'RESP_RESTAURANT')
ON CONFLICT (role, module) DO NOTHING;
