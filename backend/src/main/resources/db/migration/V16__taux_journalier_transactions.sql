-- ============================================================
-- MBSC Finapp - V16 : taux de change journalier sur les operations
--   de tresorerie. Capture le taux (FC pour 1 USD) en vigueur au
--   moment de chaque operation, pour tracabilite/audit — distinct
--   du taux du jour courant utilise pour l'affichage USD du journal.
-- ============================================================

ALTER TABLE transactions_caisse       ADD COLUMN taux_journalier NUMERIC(15,6);
ALTER TABLE transactions_bancaires    ADD COLUMN taux_journalier NUMERIC(15,6);
ALTER TABLE transactions_mobile_money ADD COLUMN taux_journalier NUMERIC(15,6);
