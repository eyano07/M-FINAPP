-- Comptes des boissons offertes a la clientele et des boissons perimees.
--
-- Jusqu'ici, une boisson offerte (mouvement de vides « cadeau ») ou perimee sortait du stock avec l'ecriture d'une vente :
-- D compte de variation de la boisson (6031x) / C son compte de stock (3111x). L'offre gracieuse et la peremption se
-- confondaient ainsi avec le cout des boissons vendues. Elles ont desormais leur compte (voir
-- RestaurantService.compteChargePerte) :
--   * 6276.1 « Boissons offertes a la clientele », sous 6276 « Cadeaux a la clientele » : une boisson offerte est une
--     charge de relations publiques (services exterieurs), pas le cout d'une marchandise vendue ;
--   * 6031.2 « Boissons perimees », sous 6031 « Variations des stocks de marchandises » : en inventaire permanent, les
--     sorties et manquants de stock vont en 603 ; la peremption reste une charge ordinaire (pas H.A.O.), isolee au grand
--     livre sans quitter la variation des stocks. Le 6031.1 est deja pris par les minerais (V67) : il ne faut surtout pas
--     le reutiliser, les boissons perimees seraient passees en variation des stocks de minerais.
-- La casse d'une bouteille pleine garde le compte de variation de la boisson, comme une vente.
--
-- Meme principe que V67 : idempotent (NOT EXISTS sur le numero), type et classe herites du parent, manuel = true
-- (modifiable et desactivable depuis l'ecran Plan comptable). Chaque compte recoit sa fiche de documentation : sans elle,
-- la redaction automatique du demarrage (DocumentationCompteService.documenterComptesExistants) lui donnerait la fiche
-- generique d'un compte « ajoute par l'entreprise, date et auteur inconnus ».
--
-- Les pertes deja enregistrees gardent leurs ecritures (6031x) : une piece passee ne se reecrit pas. Un reclassement,
-- s'il est voulu, se fait par une operation diverse.

INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel, parent_id,
                           contenu, fonctionnement, exclusions, controle, commentaires)
SELECT v.numero, v.libelle, p.type, p.classe, true, true, true, p.id,
       v.contenu, v.fonctionnement, v.exclusions, v.controle,
       v.origine || E'\n' || 'Il hérite du type (' || p.type || ') et de la classe (' || p.classe
           || ') de son compte parent ' || p.numero || '.' || E'\n'
           || 'Fiche rédigée automatiquement par l''application à la création du compte.'
FROM (VALUES
    ('6276', '6276.1', 'Boissons offertes à la clientèle',
     'Sous-compte de 6276 « Cadeaux à la clientèle » : coût, au coût moyen pondéré, des boissons offertes à la clientèle '
         || '(promotion, geste commercial), enregistrées comme « Boisson offerte (cadeau) » dans les mouvements de '
         || 'bouteilles vides du restaurant. Une boisson offerte n''est pas une boisson vendue : c''est une charge de '
         || 'relations publiques (services extérieurs), qui ne se mêle plus au coût des boissons vendues.',
     'Débité par l''application à chaque boisson offerte, du coût moyen pondéré des bouteilles (contrepartie : compte '
         || 'de stock de la boisson 3111x, au journal STOCK).' || E'\n'
         || 'Crédité seulement par la contre-passation d''une boisson offerte annulée.',
     'Les boissons vendues et la casse d''une bouteille pleine (variation des stocks 6031x de chaque boisson) et les '
         || 'boissons périmées (6031.2) n''y vont pas. Les cadeaux à la clientèle autres que des boissons du restaurant '
         || 'vont en 6276.',
     'Rapprocher des mouvements « Boisson offerte (cadeau) » non annulés de la page Mouvements des bouteilles vides '
         || '(pièce du journal STOCK) et de leur motif.',
     'Compte créé par la mise à jour V120 de l''application ; elle y impute d''office les boissons offertes : ne le '
         || 'supprimez pas, son libellé peut être modifié.'),
    ('6031', '6031.2', 'Boissons périmées',
     'Sous-compte de 6031 « Variations des stocks de marchandises » : coût, au coût moyen pondéré, des boissons '
         || 'périmées jetées, enregistrées comme « Boisson périmée » dans les mouvements de bouteilles vides du '
         || 'restaurant. En inventaire permanent, les sorties et les manquants de stock vont en 603 : la péremption y '
         || 'reste, comme charge ordinaire (et non hors activités ordinaires), mais à part de la variation des stocks '
         || 'de chaque boisson.',
     'Débité par l''application à chaque boisson périmée, du coût moyen pondéré des bouteilles jetées (contrepartie : '
         || 'compte de stock de la boisson 3111x, au journal STOCK).' || E'\n'
         || 'Crédité seulement par la contre-passation d''une péremption annulée.',
     'Les boissons vendues et la casse d''une bouteille pleine (variation des stocks 6031x de chaque boisson), les '
         || 'boissons offertes (6276.1), les minerais (6031.1) et les plats périmés (variation des stocks de produits '
         || 'finis 736x) n''y vont pas.',
     'Rapprocher des mouvements « Boisson périmée » non annulés de la page Mouvements des bouteilles vides (pièce du '
         || 'journal STOCK), justifiés par un constat de péremption (date limite dépassée).',
     'Compte créé par la mise à jour V120 de l''application ; elle y impute d''office les boissons périmées : ne le '
         || 'supprimez pas, son libellé peut être modifié.')
) AS v(parent, numero, libelle, contenu, fonctionnement, exclusions, controle, origine)
JOIN comptes_ohada p ON p.numero = v.parent
WHERE NOT EXISTS (SELECT 1 FROM comptes_ohada c WHERE c.numero = v.numero);

-- Fiches des comptes de variation propres a chaque boisson (6031x, voir DocumentationCompteService) : elles disaient ces
-- comptes debites de la peremption et des cadeaux. Seules les phrases redigees par l'application sont corrigees ; une
-- fiche retouchee a la main n'est pas touchee.
UPDATE comptes_ohada c
SET fonctionnement = replace(c.fonctionnement,
        'Débité à chaque sortie (vente, casse, péremption, cadeau) au coût moyen pondéré.',
        'Débité à chaque vente et à chaque casse d''une bouteille pleine, au coût moyen pondéré. Une boisson périmée est '
            || 'débitée en 6031.2, une boisson offerte en 6276.1.')
WHERE c.parent_id = (SELECT p.id FROM comptes_ohada p WHERE p.numero = '6031')
  AND c.fonctionnement LIKE '%Débité à chaque sortie (vente, casse, péremption, cadeau) au coût moyen pondéré.%';

UPDATE comptes_ohada c
SET contenu = replace(c.contenu,
        'il forme le coût d''achat des bouteilles vendues ou perdues sur la période.',
        'il forme le coût d''achat des bouteilles vendues ou cassées sur la période (les boissons périmées vont en '
            || '6031.2, les boissons offertes en 6276.1).')
WHERE c.parent_id = (SELECT p.id FROM comptes_ohada p WHERE p.numero = '6031')
  AND c.contenu LIKE '%il forme le coût d''achat des bouteilles vendues ou perdues sur la période.%';
