-- ---------------------------------------------------------------------------
-- Comptes desactives par la cascade des modules.
--
-- Quand l'administrateur desactive un module, les roles propres a ce module disparaissent de la gestion des
-- utilisateurs, et les comptes qui n'ont plus que de tels roles sont desactives (users.actif = FALSE) puis
-- masques. Ils redeviennent actifs et visibles quand le module est reactive (SynchronisationComptesModules).
--
-- Cette colonne distingue un compte desactive PAR LA CASCADE d'un compte desactive A LA MAIN par
-- l'administrateur : seul le premier est reactive automatiquement, le second ne l'est jamais.
-- ---------------------------------------------------------------------------
ALTER TABLE users ADD COLUMN desactive_par_module BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN users.desactive_par_module IS
    'TRUE si le compte a ete desactive automatiquement parce que tous ses roles sont lies a des modules desactives ; '
    'il est reactive avec le module. Une desactivation manuelle ne pose jamais ce drapeau.';
