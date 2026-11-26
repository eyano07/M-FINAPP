-- Sorties de stock des plats hors vente : périmé, moisi, renversé, brûlé,
-- offert, repas du personnel...
--
-- Chacune porte une sortie de stock ordinaire, comptabilisée au coût de
-- production moyen du plat, comme la part « coût » d'une vente :
-- D 736x Variations des stocks de produits finis / C 361x stock du plat.
-- Cette table en garde le motif, que le mouvement de stock ne sait pas
-- porter, et l'état (annulée ou non).
--
-- ON DELETE CASCADE : la purge du restaurant (purge_restaurant.sql)
-- supprime les plats et leurs mouvements ; leurs sorties partent avec eux.
CREATE TABLE restaurant_sorties_plats (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    entrepot_id BIGINT NOT NULL REFERENCES entrepots(id),
    mouvement_stock_id BIGINT NOT NULL UNIQUE REFERENCES mouvements_stock(id) ON DELETE CASCADE,
    quantite NUMERIC(15,3) NOT NULL CHECK (quantite > 0),
    motif VARCHAR(30) NOT NULL
        CONSTRAINT chk_sorties_plats_motif
        CHECK (motif IN ('PERIME', 'MOISI', 'RENVERSE', 'BRULE', 'CADEAU', 'REPAS_PERSONNEL', 'AUTRE')),
    precision_motif VARCHAR(500),
    date_sortie DATE NOT NULL,
    -- Coût sorti (quantité × coût de production moyen), en USD comme le grand livre.
    valeur NUMERIC(15,2) NOT NULL DEFAULT 0,
    annulee BOOLEAN NOT NULL DEFAULT FALSE,
    annulee_le TIMESTAMPTZ,
    annulee_par_id BIGINT REFERENCES users(id),
    created_by_id BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sorties_plats_date ON restaurant_sorties_plats (date_sortie);
