-- Suivi de gestion des lots de stock (date d'achat, fournisseur) pour les
-- boissons et provisions du module Restaurant, et paramètre FIFO/LIFO de
-- l'ordre de sortie.
--
-- Purement informatif : la comptabilité continue d'utiliser le coût moyen
-- pondéré (stock_niveaux) exactement comme avant. Le SYSCOHADA, comme
-- l'IFRS, interdit le LIFO comme méthode de valorisation comptable ; ce
-- suivi par lot n'écrit donc jamais au grand livre.
CREATE TABLE lots_stock (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    entrepot_id BIGINT NOT NULL REFERENCES entrepots(id),
    ligne_mouvement_id BIGINT REFERENCES lignes_mouvement_stock(id) ON DELETE CASCADE,
    date_entree DATE NOT NULL,
    fournisseur VARCHAR(200),
    quantite_initiale NUMERIC(15,3) NOT NULL,
    quantite_restante NUMERIC(15,3) NOT NULL,
    prix_achat_unitaire NUMERIC(15,6) NOT NULL DEFAULT 0,
    prix_transport_unitaire NUMERIC(15,6) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lots_stock_article_entrepot ON lots_stock (article_id, entrepot_id, quantite_restante);
CREATE INDEX idx_lots_stock_ligne_mouvement ON lots_stock (ligne_mouvement_id);

-- Détail de ce qu'une sortie (ou le départ d'un transfert) a prélevé sur
-- chaque lot : permet une annulation exacte, quel que soit l'ordre
-- FIFO/LIFO en vigueur au moment de l'annulation.
CREATE TABLE lots_stock_consommations (
    id BIGSERIAL PRIMARY KEY,
    ligne_mouvement_id BIGINT NOT NULL REFERENCES lignes_mouvement_stock(id) ON DELETE CASCADE,
    lot_id BIGINT NOT NULL REFERENCES lots_stock(id) ON DELETE CASCADE,
    quantite NUMERIC(15,3) NOT NULL
);
CREATE INDEX idx_lots_stock_consommations_ligne ON lots_stock_consommations (ligne_mouvement_id);

ALTER TABLE restaurant_parametres
    ADD COLUMN methode_sortie_lots VARCHAR(10) NOT NULL DEFAULT 'FIFO';

-- Stock existant : un lot d'ouverture par (article, entrepôt), sans
-- fournisseur ni date d'achat connus (ils n'ont jamais été enregistrés).
INSERT INTO lots_stock (article_id, entrepot_id, date_entree, quantite_initiale, quantite_restante,
                         prix_achat_unitaire, prix_transport_unitaire)
SELECT s.article_id, s.entrepot_id, CURRENT_DATE, s.quantite, s.quantite,
       s.valeur_achat / s.quantite, (s.valeur_totale - s.valeur_achat) / s.quantite
FROM stock_niveaux s
JOIN articles a ON a.id = s.article_id
WHERE a.type IN ('BOISSON', 'PROVISION') AND s.quantite > 0;
