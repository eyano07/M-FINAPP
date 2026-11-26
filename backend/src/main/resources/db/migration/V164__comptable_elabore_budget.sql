-- Élaboration des budgets (26/11/2026) : le comptable ou le DFIN crée et soumet un budget, le DA l'approuve, le DFIN
-- le met en exécution. Le comptable passe de la lecture à l'écriture sur le module Budget (sans toucher à un réglage
-- que l'administrateur aurait déjà changé).
UPDATE role_permissions SET niveau = 'ECRITURE' WHERE role = 'COMPTABLE' AND module = 'BUDGET' AND niveau = 'LECTURE';
