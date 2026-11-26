-- Comptes d'écarts de change sur créances et dettes commerciales.
--
-- Le SYSCOHADA révisé (2017) range les écarts de change réalisés sur les
-- ventes, les achats et les notes de frais dans le résultat d'exploitation :
-- 656 (perte, une charge) et 756 (gain). Les comptes 676 et 776 sont
-- réservés aux opérations financières (emprunts, prêts, placements). Le
-- référentiel chargé par V23 ne contenait ni 656 ni 756 : les écarts des
-- notes de frais (EcartChangeService) et des ventes à crédit (VenteService)
-- partaient donc en 676/776. Ils vont désormais en 656/756. 676 et 776
-- n'ayant jamais été mouvementés, il n'y a rien à reclasser.
--
-- Les états financiers rangent les comptes par préfixe : 656 tombe dans
-- « Autres charges » (65), 756 dans « Autres produits » (75).

INSERT INTO comptes_ohada (numero, libelle, type, classe, parent_id, manuel, imputable, actif,
                           contenu, commentaires, fonctionnement, exclusions, controle)
SELECT '656', 'Pertes de change sur créances et dettes commerciales', 'CHARGE', 6, p.id, FALSE, TRUE, TRUE,
       'Le compte 656 enregistre les pertes de change réalisées sur les créances et dettes commerciales libellées en monnaie étrangère — ventes aux clients, achats auprès des fournisseurs, notes de frais — lorsque le taux retenu au règlement est défavorable par rapport à celui retenu à l''origine (facturation ou engagement de la dépense).',
       'Depuis la révision du SYSCOHADA (2017), ces écarts relèvent du résultat d''exploitation et non plus du résultat financier : le compte 676 est réservé aux pertes de change sur opérations financières. L''application les constate automatiquement au règlement d''une note de frais ou d''une créance client, par une pièce distincte libellée « Écart de change sur ... » ou dans la pièce de règlement de la créance.',
       'Le compte est débité de l''écart défavorable constaté au règlement, par le crédit du compte de charge, de tiers ou de trésorerie concerné. Il est crédité pour solde à la clôture de l''exercice par le débit du compte 13 – Résultat net de l''exercice.',
       'Ne relèvent pas du compte 656 : les pertes de change sur opérations financières (emprunts, prêts, placements), à porter au compte 676 ; les écarts de conversion latents constatés à la clôture sur des créances et dettes non encore réglées, qui figurent aux comptes 478 et 479.',
       'Rapprocher chaque écart des taux appliqués à l''engagement (ou à la facturation) et au règlement de l''opération concernée.'
FROM comptes_ohada p
WHERE p.numero = '65'
  AND NOT EXISTS (SELECT 1 FROM comptes_ohada WHERE numero = '656');

INSERT INTO comptes_ohada (numero, libelle, type, classe, parent_id, manuel, imputable, actif,
                           contenu, commentaires, fonctionnement, exclusions, controle)
SELECT '756', 'Gains de change sur créances et dettes commerciales', 'PRODUIT', 7, p.id, FALSE, TRUE, TRUE,
       'Le compte 756 enregistre les gains de change réalisés sur les créances et dettes commerciales libellées en monnaie étrangère — ventes aux clients, achats auprès des fournisseurs, notes de frais — lorsque le taux retenu au règlement est favorable par rapport à celui retenu à l''origine (facturation ou engagement de la dépense).',
       'Depuis la révision du SYSCOHADA (2017), ces écarts relèvent du résultat d''exploitation et non plus du résultat financier : le compte 776 est réservé aux gains de change sur opérations financières. L''application les constate automatiquement au règlement d''une note de frais ou d''une créance client, par une pièce distincte libellée « Écart de change sur ... » ou dans la pièce de règlement de la créance.',
       'Le compte est crédité de l''écart favorable constaté au règlement, par le débit du compte de charge, de tiers ou de trésorerie concerné. Il est débité pour solde à la clôture de l''exercice par le crédit du compte 13 – Résultat net de l''exercice.',
       'Ne relèvent pas du compte 756 : les gains de change sur opérations financières (emprunts, prêts, placements), à porter au compte 776 ; les écarts de conversion latents constatés à la clôture sur des créances et dettes non encore réglées, qui figurent aux comptes 478 et 479 et ne constituent pas un produit acquis.',
       'Rapprocher chaque écart des taux appliqués à l''engagement (ou à la facturation) et au règlement de l''opération concernée.'
FROM comptes_ohada p
WHERE p.numero = '75'
  AND NOT EXISTS (SELECT 1 FROM comptes_ohada WHERE numero = '756');

-- 676/776 : intitulés du SYSCOHADA révisé, pour ne plus les confondre avec
-- 656/756. Seulement s'ils portent encore l'intitulé d'origine (V23) : un
-- intitulé retouché par l'administrateur n'est pas écrasé.
UPDATE comptes_ohada
SET libelle = 'Pertes de change financières',
    exclusions = concat_ws(' ', nullif(exclusions, ''),
        'Ne relèvent pas non plus du compte 676 les pertes de change sur créances et dettes commerciales (ventes, achats, notes de frais) : elles sont portées au compte 656.')
WHERE numero = '676' AND libelle = 'Pertes de change';

UPDATE comptes_ohada
SET libelle = 'Gains de change financiers',
    exclusions = concat_ws(' ', nullif(exclusions, ''),
        'Ne relèvent pas non plus du compte 776 les gains de change sur créances et dettes commerciales (ventes, achats, notes de frais) : ils sont portés au compte 756.')
WHERE numero = '776' AND libelle = 'Gains de change';
