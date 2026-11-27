-- Retour au stock des projets (27/11/2026) : un bien affecté à un projet (chantier fermé, surplus) revient dans le stock
-- commun des projets : RETOUR_STOCK sur l'article du projet (quantité et valeur négatives, l'exécuté du projet diminue),
-- RETOUR_STOCK_ENTREE sur l'article du stock commun (le bien redevient disponible, réaffectable à n'importe quel projet).
ALTER TABLE projet_mouvements DROP CONSTRAINT projet_mouvements_type_check;
ALTER TABLE projet_mouvements ADD CONSTRAINT projet_mouvements_type_check CHECK (type IN ('ACHAT', 'SORTIE',
    'TRANSFERT_SORTIE', 'TRANSFERT_ENTREE', 'RETOUR_FONDS', 'AJUSTEMENT', 'AFFECTATION', 'AFFECTATION_SORTIE',
    'RETOUR_STOCK', 'RETOUR_STOCK_ENTREE'));
