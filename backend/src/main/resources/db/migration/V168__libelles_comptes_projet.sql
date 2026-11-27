-- Comptes des projets (28/11/2026) : sans le nom du projet, car un bien acheté pour un projet peut être transféré à un
-- autre ; le rattachement au projet est porté par la fiche de stock projet. Besoin : « Projet — <désignation> » ;
-- regroupement : « Projets » (partagé par tous les projets).
UPDATE comptes_ohada c SET libelle = left('Projet — ' || a.designation, 200)
FROM projet_articles a
WHERE a.compte_id = c.id AND a.projet_id IS NOT NULL;

UPDATE comptes_ohada g SET libelle = 'Projets'
WHERE NOT g.imputable AND g.libelle ILIKE 'projet %'
  AND EXISTS (SELECT 1 FROM comptes_ohada c JOIN projet_articles a ON a.compte_id = c.id WHERE c.parent_id = g.id);
