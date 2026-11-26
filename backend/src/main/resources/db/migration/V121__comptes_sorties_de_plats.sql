-- Un compte par motif de sortie de plat.
--
-- Jusqu'ici, toute sortie de plat hors vente (ecran Sorties de plats) etait ecrite comme la part « cout » d'une vente :
-- D variation des stocks de produits finis du plat (736x) / C son compte de stock (361x). Un plat offert, un plat perime, un
-- plat renverse ou un repas du personnel se confondaient avec la sortie des plats vendus : le grand livre ne disait nulle part
-- ce que les pertes avaient coute, motif par motif. Chaque motif a desormais son compte (voir
-- RestaurantService.compteSortiePlat), comme les boissons offertes (6276.1) et perimees (6031.2) depuis V120 :
--
--   motif                  compte  libelle                          sous
--   Offert (cadeau)        6276.2  Plats offerts a la clientele     6276 Cadeaux a la clientele (relations publiques)
--   Repas du personnel     6617.1  Repas du personnel               6617 Avantages en nature (charges de personnel)
--   Perime                 736.1   Plats perimes                    736 Variations des stocks de produits finis
--   Moisi / avarie         736.2   Plats moisis ou avaries          736
--   Renverse / tombe       736.3   Plats renverses ou tombes        736
--   Brule / rate           736.4   Plats brules ou rates            736
--   Autre                  736.5   Autres sorties de plats          736
--
-- Les pertes de stock restent dans la variation des stocks de produits finis (en inventaire permanent, les sorties de stock
-- de produits finis vont en 736, comme celles des marchandises en 603 : voir 6031.2), isolees de la sortie des plats vendus,
-- si bien que la ligne « variation des stocks de produits finis » des etats financiers reste egale a la variation reelle des
-- stocks. Un cadeau et un repas du personnel sont, eux, des charges par nature : le resultat est le meme, mais elles
-- apparaissent sous leur nom (relations publiques, avantages en nature). Seule la sortie d'un plat vendu passe par le compte
-- de variation du plat (736x).
--
-- Meme principe que V67 et V120 : idempotent (NOT EXISTS sur le numero), type et classe herites du parent, manuel = true
-- (modifiable et desactivable depuis l'ecran Plan comptable), avec leur fiche de documentation : sans elle, la redaction
-- automatique du demarrage leur donnerait la fiche generique d'un compte « ajoute par l'entreprise, date et auteur inconnus ».
--
-- Les sorties deja enregistrees (ex. PC-2026-000105, un plat offert : D 7361 / C 3611) gardent leur ecriture : une piece passee
-- ne se reecrit pas. Pour la ressaisir sur le compte de son motif : annuler la sortie (Sorties de plats) puis la saisir de nouveau.

INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel, parent_id,
                           contenu, fonctionnement, exclusions, controle, commentaires)
SELECT v.numero, v.libelle, p.type, p.classe, true, true, true, p.id,
       v.contenu, v.fonctionnement, v.exclusions, v.controle,
       v.origine || E'\n' || 'Il hérite du type (' || p.type || ') et de la classe (' || p.classe
           || ') de son compte parent ' || p.numero || '.' || E'\n'
           || 'Fiche rédigée automatiquement par l''application à la création du compte.'
FROM (VALUES
    ('6276', '6276.2', 'Plats offerts à la clientèle',
     'Sous-compte de 6276 « Cadeaux à la clientèle » : coût de production, au coût moyen, des plats offerts à la '
         || 'clientèle (geste commercial, plat offert à une table, cadeau de la direction), enregistrés avec le motif '
         || '« Offert (cadeau) » à l''écran Sorties de plats du restaurant. Un plat offert n''est pas un plat vendu : '
         || 'c''est une charge de relations publiques (services extérieurs), qui ne se mêle plus à la sortie de stock '
         || 'des plats vendus (variation des stocks de produits finis 736x).',
     'Débité par l''application à chaque plat offert, du coût de production moyen des portions (contrepartie : compte '
         || 'de stock du plat 361x, au journal STOCK).' || E'\n'
         || 'Crédité seulement par la contre-passation d''une sortie de plat « Offert (cadeau) » annulée.',
     'Les plats vendus et les autres sorties de plats (variation des stocks de produits finis 736x du plat, 736.1 à '
         || '736.5, repas du personnel 6617.1) n''y vont pas. Les boissons offertes vont en 6276.1.',
     'Rapprocher des sorties de plats de motif « Offert (cadeau) » non annulées de l''écran Sorties de plats (pièce du '
         || 'journal STOCK) et de leur précision (bénéficiaire, autorisation).',
     'Compte créé par la mise à jour V121 de l''application ; elle y impute d''office les plats offerts : ne le '
         || 'supprimez pas, son libellé peut être modifié.'),
    ('6617', '6617.1', 'Repas du personnel',
     'Sous-compte de 6617 « Avantages en nature » : coût de production, au coût moyen, des plats du restaurant '
         || 'consommés par le personnel, enregistrés avec le motif « Repas du personnel » à l''écran Sorties de plats. '
         || 'Un repas servi gratuitement aux employés est un avantage en nature, donc une charge de personnel, et non '
         || 'le coût d''un plat vendu.',
     'Débité par l''application à chaque repas du personnel, du coût de production moyen des portions (contrepartie : '
         || 'compte de stock du plat 361x, au journal STOCK).' || E'\n'
         || 'Crédité seulement par la contre-passation d''une sortie de plat « Repas du personnel » annulée.',
     'Les plats vendus au personnel (vente du restaurant), les plats offerts à la clientèle (6276.2) et les pertes de '
         || 'plats (736.1 à 736.5) n''y vont pas. Les autres avantages en nature (logement, véhicule...) gardent leur '
         || 'propre sous-compte de 6617.',
     'Rapprocher des sorties de plats de motif « Repas du personnel » non annulées de l''écran Sorties de plats (pièce '
         || 'du journal STOCK) ; l''avantage en nature est à déclarer selon la réglementation sociale et fiscale de '
         || 'l''entreprise.',
     'Compte créé par la mise à jour V121 de l''application ; elle y impute d''office les repas du personnel : ne le '
         || 'supprimez pas, son libellé peut être modifié.'),
    ('736', '736.1', 'Plats périmés',
     'Sous-compte de 736 « Variations des stocks de produits finis » : coût de production, au coût moyen, des plats '
         || 'périmés sortis du stock (date d''expiration de leur production dépassée), enregistrés avec le motif « Périmé » '
         || 'à l''écran Sorties de plats du restaurant, y compris par le retrait des plats périmés (manuel ou '
         || 'automatique). En inventaire permanent, les sorties de stock de produits finis vont en 736 : la péremption y '
         || 'reste, mais à part de la sortie des plats vendus.',
     'Débité par l''application à chaque plat périmé retiré du stock, du coût de production moyen des portions '
         || '(contrepartie : compte de stock du plat 361x, au journal STOCK).' || E'\n'
         || 'Crédité seulement par la contre-passation d''une sortie de plat « Périmé » annulée.',
     'Les plats vendus (variation des stocks de produits finis de chaque plat, 736x), les plats moisis ou avariés '
         || '(736.2) et les boissons périmées (6031.2) n''y vont pas.',
     'Rapprocher des sorties de plats de motif « Périmé » non annulées (écran Sorties de plats, pièce du journal '
         || 'STOCK) de la date d''expiration de la production concernée.',
     'Compte créé par la mise à jour V121 de l''application ; elle y impute d''office les plats périmés : ne le '
         || 'supprimez pas, son libellé peut être modifié.'),
    ('736', '736.2', 'Plats moisis ou avariés',
     'Sous-compte de 736 « Variations des stocks de produits finis » : coût de production, au coût moyen, des plats '
         || 'moisis ou avariés avant leur date d''expiration (conservation défaillante, chaîne du froid rompue, '
         || 'contamination), enregistrés avec le motif « Moisi / avarié » à l''écran Sorties de plats du restaurant.',
     'Débité par l''application à chaque plat moisi ou avarié sorti du stock, du coût de production moyen des portions '
         || '(contrepartie : compte de stock du plat 361x, au journal STOCK).' || E'\n'
         || 'Crédité seulement par la contre-passation d''une sortie de plat « Moisi / avarié » annulée.',
     'Les plats vendus (736x), les plats périmés à leur date d''expiration (736.1) et les plats renversés ou brûlés '
         || '(736.3, 736.4) n''y vont pas.',
     'Rapprocher des sorties de plats de motif « Moisi / avarié » non annulées de l''écran Sorties de plats (pièce du '
         || 'journal STOCK) ; la précision indique la cause (stockage, réfrigération...).',
     'Compte créé par la mise à jour V121 de l''application ; elle y impute d''office les plats moisis ou avariés : ne '
         || 'le supprimez pas, son libellé peut être modifié.'),
    ('736', '736.3', 'Plats renversés ou tombés',
     'Sous-compte de 736 « Variations des stocks de produits finis » : coût de production, au coût moyen, des plats '
         || 'renversés ou tombés (accident de service ou de manutention), enregistrés avec le motif « Renversé / tombé » '
         || 'à l''écran Sorties de plats du restaurant.',
     'Débité par l''application à chaque plat renversé ou tombé, du coût de production moyen des portions (contrepartie : '
         || 'compte de stock du plat 361x, au journal STOCK).' || E'\n'
         || 'Crédité seulement par la contre-passation d''une sortie de plat « Renversé / tombé » annulée.',
     'Les plats vendus (736x), les plats périmés, moisis ou brûlés (736.1, 736.2, 736.4) n''y vont pas.',
     'Rapprocher des sorties de plats de motif « Renversé / tombé » non annulées de l''écran Sorties de plats (pièce du '
         || 'journal STOCK) ; la précision indique les circonstances (plateau renversé en salle...).',
     'Compte créé par la mise à jour V121 de l''application ; elle y impute d''office les plats renversés ou tombés : ne '
         || 'le supprimez pas, son libellé peut être modifié.'),
    ('736', '736.4', 'Plats brûlés ou ratés',
     'Sous-compte de 736 « Variations des stocks de produits finis » : coût de production, au coût moyen, des plats '
         || 'brûlés ou ratés en cuisine (erreur de cuisson ou de préparation), enregistrés avec le motif « Brûlé / raté » '
         || 'à l''écran Sorties de plats du restaurant.',
     'Débité par l''application à chaque plat brûlé ou raté, du coût de production moyen des portions (contrepartie : '
         || 'compte de stock du plat 361x, au journal STOCK).' || E'\n'
         || 'Crédité seulement par la contre-passation d''une sortie de plat « Brûlé / raté » annulée.',
     'Les plats vendus (736x), les plats périmés, moisis ou renversés (736.1, 736.2, 736.3) n''y vont pas.',
     'Rapprocher des sorties de plats de motif « Brûlé / raté » non annulées de l''écran Sorties de plats (pièce du '
         || 'journal STOCK) ; la précision indique la cause. Une fréquence élevée signale un problème de production à '
         || 'corriger.',
     'Compte créé par la mise à jour V121 de l''application ; elle y impute d''office les plats brûlés ou ratés : ne le '
         || 'supprimez pas, son libellé peut être modifié.'),
    ('736', '736.5', 'Autres sorties de plats',
     'Sous-compte de 736 « Variations des stocks de produits finis » : coût de production, au coût moyen, des plats '
         || 'sortis du stock pour un motif qui n''est pas l''un des autres, enregistrés avec le motif « Autre » (la '
         || 'précision est alors obligatoire) à l''écran Sorties de plats du restaurant.',
     'Débité par l''application à chaque sortie de plat de motif « Autre », du coût de production moyen des portions '
         || '(contrepartie : compte de stock du plat 361x, au journal STOCK).' || E'\n'
         || 'Crédité seulement par la contre-passation d''une sortie de plat « Autre » annulée.',
     'Les plats vendus (736x) et les sorties qui ont leur propre motif et leur propre compte (736.1 à 736.4, 6276.2, '
         || '6617.1) n''y vont pas.',
     'Lire la précision de chaque sortie de plat de motif « Autre » (écran Sorties de plats, pièce du journal STOCK) : '
         || 'un motif qui revient souvent mérite son propre compte.',
     'Compte créé par la mise à jour V121 de l''application ; elle y impute d''office les sorties de plats de motif '
         || '« Autre » : ne le supprimez pas, son libellé peut être modifié.')
) AS v(parent, numero, libelle, contenu, fonctionnement, exclusions, controle, origine)
JOIN comptes_ohada p ON p.numero = v.parent
WHERE NOT EXISTS (SELECT 1 FROM comptes_ohada c WHERE c.numero = v.numero);

-- Fiches des comptes propres a chaque plat (voir DocumentationCompteService) : celle du compte de variation (736x) disait le
-- compte debite de toutes les sorties, celle du compte de stock (361x) des « repas offerts ». Seules les phrases redigees par
-- l'application sont corrigees ; une fiche retouchee a la main n'est pas touchee.
UPDATE comptes_ohada c
SET fonctionnement = replace(c.fonctionnement,
        'Débité à chaque sortie (vente, perte, repas offert) au coût de production moyen.',
        'Débité à chaque vente, au coût de production moyen. Les autres sorties de plats (périmé, moisi, renversé, brûlé, '
            || 'offert, repas du personnel, autre) vont à leur propre compte (736.1 à 736.5, 6276.2 et 6617.1), pas ici.')
WHERE c.parent_id = (SELECT p.id FROM comptes_ohada p WHERE p.numero = '736')
  AND c.fonctionnement LIKE '%Débité à chaque sortie (vente, perte, repas offert) au coût de production moyen.%';

UPDATE comptes_ohada c
SET fonctionnement = replace(c.fonctionnement,
        'Crédité à chaque sortie (vente, perte, repas offert) au coût de production moyen.',
        'Crédité à chaque sortie (vente, perte, plat offert, repas du personnel) au coût de production moyen.')
WHERE c.parent_id = (SELECT p.id FROM comptes_ohada p WHERE p.numero = '361')
  AND c.fonctionnement LIKE '%Crédité à chaque sortie (vente, perte, repas offert) au coût de production moyen.%';
