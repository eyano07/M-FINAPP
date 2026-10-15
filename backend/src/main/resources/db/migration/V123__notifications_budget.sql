-- ---------------------------------------------------------------------------
-- Notifications du circuit des budgets (soumission au DA, approbation, rejet, mise en execution, depense validee
-- hors budget ou en depassement) : rattachees au budget concerne, comme celles des notes de frais le sont a leur
-- note. Un budget supprime (brouillon ou rejete) emporte ses notifications, dont le lien n'aurait plus de cible.
-- ---------------------------------------------------------------------------
ALTER TABLE notifications ADD COLUMN budget_id BIGINT REFERENCES budgets(id) ON DELETE CASCADE;
CREATE INDEX idx_notifications_budget ON notifications (budget_id);
