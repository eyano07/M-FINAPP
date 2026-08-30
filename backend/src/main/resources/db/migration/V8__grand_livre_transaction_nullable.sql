-- V8 : rend la colonne transaction_id nullable pour autoriser les écritures directes
ALTER TABLE grand_livre ALTER COLUMN transaction_id DROP NOT NULL;
