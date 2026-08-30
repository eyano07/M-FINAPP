-- =====================================================================
-- V3 : Priorite de paiement des notes de frais
-- Definie par le DA apres validation (BASSE / MOYENNE / HAUTE).
-- =====================================================================

ALTER TABLE notes_frais ADD COLUMN IF NOT EXISTS priorite VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_notes_frais_priorite ON notes_frais(priorite);
