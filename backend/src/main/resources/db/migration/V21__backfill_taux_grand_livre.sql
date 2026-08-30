-- Fige retroactivement le taux du jour sur les ecritures anterieures.
--
-- Depuis V20, toute ecriture nait avec le taux en vigueur au moment de
-- l'operation, ce qui permet de la reafficher en USD sans dependre du taux
-- courant. Les ecritures deja en base n'ont pas ce taux : sans ce rattrapage,
-- leur contre-valeur en USD changerait a chaque fois que l'administrateur
-- enregistre un nouveau taux — precisement ce que le taux fige evite.
--
-- On retient le taux applicable a la date de l'ecriture (le plus recent dont
-- la date d'effet ne lui est pas posterieure) ; pour les ecritures anterieures
-- au tout premier taux connu, on retient ce premier taux, faute de mieux.

UPDATE grand_livre g
SET taux_applique = COALESCE(
    (SELECT t.taux
       FROM taux_change t
      WHERE t.date_effet <= g.date_ecriture
      ORDER BY t.date_effet DESC, t.created_at DESC
      LIMIT 1),
    (SELECT t.taux
       FROM taux_change t
      ORDER BY t.date_effet ASC, t.created_at ASC
      LIMIT 1)
)
WHERE g.taux_applique IS NULL;
