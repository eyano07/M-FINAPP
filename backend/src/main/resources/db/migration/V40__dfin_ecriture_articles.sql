-- Le DFIN peut desormais modifier la fiche article (unite, prix de vente,
-- compte produit) depuis la page Articles : StockService.creerArticle /
-- modifierArticle lui sont ouverts (voir @PreAuthorize). Le filtre de module
-- (ModuleAccessFilter) exige ECRITURE sur LOGISTIQUE pour toute requete non-GET
-- sous /logistique, y compris pour un role qui n'a droit qu'a UNE seule
-- action d'ecriture du module (les autres — entrepots, mouvements — restent
-- fermees au DFIN par les @PreAuthorize de service, inchanges).
UPDATE role_permissions
SET niveau = 'ECRITURE'
WHERE role = 'DFIN' AND module = 'LOGISTIQUE';
