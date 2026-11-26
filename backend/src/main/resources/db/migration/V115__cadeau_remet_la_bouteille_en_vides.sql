-- Boisson offerte (cadeau) : le contenu est consomme mais la bouteille revient dans le stock de vides, comme pour une vente
-- (TypeMouvementEmballage.CADEAU passe du sens 0 au sens +1). Le compteur de vides et l'historique des mouvements doivent
-- rester coherents : la somme signee des mouvements egale le compteur.

-- 1. Cadeaux deja enregistres et non annules : leurs bouteilles reviennent dans le compteur de vides.
UPDATE restaurant_emballages e
SET bouteilles_vides = e.bouteilles_vides + c.total
FROM (SELECT emballage_id, SUM(quantite_bouteilles) AS total
      FROM restaurant_mouvements_emballage
      WHERE type = 'CADEAU' AND NOT annule
      GROUP BY emballage_id) c
WHERE c.emballage_id = e.id;

-- 2. Cadeaux deja annules : leur annulation n'avait produit aucun mouvement inverse (effet nul a l'epoque). Ils comptent
--    desormais +quantite dans l'historique : le mouvement inverse manquant est ajoute, la somme reste egale au compteur.
INSERT INTO restaurant_mouvements_emballage
    (emballage_id, type, date_mouvement, created_by_id, quantite_bouteilles, motif, annule, annulation_de_id)
SELECT m.emballage_id, 'AJUSTEMENT_MOINS', m.date_mouvement, m.created_by_id, m.quantite_bouteilles,
       'Annulation du mouvement n° ' || m.id || ' (boisson offerte)', FALSE, m.id
FROM restaurant_mouvements_emballage m
WHERE m.type = 'CADEAU' AND m.annule
  AND NOT EXISTS (SELECT 1 FROM restaurant_mouvements_emballage i WHERE i.annulation_de_id = m.id);
