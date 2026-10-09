-- Budget previsionnel : ventilation mensuelle (les trimestres, semestres et l'annee en sont des totaux),
-- references, revisions tracees, dates du circuit d'approbation, et controle budgetaire des notes de frais.
-- Le « realise » n'est plus stocke : il est calcule a la demande depuis le grand livre (voir SuiviBudgetaireService).

-- 1. Ventilation mensuelle de chaque ligne (mois 1 = janvier de l'exercice).
CREATE TABLE lignes_budget_mois (
    ligne_budget_id BIGINT        NOT NULL REFERENCES lignes_budget(id) ON DELETE CASCADE,
    mois            INTEGER       NOT NULL CHECK (mois BETWEEN 1 AND 12),
    montant         NUMERIC(15,2) NOT NULL DEFAULT 0 CHECK (montant >= 0),
    PRIMARY KEY (ligne_budget_id, mois)
);

-- Lignes deja saisies : montant annuel reparti a parts egales, les centimes restants sur decembre
-- (la somme des douze mois est exactement le montant annuel).
INSERT INTO lignes_budget_mois (ligne_budget_id, mois, montant)
SELECT l.id, m.mois,
       CASE WHEN m.mois < 12 THEN trunc(l.montant_prevu / 12, 2)
            ELSE l.montant_prevu - 11 * trunc(l.montant_prevu / 12, 2) END
FROM lignes_budget l
CROSS JOIN generate_series(1, 12) AS m(mois);

-- Hypothese ou base de calcul de la ligne (ex. « 2 vehicules x 120 l/mois »).
ALTER TABLE lignes_budget ADD COLUMN commentaire VARCHAR(500);

-- 2. Budgets : reference, revisions, dates du circuit.
ALTER TABLE budgets ADD COLUMN reference        VARCHAR(30);
ALTER TABLE budgets ADD COLUMN revision_de_id   BIGINT REFERENCES budgets(id);
ALTER TABLE budgets ADD COLUMN numero_revision  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE budgets ADD COLUMN motif_rejet      VARCHAR(1000);
ALTER TABLE budgets ADD COLUMN date_soumission  TIMESTAMP;
ALTER TABLE budgets ADD COLUMN date_approbation TIMESTAMP;
ALTER TABLE budgets ADD COLUMN date_execution   TIMESTAMP;
ALTER TABLE budgets ADD COLUMN date_cloture     TIMESTAMP;

-- References des budgets existants, tirees de la meme sequence que les nouveaux (aucune collision possible).
UPDATE budgets b
   SET reference = 'BUD-' || b.exercice || '-' || lpad(nextval('seq_budget')::text, 6, '0')
  FROM (SELECT id FROM budgets ORDER BY id) o
 WHERE b.id = o.id;
ALTER TABLE budgets ALTER COLUMN reference SET NOT NULL;
CREATE UNIQUE INDEX ux_budgets_reference ON budgets (reference);

-- Un seul budget en execution par exercice. Pose seulement si les donnees deja presentes le respectent
-- (le service applique la meme regle dans tous les cas) : une migration ne doit pas echouer sur des
-- donnees anterieures a la regle.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM budgets WHERE statut = 'EN_EXECUTION' GROUP BY exercice HAVING count(*) > 1) THEN
    CREATE UNIQUE INDEX ux_budgets_un_en_execution_par_exercice ON budgets (exercice) WHERE statut = 'EN_EXECUTION';
  END IF;
END $$;

-- 3. Notes de frais : resultat du controle budgetaire releve a la soumission, et justification exigee
--    quand une depense n'est pas couverte par le budget (hors budget, depassement, aucun budget).
ALTER TABLE notes_frais ADD COLUMN statut_budget        VARCHAR(20);
ALTER TABLE notes_frais ADD COLUMN justification_budget VARCHAR(1000);
ALTER TABLE notes_frais ADD COLUMN budget_id            BIGINT REFERENCES budgets(id);
