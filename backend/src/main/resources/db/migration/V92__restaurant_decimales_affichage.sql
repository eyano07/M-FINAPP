-- Arrondi à l'affichage dans le module Restaurant : combien de chiffres
-- après la virgule pour les montants et pour les quantités.
--
-- Affichage seulement : les montants restent enregistrés et comptabilisés
-- avec leur propre précision (au centime de dollar au grand livre, coût
-- moyen à 6 décimales), quel que soit ce réglage.
--
-- decimales_montants NULL = automatique, l'affichage d'avant ce réglage :
-- francs congolais sans décimale, dollars à 2 décimales.
-- INTEGER, pas SMALLINT : Hibernate valide le schéma (ddl-auto: validate) et
-- attend INTEGER pour un champ Integer.
ALTER TABLE restaurant_parametres
    ADD COLUMN decimales_montants INTEGER
        CONSTRAINT chk_restaurant_parametres_decimales_montants CHECK (decimales_montants BETWEEN 0 AND 4),
    ADD COLUMN decimales_quantites INTEGER NOT NULL DEFAULT 2
        CONSTRAINT chk_restaurant_parametres_decimales_quantites CHECK (decimales_quantites BETWEEN 0 AND 3);
