-- Frais d'approche (transport, manutention) sur les achats de boissons et de
-- provisions reglés par note de frais.
--
-- La note porte les frais sur une ligne a part, imputee en « frais sur
-- achats » (6015 marchandises, 6025 matieres premieres). Au paiement, ils
-- sont repartis sur les articles achetes et incorpores a leur cout d'entree
-- en stock : par bouteille pour les boissons, au prorata du montant pour les
-- provisions.
ALTER TABLE lignes_note_frais
    ADD COLUMN frais_approche BOOLEAN NOT NULL DEFAULT FALSE;

-- Valeur du stock HORS frais d'approche, tenue en parallele de valeur_totale
-- (cout complet, frais compris). Valeur de gestion seulement, sans ecriture
-- comptable : elle donne le « prix d'achat moyen » affiche a cote du cout
-- moyen. Le stock existant n'a jamais porte de frais d'approche.
ALTER TABLE stock_niveaux
    ADD COLUMN valeur_achat NUMERIC(15,2) NOT NULL DEFAULT 0;
UPDATE stock_niveaux SET valeur_achat = valeur_totale;

-- Part hors frais de chaque ligne de mouvement : le prix paye pour une
-- entree, la part de valeur_achat retiree pour une sortie ou un transfert.
-- Permet d'annuler un mouvement sans deviner ce qu'il avait apporte ou
-- retire. NULL pour les lignes anterieures : lue comme leur montant.
ALTER TABLE lignes_mouvement_stock
    ADD COLUMN montant_achat NUMERIC(15,2);
