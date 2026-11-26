-- Bénéficiaire d'une pièce comptable (15/11/2026) : le nom de la personne ou de l'entité payée ou servie, saisi
-- avec la pièce ou lu dans une colonne « Bénéficiaire » du journal importé. Le journal des comptes l'affiche quand
-- aucune note de frais n'en fournit un. Facultatif.
ALTER TABLE pieces_comptables ADD COLUMN IF NOT EXISTS beneficiaire VARCHAR(255);
