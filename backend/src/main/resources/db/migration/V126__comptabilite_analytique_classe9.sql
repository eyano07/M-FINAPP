-- =====================================================================
-- V126 : Comptabilite analytique de gestion (CAGE) - SYSCOHADA revise
-- =====================================================================
-- La classe 9 du SYSCOHADA revise regroupe les engagements hors bilan
-- (90, deja charges par V23) et les comptes de la comptabilite analytique
-- de gestion 92 a 99. Pour ces derniers, le referentiel ne fixe qu'un
-- cadre a deux chiffres « laisse a l'initiative de chaque entite » : on
-- charge donc les huit rubriques officielles, que l'entite subdivise
-- ensuite depuis la page Comptabilite analytique (ex. 9411 Cout atelier A).
--
-- type NULL : comme les 90xx, ces comptes ne relevent ni du bilan ni du
-- compte de resultat et sont donc ignores par ces etats.

INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel, contenu) VALUES
    ('92', 'Comptes réfléchis', NULL, 9, TRUE, TRUE, FALSE,
     'Contrepartie, en comptabilité analytique de gestion, des charges et produits de la comptabilité générale (classes 6 et 7) et des stocks (classe 3) repris pour le calcul des coûts.'),
    ('93', 'Comptes de reclassements', NULL, 9, TRUE, TRUE, FALSE,
     'Reclassement des charges et produits par destination (fonctions, sections, centres d''analyse) avant leur imputation aux coûts.'),
    ('94', 'Comptes de coûts', NULL, 9, TRUE, TRUE, FALSE,
     'Coûts d''achat, de production, de distribution et de revient, subdivisés selon les produits, activités ou commandes de l''entité.'),
    ('95', 'Comptes de stocks', NULL, 9, TRUE, TRUE, FALSE,
     'Inventaire permanent des stocks (matières, en-cours, produits) valorisés aux coûts calculés en comptabilité analytique.'),
    ('96', 'Comptes d''écarts sur coûts préétablis', NULL, 9, TRUE, TRUE, FALSE,
     'Écarts entre coûts réels et coûts préétablis (standards ou budgétés) : écarts sur quantités, sur prix, sur rendement.'),
    ('97', 'Comptes de différences de traitement comptable', NULL, 9, TRUE, TRUE, FALSE,
     'Différences d''incorporation entre comptabilité générale et comptabilité analytique : charges non incorporables, charges supplétives, différences d''inventaire.'),
    ('98', 'Comptes de résultats', NULL, 9, TRUE, TRUE, FALSE,
     'Résultats analytiques par produit, activité ou centre de profit, et rapprochement avec le résultat de la comptabilité générale.'),
    ('99', 'Comptes de liaisons internes', NULL, 9, TRUE, TRUE, FALSE,
     'Liaisons entre établissements ou entre comptabilité générale et comptabilité analytique tenues de manière autonome.')
ON CONFLICT (numero) DO NOTHING;

-- Rattachement hierarchique (plus long prefixe strict existant, regle de V23).
UPDATE comptes_ohada c
SET parent_id = (
    SELECT p.id FROM comptes_ohada p
    WHERE p.numero <> c.numero
      AND c.numero LIKE p.numero || '%'
    ORDER BY length(p.numero) DESC
    LIMIT 1
)
WHERE c.classe = 9 AND c.numero >= '92' AND c.numero < '9A';
