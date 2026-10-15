-- ---------------------------------------------------------------------------
-- Deux roles pour le restaurant : RESP_CUISINE (plats, fiches techniques, production,
-- provisions) et RESP_BAR (boissons, bouteilles vides, achats de boissons). RESP_RESTAURANT
-- reste le responsable global, qui voit les deux domaines.
--
-- Seules les lignes de la table roles sont creees ici. AUCUNE ligne role_permissions : les
-- droits d'origine de chaque role (module RESTAURANT en ecriture, module LOGISTIQUE en lecture)
-- sont poses a la creation du premier compte qui le porte, par
-- PermissionService.appliquerDroitsParDefaut (voir DroitsParDefaut). Une ligne fixe inseree ici
-- ferait passer le role pour « deja configure » sur une base dont la grille est vide (regle V108).
--
-- Retour arriere : l'ancienne version de l'application ne connait pas ces deux noms de role et
-- ne saurait pas lire un compte qui en porte un ; revenir en arriere suppose de restaurer aussi
-- la sauvegarde de la base prise avant cette migration.
-- ---------------------------------------------------------------------------
INSERT INTO roles (nom) VALUES ('RESP_CUISINE') ON CONFLICT (nom) DO NOTHING;
INSERT INTO roles (nom) VALUES ('RESP_BAR') ON CONFLICT (nom) DO NOTHING;
