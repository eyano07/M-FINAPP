CREATE SEQUENCE seq_production START 1;

-- Fiche technique : quantite d'une provision pour UNE portion du plat.
-- Pas de table d'en-tete : un plat n'a qu'une seule fiche, ses lignes suffisent
-- a la porter (meme principe que menu_jour porte par sa date).
CREATE TABLE lignes_recette (
    id           BIGSERIAL PRIMARY KEY,
    plat_id      BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    provision_id BIGINT NOT NULL REFERENCES articles(id),
    quantite     NUMERIC(15,3) NOT NULL,
    CONSTRAINT uq_ligne_recette UNIQUE (plat_id, provision_id)
);
CREATE INDEX idx_lignes_recette_plat ON lignes_recette(plat_id);

-- Une production transforme des provisions en portions d'un plat. Elle porte
-- les deux mouvements de stock qu'elle a generes (sortie des ingredients puis
-- entree du plat) : c'est ce qui rend l'annulation possible.
CREATE TABLE productions (
    id                  BIGSERIAL PRIMARY KEY,
    reference           VARCHAR(30) NOT NULL UNIQUE,
    date_production     DATE NOT NULL,
    plat_id             BIGINT NOT NULL REFERENCES articles(id),
    entrepot_id         BIGINT NOT NULL REFERENCES entrepots(id),
    quantite            NUMERIC(15,3) NOT NULL,
    cout_total          NUMERIC(15,2) NOT NULL DEFAULT 0,
    cout_unitaire       NUMERIC(15,6) NOT NULL DEFAULT 0,
    statut              VARCHAR(20) NOT NULL,
    mouvement_sortie_id BIGINT REFERENCES mouvements_stock(id),
    mouvement_entree_id BIGINT REFERENCES mouvements_stock(id),
    created_by_id       BIGINT REFERENCES users(id),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_productions_date ON productions(date_production);
CREATE INDEX idx_productions_plat ON productions(plat_id);

-- Ce qui a REELLEMENT ete consomme : peut differer de la fiche technique
-- (perte, substitution, portion plus genereuse). Le cout unitaire y est celui
-- du CMP au moment de la sortie, fige.
CREATE TABLE lignes_production (
    id            BIGSERIAL PRIMARY KEY,
    production_id BIGINT NOT NULL REFERENCES productions(id) ON DELETE CASCADE,
    provision_id  BIGINT NOT NULL REFERENCES articles(id),
    quantite      NUMERIC(15,3) NOT NULL,
    cout_unitaire NUMERIC(15,2) NOT NULL DEFAULT 0,
    montant       NUMERIC(15,2) NOT NULL DEFAULT 0
);
CREATE INDEX idx_lignes_production_prod ON lignes_production(production_id);
