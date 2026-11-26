-- Modele de papier a en-tete choisi par l'administrateur, applique a tous les documents imprimes et PDF.
-- CLASSIQUE reprend la mise en page historique (bandeau incline a droite).
ALTER TABLE parametres_entreprise
    ADD COLUMN modele_entete VARCHAR(20) NOT NULL DEFAULT 'CLASSIQUE';
