-- Reserve de tresorerie minimale exigee par priorite de note de frais,
-- definie par le DA (voir ParametresPrioriteNote / RegleTresorerieService).
-- Ligne unique (id = 1), meme convention que parametres_comptables.
--
-- Seuils a 0 par defaut : 0 = aucune restriction. Le deploiement de cette
-- migration ne modifie donc AUCUN comportement de paiement tant que le DA
-- n'a pas saisi ses propres montants.
CREATE TABLE parametres_priorite_note (
    id            BIGINT PRIMARY KEY,
    seuil_basse   NUMERIC(15,2) NOT NULL DEFAULT 0,
    seuil_moyenne NUMERIC(15,2) NOT NULL DEFAULT 0,
    seuil_haute   NUMERIC(15,2) NOT NULL DEFAULT 0,
    maj_par_id    BIGINT REFERENCES users(id),
    maj_le        TIMESTAMP
);

INSERT INTO parametres_priorite_note (id, seuil_basse, seuil_moyenne, seuil_haute)
VALUES (1, 0, 0, 0);
