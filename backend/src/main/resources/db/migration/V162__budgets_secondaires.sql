-- Budgets secondaires (26/11/2026) : à côté du budget principal de l'entreprise, des budgets parallèles (le budget d'un
-- projet, par exemple). Un budget secondaire suit son propre circuit (DFIN, DA, exécution) et peut être en exécution en
-- même temps que le budget principal et que d'autres budgets secondaires.
--
-- Une note de frais précise son budget : sans budget secondaire, elle relève du budget principal ; avec, elle est
-- contrôlée et suivie sur ce budget seulement (et sort du réalisé et de l'engagé du budget principal).

ALTER TABLE budgets ADD COLUMN type_budget VARCHAR(12) NOT NULL DEFAULT 'PRINCIPAL'
    CHECK (type_budget IN ('PRINCIPAL', 'SECONDAIRE'));

-- « Un seul budget en exécution par mois » ne vaut plus que pour le budget principal.
ALTER TABLE budgets DROP CONSTRAINT IF EXISTS ex_budgets_un_en_execution_par_mois;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM budgets b1 JOIN budgets b2 ON b1.id < b2.id
                 WHERE b1.statut = 'EN_EXECUTION' AND b2.statut = 'EN_EXECUTION'
                   AND int4range(b1.exercice * 12 + b1.mois_debut, b1.exercice * 12 + b1.mois_fin, '[]')
                    && int4range(b2.exercice * 12 + b2.mois_debut, b2.exercice * 12 + b2.mois_fin, '[]')) THEN
    ALTER TABLE budgets ADD CONSTRAINT ex_budgets_un_en_execution_par_mois
      EXCLUDE USING gist (int4range(exercice * 12 + mois_debut, exercice * 12 + mois_fin, '[]') WITH &&)
      WHERE (statut = 'EN_EXECUTION' AND type_budget = 'PRINCIPAL');
  END IF;
END $$;

-- Budget secondaire d'une note de frais (null : budget principal).
ALTER TABLE notes_frais ADD COLUMN budget_secondaire_id BIGINT REFERENCES budgets(id);
CREATE INDEX idx_notes_frais_budget_secondaire ON notes_frais (budget_secondaire_id);
