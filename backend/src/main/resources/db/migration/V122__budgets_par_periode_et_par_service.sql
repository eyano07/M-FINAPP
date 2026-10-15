-- ---------------------------------------------------------------------------
-- Budgets d'une periode plus courte que l'annee, et budgets par service.
--
-- 1. Periode : un budget couvre l'exercice entier (ANNUEL, comme jusqu'ici), un semestre, un trimestre ou
--    un seul mois. Exemple : un logiciel installe en novembre recoit un budget pour decembre seulement, au
--    lieu d'un budget annuel pour une annee qui s'acheve. La ventilation reste mensuelle (douze mois de
--    l'exercice) : les mois hors de la periode restent a zero (regle du service). Plusieurs budgets d'un
--    meme exercice peuvent etre en execution s'ils ne couvrent pas les memes mois.
-- 2. Services : un service (departement) porte le nom de son responsable. Les lignes de depenses d'un
--    budget et les notes de frais peuvent y etre rattachees : chaque service a son enveloppe, le budget
--    reste unique (consolide), et les depenses se suivent par service.
--
-- Les budgets existants deviennent des budgets annuels sans service : rien ne change pour eux.
-- ---------------------------------------------------------------------------

-- 1. Services
CREATE TABLE services_budgetaires (
    id            BIGSERIAL    PRIMARY KEY,
    nom           VARCHAR(120) NOT NULL,
    responsable   VARCHAR(150) NOT NULL,
    description   VARCHAR(500),
    actif         BOOLEAN      NOT NULL DEFAULT TRUE,
    cree_par_id   BIGINT       REFERENCES users(id),
    date_creation TIMESTAMP    NOT NULL DEFAULT now()
);
-- Deux services ne portent pas le meme nom, a la casse pres.
CREATE UNIQUE INDEX ux_services_budgetaires_nom ON services_budgetaires (lower(nom));

-- Enveloppe d'un service : ses lignes de budget (null = ligne generale, sans service).
ALTER TABLE lignes_budget ADD COLUMN service_id BIGINT REFERENCES services_budgetaires(id);
CREATE INDEX idx_lignes_budget_service ON lignes_budget (service_id);

-- Service qui engage la depense d'une note de frais (facultatif).
ALTER TABLE notes_frais ADD COLUMN service_id BIGINT REFERENCES services_budgetaires(id);
CREATE INDEX idx_notes_frais_service ON notes_frais (service_id);

-- 2. Periode couverte par un budget
ALTER TABLE budgets ADD COLUMN duree   VARCHAR(12) NOT NULL DEFAULT 'ANNUEL';
ALTER TABLE budgets ADD COLUMN periode INTEGER     NOT NULL DEFAULT 1;
ALTER TABLE budgets ADD CONSTRAINT ck_budgets_periode CHECK (
       (duree = 'ANNUEL'      AND periode = 1)
    OR (duree = 'SEMESTRIEL'  AND periode BETWEEN 1 AND 2)
    OR (duree = 'TRIMESTRIEL' AND periode BETWEEN 1 AND 4)
    OR (duree = 'MENSUEL'     AND periode BETWEEN 1 AND 12));

-- Premier et dernier mois couverts : deduits de la duree et de la periode, jamais saisis.
ALTER TABLE budgets ADD COLUMN mois_debut INTEGER GENERATED ALWAYS AS (
    CASE duree WHEN 'SEMESTRIEL'  THEN periode * 6 - 5
               WHEN 'TRIMESTRIEL' THEN periode * 3 - 2
               WHEN 'MENSUEL'     THEN periode
               ELSE 1 END) STORED;
ALTER TABLE budgets ADD COLUMN mois_fin INTEGER GENERATED ALWAYS AS (
    CASE duree WHEN 'SEMESTRIEL'  THEN periode * 6
               WHEN 'TRIMESTRIEL' THEN periode * 3
               WHEN 'MENSUEL'     THEN periode
               ELSE 12 END) STORED;

-- 3. Un seul budget en execution pour un meme mois (et non plus pour un meme exercice) : un budget de
--    decembre et un budget du premier trimestre suivant peuvent etre en execution ensemble, pas un budget
--    annuel et un budget de decembre de la meme annee. Les mois sont numerotes de facon absolue
--    (exercice x 12 + mois) pour qu'une seule plage d'entiers suffise, sans extension.
--    Pose seulement si les donnees deja presentes respectent la regle (le service l'applique dans tous les
--    cas) : une migration ne doit pas echouer sur des donnees anterieures a la regle.
DROP INDEX IF EXISTS ux_budgets_un_en_execution_par_exercice;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM budgets WHERE statut = 'EN_EXECUTION' GROUP BY exercice HAVING count(*) > 1) THEN
    ALTER TABLE budgets ADD CONSTRAINT ex_budgets_un_en_execution_par_mois
      EXCLUDE USING gist (int4range(exercice * 12 + mois_debut, exercice * 12 + mois_fin, '[]') WITH &&)
      WHERE (statut = 'EN_EXECUTION');
  END IF;
END $$;
