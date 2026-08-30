-- V9 : ajoute le libellé sur la transaction de caisse (affiché dans l'historique)
ALTER TABLE transactions_caisse ADD COLUMN IF NOT EXISTS libelle VARCHAR(255);
