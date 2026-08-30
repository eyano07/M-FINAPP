-- Une note de frais peut desormais comporter plusieurs lignes de depense
-- (montant + compte d'imputation + description propres a chaque ligne),
-- au lieu d'un montant et d'un compte unique portes par l'entete.

CREATE TABLE lignes_note_frais (
    id                    BIGSERIAL PRIMARY KEY,
    note_id               BIGINT NOT NULL REFERENCES notes_frais(id) ON DELETE CASCADE,
    montant               NUMERIC(15,2) NOT NULL CHECK (montant > 0),
    compte_imputation_id  BIGINT REFERENCES comptes_ohada(id),
    description           VARCHAR(500),
    ordre                 INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_lignes_note_frais_note ON lignes_note_frais(note_id);

-- Reprise des notes existantes : chaque note actuelle devient une note a une
-- seule ligne, reprenant son montant, son compte d'imputation et son objet.
INSERT INTO lignes_note_frais (note_id, montant, compte_imputation_id, description, ordre)
SELECT id, montant, compte_imputation_id, objet, 1 FROM notes_frais;

-- Le montant reste sur l'entete (cache = somme des lignes, maintenu par
-- l'application) pour ne pas casser les requetes de reporting existantes.
-- Le compte d'imputation unique n'a plus de sens : la ventilation se fait
-- desormais ligne par ligne.
ALTER TABLE notes_frais DROP COLUMN compte_imputation_id;

-- Pieces jointes (PDF / images) : tracabilite de l'auteur et de la date d'ajout.
ALTER TABLE pieces_jointes ADD COLUMN ajoute_par_id BIGINT REFERENCES users(id);
ALTER TABLE pieces_jointes ADD COLUMN date_ajout TIMESTAMP NOT NULL DEFAULT now();
