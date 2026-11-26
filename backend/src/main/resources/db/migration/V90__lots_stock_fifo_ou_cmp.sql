-- Le paramètre de sortie des lots de stock du module Restaurant ne propose
-- plus que les deux méthodes reconnues par le SYSCOHADA pour les biens
-- interchangeables : FIFO (le plus ancien lot s'épuise en premier) et CMP
-- (coût moyen pondéré : chaque sortie prélève sur tous les lots au prorata).
-- Le LIFO est retiré ; un réglage LIFO éventuel passe au CMP.
--
-- Toujours un suivi de gestion : la comptabilité reste au coût moyen
-- pondéré quel que soit ce choix (voir V89).
UPDATE restaurant_parametres SET methode_sortie_lots = 'CMP' WHERE methode_sortie_lots = 'LIFO';

ALTER TABLE restaurant_parametres
    ADD CONSTRAINT chk_restaurant_parametres_methode_sortie_lots
    CHECK (methode_sortie_lots IN ('FIFO', 'CMP'));
