-- ---------------------------------------------------------------------------
-- Un lot de stock se souvient de la ligne de note de frais dont le paiement l'a cree.
--
-- Pourquoi : un retour de fonds (V127) rend au fournisseur une partie d'une livraison. Les boissons sortent en FIFO
-- (la livraison la plus ancienne part d'abord) ; sans ce lien, le retour vidait le plus ancien lot de l'article au lieu
-- du lot de l'achat retourne : la page « Stock cuisine & bar » montrait la livraison retournee inchangee (60 bouteilles
-- au lieu de 20) pendant qu'un lot plus ancien perdait les 40 bouteilles.
--
-- Suivi de gestion seulement : aucune incidence comptable (le cout moyen pondere reste la seule valorisation du grand
-- livre), aucune ecriture. NULL pour un lot qui ne vient pas du paiement d'une note (reception directe de provision,
-- lot d'ouverture, arrivee d'un transfert...).
-- ---------------------------------------------------------------------------

ALTER TABLE lots_stock ADD COLUMN ligne_note_frais_id BIGINT REFERENCES lignes_note_frais(id) ON DELETE SET NULL;
CREATE INDEX idx_lots_stock_ligne_note_frais ON lots_stock (ligne_note_frais_id) WHERE ligne_note_frais_id IS NOT NULL;

-- Lots deja en place : rattaches a leur ligne de note quand le lien se deduit sans ambiguite. Le lot porte le
-- beneficiaire de la note (fournisseur), l'article, l'entrepot et la quantite payee ; la ligne les porte aussi, la
-- quantite d'origine etant celle d'avant la premiere reduction validee si la ligne a ete reduite depuis. Le lien n'est
-- pose que si UN SEUL lot correspond a la ligne ET une seule ligne au lot : au moindre doute le lot reste sans lien
-- (et se videra alors comme avant, selon sa methode FIFO ou prorata).
WITH lignes_payees AS (
    SELECT ln.id AS ligne_id, ln.article_id, ln.entrepot_id, nf.beneficiaire,
           COALESCE(
               (SELECT lr.ancienne_quantite
                  FROM lignes_retour_fonds lr
                  JOIN retours_fonds_note r ON r.id = lr.retour_id AND r.statut = 'VALIDE'
                 WHERE lr.ligne_note_id = ln.id AND lr.ancienne_quantite IS NOT NULL
                 ORDER BY r.id
                 LIMIT 1),
               ln.quantite_marchandise) AS quantite_payee
    FROM lignes_note_frais ln
    JOIN notes_frais nf ON nf.id = ln.note_id
    WHERE ln.achat_marchandise
      AND nf.statut = 'PAYEE'
      AND nf.beneficiaire IS NOT NULL
      AND ln.article_id IS NOT NULL
      AND ln.entrepot_id IS NOT NULL
),
couples AS (
    SELECT lot.id AS lot_id, lp.ligne_id
    FROM lots_stock lot
    JOIN lignes_mouvement_stock lm ON lm.id = lot.ligne_mouvement_id
    JOIN mouvements_stock m ON m.id = lm.mouvement_id AND m.type = 'ENTREE'
    JOIN lignes_payees lp ON lp.article_id = lot.article_id
                         AND lp.entrepot_id = lot.entrepot_id
                         AND lp.beneficiaire = lot.fournisseur
                         AND lp.quantite_payee = lot.quantite_initiale
    WHERE lot.ligne_note_frais_id IS NULL
)
UPDATE lots_stock lot
   SET ligne_note_frais_id = c.ligne_id
  FROM couples c
 WHERE c.lot_id = lot.id
   AND (SELECT count(*) FROM couples x WHERE x.lot_id = c.lot_id) = 1
   AND (SELECT count(*) FROM couples x WHERE x.ligne_id = c.ligne_id) = 1;
