-- Lien optionnel entre un frais accessoire de camion minerais et la note de
-- frais qui demande son reglement (meme mecanisme que
-- camions_minerai.note_frais_reglement_id) : sans ce lien, un meme frais
-- pourrait figurer dans deux notes a la fois.
ALTER TABLE charges_camion_minerai
    ADD COLUMN note_frais_reglement_id BIGINT NULL REFERENCES notes_frais(id);

CREATE INDEX idx_charge_camion_minerai_note_frais_reglement ON charges_camion_minerai(note_frais_reglement_id);
