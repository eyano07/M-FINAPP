-- Ajout de la colonne pour distinguer les comptes pré-chargés des comptes manuels
ALTER TABLE comptes_ohada ADD COLUMN IF NOT EXISTS manuel BOOLEAN NOT NULL DEFAULT FALSE;
