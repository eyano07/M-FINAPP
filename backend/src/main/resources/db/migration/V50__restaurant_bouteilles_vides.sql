-- ---------------------------------------------------------------------------
-- Stock de bouteilles vides et casiers (module Restaurant).
--
-- V49 suivait les emballages avec deux compteurs (en magasin / chez le client)
-- alimentes a la main. Le vrai cycle d'un bar est celui de la consigne :
-- chaque bouteille VENDUE revient en stock de vides, et acheter des casiers
-- pleins fait RENDRE l'equivalent en vides.
--
-- Modele retenu : UN SEUL compteur de bouteilles vides par boisson. Le casier
-- n'est PAS stocke, c'est une unite d'affichage derivee
-- (casiers = vides / contenance, reste = bouteilles). C'est ce qui reproduit
-- exactement l'arithmetique metier : 3 casiers + 8 bouteilles = 80 vides ;
-- vendre 3 puis 28 donne 111, soit 4 casiers + 15 ; acheter 2 casiers depuis
-- 80 laisse 32, soit 1 casier + 8.
--
-- Les mouvements d'emballages ne produisent AUCUNE ecriture comptable : la
-- consigne n'est pas portee au bilan (pas de 4194/7074). Seule la reception de
-- boissons, qui est un vrai achat, genere une piece -- via le moteur de stock
-- ordinaire, pas ici.
--
-- On procede par ALTER et non par DROP : les tables sont vides a ce jour, mais
-- une migration ne doit pas detruire de donnees au cas ou un environnement en
-- aurait acquis entre-temps.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. Emballages : un conditionnement par boisson
-- ---------------------------------------------------------------------------
ALTER TABLE restaurant_emballages
    ADD COLUMN IF NOT EXISTS contenance_casier  INTEGER,
    ADD COLUMN IF NOT EXISTS bouteilles_vides   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS consigne_bouteille NUMERIC(15,2) NOT NULL DEFAULT 0;

-- Reprise des donnees eventuelles avant de poser les contraintes.
UPDATE restaurant_emballages SET contenance_casier = COALESCE(contenance, 24)
WHERE contenance_casier IS NULL;

-- Un emballage sans boisson n'a plus de sens : le conditionnement se definit
-- pour une boisson donnee ("le casier de Coca 33 cl contient 24 bouteilles").
DELETE FROM restaurant_mouvements_emballage
WHERE emballage_id IN (SELECT id FROM restaurant_emballages WHERE article_boisson_id IS NULL);
DELETE FROM restaurant_emballages WHERE article_boisson_id IS NULL;

ALTER TABLE restaurant_emballages
    ALTER COLUMN contenance_casier SET NOT NULL,
    ALTER COLUMN article_boisson_id SET NOT NULL;

ALTER TABLE restaurant_emballages
    DROP CONSTRAINT IF EXISTS chk_emballage_quantites,
    DROP COLUMN IF EXISTS type,
    DROP COLUMN IF EXISTS contenance,
    DROP COLUMN IF EXISTS montant_consigne,
    DROP COLUMN IF EXISTS quantite_stock,
    DROP COLUMN IF EXISTS quantite_circulation;

ALTER TABLE restaurant_emballages
    ADD CONSTRAINT uq_emballage_boisson UNIQUE (article_boisson_id),
    ADD CONSTRAINT chk_emballage_contenance CHECK (contenance_casier > 0),
    ADD CONSTRAINT chk_emballage_vides CHECK (bouteilles_vides >= 0);

COMMENT ON TABLE restaurant_emballages IS
    'Conditionnement d''une boisson : combien de bouteilles par casier, et combien de bouteilles vides sont actuellement en stock. Le nombre de casiers n''est pas stocke, il se deduit (vides / contenance_casier).';
COMMENT ON COLUMN restaurant_emballages.bouteilles_vides IS
    'Stock de bouteilles vides. Alimente automatiquement par les ventes, diminue par les achats (echange de consigne) et la casse.';
COMMENT ON COLUMN restaurant_emballages.consigne_bouteille IS
    'Consigne unitaire INDICATIVE (FC), affichee seulement : aucune ecriture comptable n''est generee sur les emballages.';

-- ---------------------------------------------------------------------------
-- 2. Journal du stock de vides
-- ---------------------------------------------------------------------------
-- La quantite reste TOUJOURS positive : le sens du mouvement est porte par le
-- type, jamais infere d'un signe. Types : VENTE (+), RETOUR_VENTE (-),
-- ACHAT (-), CASSE (-), AJUSTEMENT_PLUS (+), AJUSTEMENT_MOINS (-).
ALTER TABLE restaurant_mouvements_emballage
    ADD COLUMN IF NOT EXISTS quantite_bouteilles INTEGER,
    ADD COLUMN IF NOT EXISTS vente_id BIGINT REFERENCES ventes(id),
    ADD COLUMN IF NOT EXISTS motif VARCHAR(500);

UPDATE restaurant_mouvements_emballage
SET quantite_bouteilles = CEIL(quantite)::INTEGER
WHERE quantite_bouteilles IS NULL;

UPDATE restaurant_mouvements_emballage SET motif = tiers WHERE motif IS NULL AND tiers IS NOT NULL;

ALTER TABLE restaurant_mouvements_emballage
    ALTER COLUMN quantite_bouteilles SET NOT NULL,
    DROP COLUMN IF EXISTS quantite,
    DROP COLUMN IF EXISTS tiers,
    DROP COLUMN IF EXISTS notes;

ALTER TABLE restaurant_mouvements_emballage
    ADD CONSTRAINT chk_mvt_emballage_quantite CHECK (quantite_bouteilles > 0);

CREATE INDEX IF NOT EXISTS idx_mvt_emballage_vente ON restaurant_mouvements_emballage (vente_id);

COMMENT ON TABLE restaurant_mouvements_emballage IS
    'Journal du stock de bouteilles vides. La somme signee des mouvements doit toujours egaler restaurant_emballages.bouteilles_vides.';
COMMENT ON COLUMN restaurant_mouvements_emballage.vente_id IS
    'Vente a l''origine du mouvement, pour les mouvements automatiques (VENTE, RETOUR_VENTE). NULL pour une saisie manuelle.';
