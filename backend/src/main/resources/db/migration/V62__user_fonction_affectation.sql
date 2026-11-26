-- Fonction et affectation de l'utilisateur (compte systeme), saisies par
-- l'ADMIN a la creation/modification du compte : distinctes du poste/
-- affectation de la fiche DRH (Employe), qui concerne l'employe paye et non
-- le compte applicatif. Utilisees pour personnaliser le papier a en-tete
-- individuel (voir PapierEnteteService).
ALTER TABLE users ADD COLUMN fonction VARCHAR(100);
ALTER TABLE users ADD COLUMN affectation VARCHAR(100);
