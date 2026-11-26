-- Note de frais déclarée hors budget par le demandeur (26/11/2026) : la dépense ne s'impute sur aucun budget (ni le
-- principal ni un budget secondaire), sa justification est obligatoire et le DA doit motiver sa validation.
ALTER TABLE notes_frais ADD COLUMN hors_budget BOOLEAN NOT NULL DEFAULT FALSE;
