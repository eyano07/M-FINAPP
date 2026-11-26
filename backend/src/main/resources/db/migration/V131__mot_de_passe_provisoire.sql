-- ---------------------------------------------------------------------------
-- V131 : mot de passe provisoire a remplacer a la premiere connexion.
--
-- Un compte cree par l'administrateur (ou dont l'administrateur redefinit le mot de passe) recoit un mot de passe
-- que l'administrateur connait : tant que l'utilisateur ne l'a pas remplace par le sien (PUT /profil/mot-de-passe),
-- seuls la connexion et le changement de mot de passe lui sont ouverts (MotDePasseProvisoireFilter).
-- Les comptes existants ne sont pas concernes (FALSE par defaut).
-- ---------------------------------------------------------------------------
ALTER TABLE users ADD COLUMN IF NOT EXISTS doit_changer_mot_de_passe BOOLEAN NOT NULL DEFAULT FALSE;
