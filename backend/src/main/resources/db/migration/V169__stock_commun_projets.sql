-- Stock commun des projets (27/11/2026). Un compte « Projet — … » n'est lié à aucun projet : il peut servir à plusieurs.
-- Un achat payé entre dans le stock commun (article sans projet, un par compte), puis le logisticien l'affecte à un
-- projet (mouvements AFFECTATION_SORTIE sur le stock commun, AFFECTATION sur l'article du projet).
-- Budget du projet : une ligne est EXÉCUTÉE quand le bien est affecté au projet, ENGAGÉE tant que le bien attend dans le
-- stock commun (ou que la note est approuvée sans être payée).

-- Plusieurs projets peuvent avoir un article sur le même compte ; le stock commun n'a qu'un article par compte.
ALTER TABLE projet_articles DROP CONSTRAINT projet_articles_compte_id_key;
CREATE INDEX idx_projet_articles_compte ON projet_articles (compte_id);
CREATE UNIQUE INDEX ux_projet_articles_stock_commun ON projet_articles (compte_id) WHERE projet_id IS NULL;
CREATE UNIQUE INDEX ux_projet_articles_projet_compte ON projet_articles (projet_id, compte_id)
    WHERE projet_id IS NOT NULL AND compte_id IS NOT NULL;

ALTER TABLE projet_mouvements DROP CONSTRAINT projet_mouvements_type_check;
ALTER TABLE projet_mouvements ADD CONSTRAINT projet_mouvements_type_check CHECK (type IN ('ACHAT', 'SORTIE',
    'TRANSFERT_SORTIE', 'TRANSFERT_ENTREE', 'RETOUR_FONDS', 'AJUSTEMENT', 'AFFECTATION', 'AFFECTATION_SORTIE'));
