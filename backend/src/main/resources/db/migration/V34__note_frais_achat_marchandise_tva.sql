-- Une ligne de note de frais peut representer un achat de marchandises,
-- avec une quantite et, si l'achat est soumis a la TVA, le compte de TVA
-- recuperable a mouvementer separement du compte de charge.
ALTER TABLE lignes_note_frais ADD COLUMN achat_marchandise BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE lignes_note_frais ADD COLUMN quantite_marchandise NUMERIC(15,3);
ALTER TABLE lignes_note_frais ADD COLUMN soumis_tva BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE lignes_note_frais ADD COLUMN compte_tva_id BIGINT REFERENCES comptes_ohada(id);
