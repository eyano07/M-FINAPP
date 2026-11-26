-- Compte d'achat (601x) de l'article, au meme titre que son compte de stock
-- (311x) et son compte de charge/variation (6031) : la marchandise achetee
-- via une note de frais doit desormais imputer 601x au reglement puis 311x
-- en contrepartie de 6031 a l'entree en stock, exactement comme un achat
-- saisi a la caisse — voir NoteFraisService.creerLignes et
-- StockService.entreesDepuisNoteFraisInterne. Auparavant la note imputait
-- directement 311x au reglement, ce qui produisait un bilan juste mais un
-- compte de resultat faux (l'achat et sa variation de stock n'y apparaissaient
-- jamais).
ALTER TABLE articles ADD COLUMN compte_achat_id BIGINT REFERENCES comptes_ohada(id);
