-- Copie d'un budget à chaque soumission au DA (26/11/2026) : ses lignes telles que soumises. Un budget rejeté puis
-- modifié et soumis à nouveau montre au DA ce qui a changé depuis la soumission précédente (comptes ajoutés, retirés,
-- montants modifiés).
CREATE TABLE budget_soumissions (
    id          BIGSERIAL    PRIMARY KEY,
    budget_id   BIGINT       NOT NULL REFERENCES budgets(id) ON DELETE CASCADE,
    numero      INTEGER      NOT NULL,
    soumis_le   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    soumis_par  VARCHAR(200),
    lignes      TEXT         NOT NULL,
    UNIQUE (budget_id, numero)
);
CREATE INDEX idx_budget_soumissions_budget ON budget_soumissions (budget_id);
