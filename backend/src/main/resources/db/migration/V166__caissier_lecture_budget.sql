-- Le caissier consulte les budgets en lecture seule (26/11/2026) : liste, page et suivi, sans aucune action. Sans droit
-- sur le module Budget jusqu'ici ; un réglage déjà posé par l'administrateur n'est pas modifié.
INSERT INTO role_permissions (role, module, niveau)
SELECT 'CAISSIER', 'BUDGET', 'LECTURE'
WHERE NOT EXISTS (SELECT 1 FROM role_permissions WHERE role = 'CAISSIER' AND module = 'BUDGET');
