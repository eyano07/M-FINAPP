-- Deux tables d'une meme salle ne peuvent plus porter le meme numero : la
-- commande, le badge paye/non paye et l'addition devenaient ambigus.
-- RestaurantService.enregistrerPlan le verifie deja pour un message clair ;
-- la contrainte le garantit en base.
--
-- DEFERRABLE INITIALLY DEFERRED : l'enregistrement d'un plan peut echanger
-- les numeros de deux tables, ou supprimer une table et en recreer une sous
-- le meme numero, dans une seule transaction. Verifiee ligne par ligne, la
-- contrainte refuserait ces etats intermediaires ; elle ne l'est qu'a la
-- validation de la transaction.
ALTER TABLE tables_restaurant
    ADD CONSTRAINT uq_table_restaurant_salle_numero UNIQUE (salle_id, numero)
    DEFERRABLE INITIALLY DEFERRED;

-- Cles etrangeres interrogees a chaque suppression d'article (un article
-- deja vendu, mouvemente, entre dans une recette ou une production ne se
-- supprime pas) et par les tableaux de bord.
CREATE INDEX idx_lignes_vente_article ON lignes_vente(article_id);
CREATE INDEX idx_lignes_mvt_stock_article ON lignes_mouvement_stock(article_id);
CREATE INDEX idx_lignes_recette_provision ON lignes_recette(provision_id);
CREATE INDEX idx_lignes_production_provision ON lignes_production(provision_id);

-- Operation proprietaire d'un mouvement de stock (vente, production, camion) :
-- recherchee a chaque annulation et pour chaque liste de mouvements.
CREATE INDEX idx_ventes_mouvement ON ventes(mouvement_id);
CREATE INDEX idx_productions_mouvement_sortie ON productions(mouvement_sortie_id);
CREATE INDEX idx_productions_mouvement_entree ON productions(mouvement_entree_id);
CREATE INDEX idx_camions_minerai_mouvement ON camions_minerai(mouvement_id);
CREATE INDEX idx_stock_gl_mouvement ON stock_grand_livre(mouvement_id);
