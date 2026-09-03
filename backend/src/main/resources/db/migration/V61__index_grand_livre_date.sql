-- Le Grand Livre est la table qui croit le plus vite (chaque operation y ajoute
-- ses lignes) et date_ecriture est le filtre de PRESQUE TOUTES les requetes
-- comptables : grand livre par compte, balance de verification, compte de
-- resultat (mouvementsParCompte), bilan (cumulParCompte), report a nouveau
-- (soldeAnterieur) — voir EcritureGrandLivreRepository. Aucun index ne la
-- couvrait : chaque etat financier faisait un parcours complet de la table.
--
-- Invisible sur le volume actuel, mais le cout croit lineairement avec
-- l'historique, et un plan comptable OHADA complet multiplie les comptes
-- interroges. L'index composite (compte_id, date_ecriture) sert les requetes
-- par compte sur periode, qui sont les plus frequentes ; l'index sur la seule
-- date sert les agregations tous comptes confondus (balance, bilan).
CREATE INDEX IF NOT EXISTS idx_grand_livre_date
  ON grand_livre (date_ecriture);

CREATE INDEX IF NOT EXISTS idx_grand_livre_compte_date
  ON grand_livre (compte_id, date_ecriture);

-- Les etats financiers excluent systematiquement les pieces au BROUILLON en
-- joignant pieces_comptables sur son statut : sans index, ce filtre force un
-- parcours de la table des pieces a chaque etat.
CREATE INDEX IF NOT EXISTS idx_pieces_comptables_statut_date
  ON pieces_comptables (statut, date_piece);
