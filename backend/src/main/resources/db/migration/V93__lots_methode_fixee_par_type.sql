-- La répartition des sorties sur les lots de stock n'est plus un paramètre :
-- elle est fixée par type d'article (voir LotStockService.methode).
--   - Boissons : FIFO. Une bouteille est une unité physique ; la plus
--     ancienne livraison part d'abord, et les lots restent en bouteilles
--     entières.
--   - Provisions : CMP. Riz, huile, épices se mélangent en cuisine ; chaque
--     sortie prélève sur tous les lots au prorata.
-- Suivi de gestion seulement : la comptabilité reste au coût moyen pondéré.

-- 1. Les sorties de boissons passées en CMP tant que c'était le réglage ont
--    pu être coupées sur plusieurs lots (0,878 bouteille ici, 2,122 là).
--    On les rejoue en FIFO : chaque lot récupère ce qu'une telle sortie lui
--    avait pris, puis la même quantité est reprise au lot le plus ancien
--    d'abord. Le total par article reste le même, les lots redeviennent des
--    bouteilles entières. Seules les sorties VALIDE (une sortie annulée a
--    déjà rendu ses bouteilles à ses lots) ; jamais un transfert, dont les
--    parts ont créé des lots à l'arrivée.
DO $$
DECLARE
    sortie RECORD;
    lot RECORD;
    total NUMERIC(15,3);
    reste NUMERIC(15,3);
    pris NUMERIC(15,3);
BEGIN
    FOR sortie IN
        SELECT c.ligne_mouvement_id, l.article_id, l.entrepot_id, min(c.id) AS premiere
        FROM lots_stock_consommations c
        JOIN lots_stock l ON l.id = c.lot_id
        JOIN articles a ON a.id = l.article_id
        JOIN lignes_mouvement_stock lm ON lm.id = c.ligne_mouvement_id
        JOIN mouvements_stock m ON m.id = lm.mouvement_id
        WHERE a.type = 'BOISSON' AND m.type = 'SORTIE' AND m.statut = 'VALIDE'
        GROUP BY c.ligne_mouvement_id, l.article_id, l.entrepot_id
        HAVING count(*) > 1
        ORDER BY premiere
    LOOP
        UPDATE lots_stock l
        SET quantite_restante = l.quantite_restante + c.quantite
        FROM lots_stock_consommations c
        WHERE c.lot_id = l.id AND c.ligne_mouvement_id = sortie.ligne_mouvement_id;

        SELECT sum(quantite) INTO total FROM lots_stock_consommations
        WHERE ligne_mouvement_id = sortie.ligne_mouvement_id;
        DELETE FROM lots_stock_consommations WHERE ligne_mouvement_id = sortie.ligne_mouvement_id;

        reste := total;
        FOR lot IN
            SELECT id, quantite_restante FROM lots_stock
            WHERE article_id = sortie.article_id AND entrepot_id = sortie.entrepot_id AND quantite_restante > 0
            ORDER BY date_entree, id
        LOOP
            EXIT WHEN reste <= 0;
            pris := least(lot.quantite_restante, reste);
            UPDATE lots_stock SET quantite_restante = quantite_restante - pris WHERE id = lot.id;
            INSERT INTO lots_stock_consommations (ligne_mouvement_id, lot_id, quantite)
            VALUES (sortie.ligne_mouvement_id, lot.id, pris);
            reste := reste - pris;
        END LOOP;
    END LOOP;
END $$;

-- 2. Le paramètre disparaît (sa contrainte CHECK, posée par V90, avec lui).
ALTER TABLE restaurant_parametres DROP COLUMN methode_sortie_lots;
