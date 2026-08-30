-- Une ligne "achat de marchandise" d'une note de frais reference l'article
-- et l'entrepot concernes, pour alimenter le stock au paiement de la note.
ALTER TABLE lignes_note_frais ADD COLUMN article_id BIGINT REFERENCES articles(id);
ALTER TABLE lignes_note_frais ADD COLUMN entrepot_id BIGINT REFERENCES entrepots(id);
