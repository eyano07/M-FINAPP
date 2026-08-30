-- ============================================================
-- MBSC Finapp - V13 : Durcissement comptable professionnel
--   (inspiré Sage / QuickBooks)
--   1. Garde-fous SQL sur le grand livre (partie double)
--   2. Traçabilité multi-devises sur les écritures
--   3. Date de clôture comptable (closing date QuickBooks)
--   4. Comptes imputables / actifs (saisie interdite sur les
--      comptes de regroupement, désactivation au lieu de la
--      suppression)
--   5. Verrou optimiste sur les niveaux de stock
-- ============================================================

-- ── 1. Contraintes d'intégrité du grand livre ────────────────
-- NOT VALID : n'invalide pas les lignes historiques éventuelles,
-- mais s'applique à toute nouvelle écriture.
ALTER TABLE grand_livre
    ADD CONSTRAINT chk_grand_livre_montants_positifs
    CHECK (debit >= 0 AND credit >= 0) NOT VALID;

ALTER TABLE grand_livre
    ADD CONSTRAINT chk_grand_livre_debit_xor_credit
    CHECK (NOT (debit > 0 AND credit > 0)) NOT VALID;

-- ── 2. Multi-devises : traçabilité sur chaque écriture ───────
-- Devise de base = CDF. Pour une opération saisie en devise
-- étrangère, on conserve la devise d'origine, le montant
-- d'origine et le taux appliqué à la comptabilisation.
ALTER TABLE grand_livre
    ADD COLUMN devise VARCHAR(3) NOT NULL DEFAULT 'CDF',
    ADD COLUMN montant_devise NUMERIC(15,2),
    ADD COLUMN taux_applique NUMERIC(15,6);

-- ── 3. Paramètres comptables (ligne unique) ──────────────────
CREATE TABLE parametres_comptables (
    id           BIGINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    date_cloture DATE,
    maj_par_id   BIGINT REFERENCES users(id),
    maj_le       TIMESTAMP
);

INSERT INTO parametres_comptables (id, date_cloture) VALUES (1, NULL)
ON CONFLICT (id) DO NOTHING;

-- ── 4. Plan comptable : imputable / actif ────────────────────
ALTER TABLE comptes_ohada
    ADD COLUMN imputable BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN actif     BOOLEAN NOT NULL DEFAULT TRUE;

-- Un compte est un compte de regroupement (non imputable) s'il
-- existe un compte plus long qui commence par son numéro.
UPDATE comptes_ohada p
SET imputable = FALSE
WHERE EXISTS (
    SELECT 1 FROM comptes_ohada c
    WHERE c.id <> p.id
      AND length(c.numero) > length(p.numero)
      AND c.numero LIKE p.numero || '%'
);

-- Exception : un compte de regroupement déjà mouvementé reste
-- imputable pour ne pas bloquer l'existant (571 par exemple).
UPDATE comptes_ohada p
SET imputable = TRUE
WHERE EXISTS (SELECT 1 FROM grand_livre g WHERE g.compte_id = p.id);

-- ── 5. Verrou optimiste sur les niveaux de stock ─────────────
ALTER TABLE stock_niveaux
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
