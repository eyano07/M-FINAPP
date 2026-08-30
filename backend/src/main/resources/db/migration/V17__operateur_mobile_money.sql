-- ============================================================
-- MBSC Finapp - V17 : operateur telecom sur les transactions
--   mobile money (Airtel, Orange, Vodacom, Africell).
-- ============================================================

ALTER TABLE transactions_mobile_money ADD COLUMN operateur_telecom VARCHAR(20);
