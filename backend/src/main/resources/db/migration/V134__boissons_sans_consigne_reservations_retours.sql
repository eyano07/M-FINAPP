-- ---------------------------------------------------------------------------
-- V134 : boissons sans consigne, pertes de ces boissons, retours de ventes remboursés (et leur avoir normalisé).
--
-- 1. Une boisson est « consignée » (bouteille gérée en vides : bière, sucrerie en casier) ou « sans consigne »
--    (whisky, vin, champagne, jus en carton...) : choix fait sur sa fiche. Une boisson sans consigne s'achète par carton
--    et/ou à l'unité sans jamais toucher au stock de vides, et n'a pas de conditionnement.
--    Les boissons existantes sans conditionnement deviennent « sans consigne » : leur comportement ne change pas (elles
--    n'avaient déjà aucun vide ni à la vente ni à l'achat).
-- 2. Les pertes d'une boisson sans consigne (casse, péremption, cadeau) ne passent pas par les mouvements de vides :
--    elles ont leur propre journal, comme les sorties de plats (V91).
-- 3. Retour d'une vente payée : le caissier demande, le responsable du bar ou du restaurant approuve, le caissier
--    rembourse. Le remboursement extourne la part rendue (pièce de vente miroir), remet en stock la marchandise intacte
--    et reprend le vide d'une bouteille consignée. Une seule demande ouverte par vente.
-- 4. Facture normalisée : un retour remboursé reçoit son propre avoir (partiel), rattaché au retour ; l'avoir
--    d'annulation complète reste unique par vente.
--
-- Les commandes en attente (ventes BROUILLON) réservent leur stock sans table dédiée : le réservé se calcule à la
-- volée sur leurs lignes (voir ReservationStockService).
-- ---------------------------------------------------------------------------

-- 1. Boissons sans consigne
ALTER TABLE articles ADD COLUMN sans_consigne BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE articles a SET sans_consigne = TRUE
WHERE a.type = 'BOISSON'
  AND NOT EXISTS (SELECT 1 FROM restaurant_emballages e WHERE e.article_boisson_id = a.id);

COMMENT ON COLUMN articles.sans_consigne IS
    'BOISSON seulement : true = boisson sans consigne (whisky, vin, jus en carton...) dont on ne gère pas les bouteilles vides ; pas de conditionnement, achat et vente sans mouvement de vides.';

-- 2. Pertes d'une boisson sans consigne
CREATE TABLE restaurant_sorties_boissons (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    entrepot_id BIGINT NOT NULL REFERENCES entrepots(id),
    mouvement_stock_id BIGINT NOT NULL UNIQUE REFERENCES mouvements_stock(id) ON DELETE CASCADE,
    quantite NUMERIC(15,3) NOT NULL CHECK (quantite > 0),
    motif VARCHAR(20) NOT NULL
        CONSTRAINT chk_sorties_boissons_motif CHECK (motif IN ('CASSE', 'PERIME', 'CADEAU')),
    precision_motif VARCHAR(500),
    date_sortie DATE NOT NULL,
    -- Coût sorti (quantité × coût moyen), en devise de base comme le grand livre.
    valeur NUMERIC(15,2) NOT NULL DEFAULT 0,
    annulee BOOLEAN NOT NULL DEFAULT FALSE,
    annulee_le TIMESTAMPTZ,
    annulee_par_id BIGINT REFERENCES users(id),
    created_by_id BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sorties_boissons_date ON restaurant_sorties_boissons (date_sortie);

-- 3. Retours de ventes remboursés
CREATE SEQUENCE IF NOT EXISTS seq_retour_vente START WITH 1 INCREMENT BY 1;

CREATE TABLE retours_vente (
    id                   BIGSERIAL     PRIMARY KEY,
    reference            VARCHAR(30)   NOT NULL UNIQUE,
    vente_id             BIGINT        NOT NULL REFERENCES ventes(id),
    statut               VARCHAR(20)   NOT NULL
        CONSTRAINT chk_retours_vente_statut CHECK (statut IN ('DEMANDE', 'APPROUVE', 'REFUSE', 'REMBOURSE', 'ANNULE')),
    motif                VARCHAR(500)  NOT NULL,
    demandeur_id         BIGINT        NOT NULL REFERENCES users(id),
    date_demande         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Accord ou refus du responsable (bar, cuisine ou restaurant).
    approbateur_id       BIGINT        REFERENCES users(id),
    date_decision        TIMESTAMPTZ,
    commentaire_decision VARCHAR(500),
    -- Remboursement par le caissier.
    caissier_id          BIGINT        REFERENCES users(id),
    date_remboursement   DATE,
    canal                VARCHAR(20)
        CONSTRAINT chk_retours_vente_canal CHECK (canal IS NULL OR canal IN ('CAISSE', 'BANQUE', 'MOBILE_MONEY', 'CREANCE')),
    etablissement_id     BIGINT        REFERENCES etablissements_tresorerie(id),
    -- Part rendue, dans la devise de la vente.
    montant_ht           NUMERIC(15,2) NOT NULL DEFAULT 0,
    montant_tva          NUMERIC(15,2) NOT NULL DEFAULT 0,
    montant_ttc          NUMERIC(15,2) NOT NULL DEFAULT 0,
    piece_id             BIGINT        REFERENCES pieces_comptables(id),
    piece_analytique_id  BIGINT        REFERENCES pieces_comptables(id),
    version              BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_retours_vente_vente ON retours_vente (vente_id);
CREATE INDEX idx_retours_vente_statut ON retours_vente (statut);
CREATE UNIQUE INDEX ux_retours_vente_un_ouvert ON retours_vente (vente_id) WHERE statut IN ('DEMANDE', 'APPROUVE');

CREATE TABLE lignes_retour_vente (
    id                 BIGSERIAL     PRIMARY KEY,
    retour_id          BIGINT        NOT NULL REFERENCES retours_vente(id) ON DELETE CASCADE,
    ligne_vente_id     BIGINT        NOT NULL REFERENCES lignes_vente(id),
    quantite           NUMERIC(15,3) NOT NULL CHECK (quantite > 0),
    -- Marchandise intacte remise en stock (bouteille non ouverte) ; un plat servi ne revient pas en stock.
    remise_en_stock    BOOLEAN       NOT NULL DEFAULT TRUE,
    montant_ht         NUMERIC(15,2) NOT NULL DEFAULT 0,
    montant_tva        NUMERIC(15,2) NOT NULL DEFAULT 0,
    -- Entrée en stock du remboursement (une par ligne : chaque article a son propre compte de variation).
    mouvement_stock_id BIGINT        REFERENCES mouvements_stock(id)
);
CREATE INDEX idx_lignes_retour_vente_retour ON lignes_retour_vente (retour_id);
CREATE INDEX idx_lignes_retour_vente_ligne ON lignes_retour_vente (ligne_vente_id);

-- 4. Facture normalisée : avoir partiel d'un retour
ALTER TABLE factures_normalisees ADD COLUMN retour_vente_id BIGINT REFERENCES retours_vente(id);
ALTER TABLE factures_normalisees DROP CONSTRAINT uq_facture_normalisee_vente_type;
CREATE UNIQUE INDEX ux_facture_normalisee_vente_type ON factures_normalisees (vente_id, type) WHERE retour_vente_id IS NULL;
CREATE UNIQUE INDEX ux_facture_normalisee_retour ON factures_normalisees (retour_vente_id) WHERE retour_vente_id IS NOT NULL;
