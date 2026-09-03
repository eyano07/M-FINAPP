-- Adresse et telephone de l'entreprise, configurables par l'ADMIN, affiches
-- en pied de page des documents imprimes (bulletins de paie, etc.).
ALTER TABLE parametres_entreprise ADD COLUMN adresse VARCHAR(255);
ALTER TABLE parametres_entreprise ADD COLUMN telephone VARCHAR(30);
