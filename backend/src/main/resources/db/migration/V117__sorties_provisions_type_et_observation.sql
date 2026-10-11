-- Sorties de provisions : un type de sortie choisi dans une liste, et une observation libre, à la place du motif libre.
--
-- La sortie elle-même est un mouvement de stock ordinaire (sortie au coût moyen, avec sa pièce comptable) : cette table
-- en garde le type et l'observation, que le mouvement de stock ne sait pas porter. Une sortie saisie avant cette table
-- n'y a pas de ligne : son type reste inconnu, son ancien motif libre n'étant conservé que dans le libellé du mouvement.
--
-- ON DELETE CASCADE : la purge du restaurant supprime les provisions et leurs mouvements ; leurs sorties partent avec eux.
CREATE TABLE restaurant_sorties_provisions (
    id BIGSERIAL PRIMARY KEY,
    mouvement_stock_id BIGINT NOT NULL UNIQUE REFERENCES mouvements_stock(id) ON DELETE CASCADE,
    type_sortie VARCHAR(30) NOT NULL
        CONSTRAINT chk_sorties_provisions_type
        CHECK (type_sortie IN ('UTILISATION_CUISINE', 'CASSE', 'PEREMPTION', 'AVARIE', 'PERTE', 'REPAS_PERSONNEL', 'DON', 'AUTRE')),
    observation VARCHAR(500),
    created_by_id BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
