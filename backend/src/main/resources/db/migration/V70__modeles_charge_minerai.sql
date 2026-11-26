-- Frais accessoires standards d'un minerais, reappliques a chaque reception.
--
-- La case « appliquer a tous les camions » ne valait jusqu'ici que pour
-- l'instant de la saisie : elle touchait les camions alors en stock, mais un
-- chargement receptionne le lendemain repartait sans frais. Or un pont
-- bascule, une autorisation de transport ou un peage se repetent a
-- l'identique sur chaque camion d'un meme projet : c'est une regle du
-- minerais, pas un geste ponctuel.
--
-- Cocher la case enregistre donc desormais le frais comme MODELE du minerais.
-- Chaque reception ulterieure le rejoue automatiquement sur le nouveau
-- camion, avec ses propres ecritures — la ligne creee reste modifiable ou
-- supprimable camion par camion, comme n'importe quel frais saisi a la main.
-- Le modele ne fixe qu'un montant par defaut : le per diem ou le transport
-- peuvent varier d'un chargement a l'autre sans le remettre en cause.
CREATE TABLE modeles_charge_minerai (
    id               BIGSERIAL PRIMARY KEY,
    article_id       BIGINT       NOT NULL REFERENCES articles(id),
    libelle          VARCHAR(200) NOT NULL,
    compte_charge_id BIGINT       NOT NULL REFERENCES comptes_ohada(id),
    montant          NUMERIC(15, 2) NOT NULL CHECK (montant > 0),
    date_creation    TIMESTAMP,
    date_maj         TIMESTAMP
);

-- Un seul modele par nature et par minerais : recocher la case sur un frais
-- deja standard en mets simplement le montant a jour, plutot que d'empiler
-- des doublons qui se cumuleraient a chaque reception.
CREATE UNIQUE INDEX uq_modele_charge_article_libelle
    ON modeles_charge_minerai (article_id, lower(libelle));
