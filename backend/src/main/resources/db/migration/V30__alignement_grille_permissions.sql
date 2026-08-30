-- Alignement de la grille de permissions sur les droits reellement appliques
-- par les @PreAuthorize des services.
--
-- Constat de l'audit : la grille (role_permissions, editable par l'admin et
-- appliquee par ModuleAccessFilter) et les listes de roles figees dans les
-- @PreAuthorize sont deux systemes independants. Sur six des sept modules ils
-- concordent ; seul COMPTABILITE promettait des acces que l'API refuse, ce qui
-- affichait des menus et des boutons aboutissant systematiquement a un 403.
--
-- Droits reels dans ComptabiliteService / BudgetService / PeriodeComptableService :
--   lecture  (pieces, grand livre, balance, bilan, resultat, journal, budgets)
--            -> DFIN, DA, DG, ADMIN
--   ecriture (creer/modifier/comptabiliser/annuler une piece, creer un budget)
--            -> DFIN, ADMIN
--   approbation d'un budget -> DA, ADMIN
--   cloture de periode      -> ADMIN uniquement
--
-- On corrige donc trois lignes :

-- 1. DG : aucune action d'ecriture ne lui est ouverte en comptabilite.
--    Il conserve la consultation complete des etats financiers.
UPDATE role_permissions
SET niveau = 'LECTURE'
WHERE role = 'DG' AND module = 'COMPTABILITE';

-- 2 et 3. CAISSIER et LOGISTIQUE : aucune methode de lecture comptable ne les
--    autorise, la grille leur affichait donc six entrees de menu dont chaque
--    page renvoyait une erreur. Le plan comptable reste accessible : il est
--    servi par /comptes, hors du prefixe /comptabilite et donc hors module.
DELETE FROM role_permissions
WHERE role IN ('CAISSIER', 'LOGISTIQUE') AND module = 'COMPTABILITE';

-- DA conserve ECRITURE : l'approbation d'un budget est une action d'ecriture
-- du module qui lui est bien reservee. Les deux ecrans du module reserves a
-- d'autres roles (creation de piece -> DFIN, cloture -> ADMIN) sont desormais
-- gardes par un controle de role cote page, en complement du niveau de module.
