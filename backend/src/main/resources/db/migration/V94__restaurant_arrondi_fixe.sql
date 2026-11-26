-- Retour à l'arrondi fixe d'avant V92 : l'utilisateur ne choisit plus le
-- nombre de chiffres après la virgule. Les écrans affichent les francs sans
-- décimale, les dollars à 2 décimales et les quantités à 2 décimales au
-- plus (stores/restaurantParametres.ts). Les deux colonnes partent avec
-- leurs contraintes CHECK.
ALTER TABLE restaurant_parametres
    DROP COLUMN decimales_montants,
    DROP COLUMN decimales_quantites;
