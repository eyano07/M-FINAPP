CREATE TABLE salles_restaurant (
    id     BIGSERIAL PRIMARY KEY,
    nom    VARCHAR(100) NOT NULL,
    ordre  INT NOT NULL DEFAULT 0,
    actif  BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE tables_restaurant (
    id        BIGSERIAL PRIMARY KEY,
    salle_id  BIGINT NOT NULL REFERENCES salles_restaurant(id) ON DELETE CASCADE,
    numero    VARCHAR(20) NOT NULL,
    forme     VARCHAR(10) NOT NULL DEFAULT 'CARRE',
    pos_x     INT NOT NULL DEFAULT 0,
    pos_y     INT NOT NULL DEFAULT 0,
    largeur   INT NOT NULL DEFAULT 100,
    hauteur   INT NOT NULL DEFAULT 100
);

CREATE INDEX idx_tables_restaurant_salle ON tables_restaurant(salle_id);
