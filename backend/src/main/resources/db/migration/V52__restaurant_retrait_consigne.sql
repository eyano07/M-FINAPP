-- La consigne unitaire par bouteille était purement indicative (aucune
-- écriture comptable ne s'appuyait dessus) et n'apportait pas de valeur
-- suffisante pour justifier sa maintenance : retirée à la demande.
ALTER TABLE restaurant_emballages DROP COLUMN consigne_bouteille;
