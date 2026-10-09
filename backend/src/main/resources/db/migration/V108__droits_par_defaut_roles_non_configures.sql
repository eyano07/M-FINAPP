-- ---------------------------------------------------------------------------
-- Reparation des droits par defaut (voir PermissionService.appliquerDroitsParDefaut).
--
-- Les droits d'origine d'un role ne sont poses que si le role n'a AUCUNE ligne dans
-- role_permissions. V105 (et V104 sur une grille non vide) ajoutent des lignes
-- BUDGET / RAPPROCHEMENT : sur une base dont la grille est vide (base neuve, purge de mise
-- en production), ces seules lignes faisaient passer DFIN, DA, DG, COMPTABLE et CAISSIER
-- pour des roles « deja configures », qui ne recevaient plus ni budget, ni comptabilite, ni
-- caisse a la creation de leurs utilisateurs (403 partout, sauf le rapprochement).
--
-- On retire donc ces lignes quand elles sont les SEULES du role : le role redevient non
-- configure et recoit sa grille complete (qui inclut BUDGET et RAPPROCHEMENT, voir
-- DroitsParDefaut) au premier utilisateur cree. Un role qui a d'autres lignes n'est pas touche.
--
-- Regle pour les migrations a venir : n'inserer des droits qu'a partir de lignes existantes
-- (INSERT ... SELECT ... FROM role_permissions), jamais des lignes fixes.
-- ---------------------------------------------------------------------------
DELETE FROM role_permissions p
WHERE p.module IN ('BUDGET', 'RAPPROCHEMENT')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions q
      WHERE q.role = p.role AND q.module NOT IN ('BUDGET', 'RAPPROCHEMENT'));
