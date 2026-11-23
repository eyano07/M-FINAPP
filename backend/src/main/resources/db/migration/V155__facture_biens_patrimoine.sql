-- ---------------------------------------------------------------------------
-- Facture d'acquisition d'un bien du patrimoine (23/11/2026) : image (JPEG, PNG, WebP) ou PDF, jointe à la
-- valorisation ou plus tard. Le fichier est dans le stockage de M-FINAPP (patrimoine/factures) ; la base n'en garde
-- que le chemin, le nom d'origine et le type.
-- ---------------------------------------------------------------------------
ALTER TABLE inventaire_biens ADD COLUMN IF NOT EXISTS facture_chemin VARCHAR(500);
ALTER TABLE inventaire_biens ADD COLUMN IF NOT EXISTS facture_nom    VARCHAR(255);
ALTER TABLE inventaire_biens ADD COLUMN IF NOT EXISTS facture_type   VARCHAR(100);
