-- Consommables simplifiés : l'utilisateur donne le nom, le prix, l'unité, le seuil et le type ; le système crée les
-- sous-comptes de stock et d'achat propres au consommable. Une facture proforma (image ou PDF) peut y être jointe.
ALTER TABLE articles ADD COLUMN type_consommable VARCHAR(30);
ALTER TABLE articles ADD COLUMN proforma_chemin  VARCHAR(500);
ALTER TABLE articles ADD COLUMN proforma_nom     VARCHAR(255);
ALTER TABLE articles ADD COLUMN proforma_type    VARCHAR(100);

-- Codes des consommables : CONS-000001, CONS-000002...
CREATE SEQUENCE IF NOT EXISTS seq_consommable;
