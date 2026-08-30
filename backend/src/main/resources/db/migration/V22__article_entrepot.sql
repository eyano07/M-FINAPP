-- Un article de type MARCHANDISE doit toujours etre affecte a un entrepot
-- (entrepot par defaut pour ses mouvements). Un SERVICE n'a pas de stock et
-- donc pas d'entrepot, exactement comme il n'a pas de compte de stock.

ALTER TABLE articles
    ADD COLUMN entrepot_id BIGINT REFERENCES entrepots(id);

-- Rattrapage des marchandises deja enregistrees : a defaut d'un choix
-- explicite, on les affecte au premier entrepot connu plutot que de laisser
-- une donnee desormais obligatoire manquante.
UPDATE articles a
SET entrepot_id = (SELECT id FROM entrepots ORDER BY id LIMIT 1)
WHERE a.type = 'MARCHANDISE' AND a.entrepot_id IS NULL;
