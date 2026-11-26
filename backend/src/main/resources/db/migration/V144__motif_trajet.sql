-- Motif d'un trajet de la flotte (15/11/2026) : pourquoi le véhicule s'est déplacé (livraison, mission, achat…).
-- Facultatif, affiché dans la liste des trajets.
ALTER TABLE trajets ADD COLUMN IF NOT EXISTS motif VARCHAR(255);
