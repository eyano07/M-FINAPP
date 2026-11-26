-- Ligne de note imputée à un compte d'immobilisation (21 à 24) : le bien est-il amortissable ? Oui : le paiement de
-- la note crée la fiche du bien et son plan d'amortissement. Non (terrain, œuvre d'art...) : inscrit au bilan sans
-- dotation. Null pour toute autre ligne.
ALTER TABLE lignes_note_frais ADD COLUMN amortissable BOOLEAN;
