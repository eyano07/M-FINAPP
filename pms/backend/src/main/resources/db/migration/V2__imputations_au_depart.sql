-- Part des acomptes imputée sur le folio et caution retenue au départ : sans elles, après le départ, l'hôtellerie
-- montrait encore ces sommes comme détenues (et proposait de les rendre une seconde fois).
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS acompte_impute NUMERIC(15, 2) NOT NULL DEFAULT 0;
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS caution_retenue NUMERIC(15, 2) NOT NULL DEFAULT 0;
