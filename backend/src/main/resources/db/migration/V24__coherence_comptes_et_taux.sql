-- =====================================================================
-- Correctifs issus de l'audit comptable mene apres la bascule V23.
--
-- V23 a importe le referentiel SYSCOHADA officiel, mais a attribue le type
-- bilanciel des comptes nouveaux par une regle grossiere sur la racine a
-- deux chiffres. Resultat : des comptes de DETTE classes en ACTIF, qui
-- basculeraient du mauvais cote du bilan des leur premiere ecriture.
-- V23 a par ailleurs rendu non imputable le compte de caisse 571, que le
-- referentiel subdivise, ce qui rendrait une installation neuve inutilisable.
--
-- Aucune ecriture n'est modifiee ici : seuls le typage, l'imputabilite,
-- la piste d'audit devise et les taux manquants sont corriges.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Typage bilanciel de la classe 4 (comptes de tiers)
-- ---------------------------------------------------------------------
-- La classe 4 melange creances et dettes : le type ne peut pas se deduire
-- de la racine a deux chiffres. On applique la nature reelle de chaque
-- sous-compte, telle que definie par le referentiel.
--
-- Les comptes deja mouvementes ne sont pas touches : l'intitule et le sens
-- sous lesquels une ecriture a ete passee doivent rester stables.

-- Dettes classees a tort en ACTIF.
UPDATE comptes_ohada c
SET type = 'PASSIF'
WHERE c.classe = 4
  AND c.type = 'ACTIF'
  AND NOT EXISTS (SELECT 1 FROM grand_livre g WHERE g.compte_id = c.id)
  AND (
        c.numero LIKE '442%'   -- Etat, autres impots et taxes (dette fiscale)
     OR c.numero LIKE '443%'   -- Etat, T.V.A. facturee (TVA collectee = dette)
     OR c.numero LIKE '444%'   -- Etat, T.V.A. due
     OR c.numero LIKE '446%'   -- Etat, autres taxes sur le chiffre d'affaires
     OR c.numero LIKE '447%'   -- Etat, impots retenus a la source
     OR c.numero LIKE '448%'   -- Etat, charges a payer
     OR c.numero LIKE '419%'   -- Clients crediteurs (avances recues)
     OR c.numero LIKE '472%'   -- Versements restant a effectuer sur titres
     OR c.numero LIKE '477%'   -- Produits constates d'avance
     OR c.numero LIKE '479%'   -- Ecarts de conversion - passif
     OR c.numero LIKE '481%'   -- Fournisseurs d'investissements
     OR c.numero LIKE '482%'   -- Fournisseurs d'investissements, effets a payer
     OR c.numero LIKE '484%'   -- Autres dettes hors activites ordinaires
  );

-- Creances classees a tort en PASSIF.
UPDATE comptes_ohada c
SET type = 'ACTIF'
WHERE c.classe = 4
  AND c.type = 'PASSIF'
  AND NOT EXISTS (SELECT 1 FROM grand_livre g WHERE g.compte_id = c.id)
  AND (
        c.numero LIKE '409%'   -- Fournisseurs debiteurs (avances versees)
     OR c.numero LIKE '421%'   -- Personnel, avances et acomptes
     OR c.numero LIKE '445%'   -- Etat, T.V.A. recuperable
     OR c.numero LIKE '467%'   -- Apporteurs, restant du sur capital appele
     OR c.numero LIKE '476%'   -- Charges constatees d'avance
     OR c.numero LIKE '478%'   -- Ecarts de conversion - actif
     OR c.numero LIKE '485%'   -- Creances sur cessions d'immobilisations
     OR c.numero LIKE '488%'   -- Autres creances hors activites ordinaires
  );

-- ---------------------------------------------------------------------
-- 2. Depreciations : soustractives de l'actif, jamais au passif
-- ---------------------------------------------------------------------
-- V23 avait laisse 59 en ACTIF (herite de l'ancien plan) mais classe ses
-- sous-comptes 590-599 en PASSIF : une meme nature d'operation se retrouvait
-- des deux cotes du bilan. Les depreciations des classes 2, 3 et 4 (28, 29,
-- 39, 49) sont uniformement soustractives de l'actif ; on aligne la classe 5.
UPDATE comptes_ohada c
SET type = 'ACTIF'
WHERE c.classe = 5
  AND c.numero LIKE '59%'
  AND c.type = 'PASSIF'
  AND NOT EXISTS (SELECT 1 FROM grand_livre g WHERE g.compte_id = c.id);

-- ---------------------------------------------------------------------
-- 3. Imputabilite du compte de caisse
-- ---------------------------------------------------------------------
-- Le referentiel subdivise 571 en 5711 (monnaie legale) / 5712 (devises),
-- ce qui en fait un compte de regroupement. V23 l'a donc rendu non
-- imputable, et ne l'a restaure que sur les bases portant deja des
-- ecritures de caisse. Sur une installation neuve, le compte de caisse
-- serait definitivement non imputable et toute piece manuelle de caisse
-- refusee.
--
-- L'application ne tient qu'une caisse, en francs congolais : la
-- distinction UML/devises du referentiel n'a pas d'objet ici. On retient
-- donc 571 comme compte de saisie, ce que le referentiel autorise (le
-- niveau de subdivision releve de l'organisation de l'entite).
UPDATE comptes_ohada SET imputable = TRUE WHERE numero = '571';

-- ---------------------------------------------------------------------
-- 4. Taux du jour manquant sur les operations anterieures
-- ---------------------------------------------------------------------
-- V21 n'avait retro-rempli que le grand livre. Les transactions de
-- tresorerie et les ventes anterieures restaient sans taux : elles
-- retombaient sur le taux courant a l'affichage, exactement la derive que
-- le taux fige doit supprimer. Meme regle de resolution que V21 : le taux
-- en vigueur a la date de l'operation.

UPDATE transactions_caisse t
SET taux_journalier = COALESCE(
    (SELECT tc.taux FROM taux_change tc
      WHERE tc.date_effet <= t.date_operation::date
      ORDER BY tc.date_effet DESC, tc.created_at DESC LIMIT 1),
    (SELECT tc.taux FROM taux_change tc ORDER BY tc.date_effet ASC, tc.created_at ASC LIMIT 1))
WHERE t.taux_journalier IS NULL;

UPDATE transactions_bancaires t
SET taux_journalier = COALESCE(
    (SELECT tc.taux FROM taux_change tc
      WHERE tc.date_effet <= t.date_operation::date
      ORDER BY tc.date_effet DESC, tc.created_at DESC LIMIT 1),
    (SELECT tc.taux FROM taux_change tc ORDER BY tc.date_effet ASC, tc.created_at ASC LIMIT 1))
WHERE t.taux_journalier IS NULL;

UPDATE transactions_mobile_money t
SET taux_journalier = COALESCE(
    (SELECT tc.taux FROM taux_change tc
      WHERE tc.date_effet <= t.date_operation::date
      ORDER BY tc.date_effet DESC, tc.created_at DESC LIMIT 1),
    (SELECT tc.taux FROM taux_change tc ORDER BY tc.date_effet ASC, tc.created_at ASC LIMIT 1))
WHERE t.taux_journalier IS NULL;

UPDATE ventes v
SET taux_journalier = COALESCE(
    (SELECT tc.taux FROM taux_change tc
      WHERE tc.date_effet <= v.date_vente
      ORDER BY tc.date_effet DESC, tc.created_at DESC LIMIT 1),
    (SELECT tc.taux FROM taux_change tc ORDER BY tc.date_effet ASC, tc.created_at ASC LIMIT 1))
WHERE v.taux_journalier IS NULL;

-- ---------------------------------------------------------------------
-- 5. Piste d'audit devise : un montant par ligne, pas le total de la piece
-- ---------------------------------------------------------------------
-- EcritureComptableService passait le meme objet de conversion a toutes les
-- lignes d'une piece : le montant en devise du TOTAL etait recopie sur
-- chaque ligne. Sur une note de 50 USD ventilee en 4 lignes, la somme des
-- montants en devise valait 200 USD. Les montants en francs, eux, sont
-- justes : on recalcule donc le montant en devise a partir d'eux.
UPDATE grand_livre
SET montant_devise = ROUND((debit + credit) / taux_applique, 2)
WHERE montant_devise IS NOT NULL
  AND taux_applique IS NOT NULL
  AND taux_applique > 0
  AND ABS((debit + credit) - ROUND(montant_devise * taux_applique, 2)) > 0.01;

-- ---------------------------------------------------------------------
-- 6. Ligne de budget rattachee a un compte retire du referentiel
-- ---------------------------------------------------------------------
-- 611 n'existe pas au referentiel et a ete desactive par V23 ; les lignes
-- de budget qui le visent encore sont ramenees sur 6181, compte vers lequel
-- ses ecritures ont deja ete reaffectees.
UPDATE lignes_budget b
SET compte_id = (SELECT id FROM comptes_ohada WHERE numero = '6181')
WHERE b.compte_id = (SELECT id FROM comptes_ohada WHERE numero = '611')
  AND EXISTS (SELECT 1 FROM comptes_ohada WHERE numero = '6181');

-- Deduplique : le meme compte ne doit pas figurer deux fois dans un budget.
DELETE FROM lignes_budget a
USING lignes_budget b
WHERE a.id > b.id AND a.budget_id = b.budget_id AND a.compte_id = b.compte_id;
