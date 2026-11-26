-- ---------------------------------------------------------------------------
-- Module BUDGET, sous-module de COMPTABILITE : l'administrateur peut activer
-- ou desactiver les budgets (ecran Modules). Desactive, les ecrans Budgets
-- sont fermes et les depenses (notes de frais) ne sont plus rattachees ni
-- controlees par rapport au budget (ControleBudgetaireService).
--
-- Actif par defaut : rien ne change tant que l'administrateur ne le coupe pas.
-- Les droits reprennent ceux de COMPTABILITE pour les roles qui consultent
-- les budgets (DFIN, DA, DG, COMPTABLE), y compris s'ils ont ete ajustes.
-- ---------------------------------------------------------------------------
INSERT INTO modules_config (module, actif, parent_module) VALUES ('BUDGET', TRUE, 'COMPTABILITE')
ON CONFLICT (module) DO UPDATE SET parent_module = EXCLUDED.parent_module;

INSERT INTO role_permissions (role, module, niveau)
SELECT role, 'BUDGET', niveau FROM role_permissions
WHERE module = 'COMPTABILITE' AND role IN ('DFIN', 'DA', 'DG', 'COMPTABLE')
ON CONFLICT (role, module) DO NOTHING;
