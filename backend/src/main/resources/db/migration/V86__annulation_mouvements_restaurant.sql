-- Rend corrigeables les mouvements de stock du module Restaurant.
--
-- Depuis que la Logistique refuse de toucher aux plats, boissons et
-- provisions, et que la Comptabilite refuse d'extourner seule une piece de
-- stock, une reception, une sortie ou une perte saisie par erreur n'avait
-- plus aucune voie de correction. Le module Restaurant les annule
-- desormais lui-meme ; il lui faut pour cela retrouver tout ce que
-- l'operation avait produit.

-- Piece d'achat (D 602x / C fournisseur) d'une reception directe de
-- provision : jusqu'ici rattachee a rien, elle ne pouvait ni etre extournee
-- avec la reception, ni etre protegee d'une extourne isolee depuis la
-- Comptabilite — qui aurait fait disparaitre l'achat du compte de resultat
-- en laissant la marchandise en stock.
ALTER TABLE mouvements_stock
    ADD COLUMN piece_achat_id BIGINT REFERENCES pieces_comptables(id);

-- Piece qui constate l'ecart de valorisation apparu a l'annulation d'un
-- mouvement (stock vide auquel il reste une valeur, ou valeur devenue
-- negative) : sans elle, cet ecart etait efface du niveau de stock mais
-- restait au grand livre.
ALTER TABLE mouvements_stock
    ADD COLUMN piece_ecart_annulation_id BIGINT REFERENCES pieces_comptables(id);

CREATE INDEX idx_mouvements_stock_piece ON mouvements_stock(piece_id);
CREATE INDEX idx_mouvements_stock_piece_achat ON mouvements_stock(piece_achat_id);
CREATE INDEX idx_mouvements_stock_piece_ecart ON mouvements_stock(piece_ecart_annulation_id);

-- Perte de boisson (casse d'une pleine, peremption, cadeau) : la sortie de
-- stock qu'elle a declenchee, pour pouvoir la retablir a l'annulation.
ALTER TABLE restaurant_mouvements_emballage
    ADD COLUMN mouvement_stock_id BIGINT REFERENCES mouvements_stock(id);

-- Le journal des vides reste en ajout seul (la somme signee des mouvements
-- egale le compteur) : une annulation ajoute un mouvement inverse qui cite
-- le mouvement annule, et marque ce dernier.
ALTER TABLE restaurant_mouvements_emballage
    ADD COLUMN annule BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE restaurant_mouvements_emballage
    ADD COLUMN annulation_de_id BIGINT REFERENCES restaurant_mouvements_emballage(id);
