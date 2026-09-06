-- Lien optionnel entre un camion de minerais et la note de frais qui
-- demande le reglement de sa dette fournisseur (circuit DFIN/DA/Tresorerie),
-- distinct du reglement direct depuis la caisse. Nul tant qu'aucune note
-- n'a ete creee pour ce camion, ou de nouveau nul si la note est annulee.
ALTER TABLE camions_minerai
    ADD COLUMN note_frais_reglement_id BIGINT NULL REFERENCES notes_frais(id);

CREATE INDEX idx_camion_minerai_note_frais_reglement ON camions_minerai(note_frais_reglement_id);
