-- Inventaire du patrimoine : le comptage porte sur tous les biens de l'entreprise relevés à l'inventaire
-- (immobilisés, petit matériel déclassé, à valoriser), plus les immobilisations saisies sans code d'inventaire.
-- Une ligne vise donc un bien d'inventaire, une immobilisation, ou les deux.
ALTER TABLE inventaires_immobilisations_lignes ALTER COLUMN immobilisation_id DROP NOT NULL;
ALTER TABLE inventaires_immobilisations_lignes ADD COLUMN bien_id BIGINT REFERENCES inventaire_biens(id);
ALTER TABLE inventaires_immobilisations_lignes ADD CONSTRAINT ck_inventaire_immo_objet
    CHECK (immobilisation_id IS NOT NULL OR bien_id IS NOT NULL);
ALTER TABLE inventaires_immobilisations_lignes ADD CONSTRAINT uq_inventaire_bien UNIQUE (inventaire_id, bien_id);
