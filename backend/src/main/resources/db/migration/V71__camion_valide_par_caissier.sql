-- La reception d'un camion de minerais par la logistique n'est plus, a elle
-- seule, un achat comptable. Jusqu'ici receptionner() constatait aussitot
-- D 601x / C 4011 (achat) et D 311x / C 6031 (entree en stock) — la
-- logistique validait donc de fait l'achat, sans intervention du caissier.
--
-- Desormais la reception cree un camion "A_VALIDER" : plaque, date, prix
-- proposes, mais AUCUNE ecriture, AUCUNE entree en stock. C'est la validation
-- par le caissier (MineraiService.validerAchat) qui constate l'achat et fait
-- entrer la marchandise en stock — meme partition brouillon/valide que
-- VenteService (BROUILLON -> valider()).
--
-- Au passage, le champ portait un nom trompeur : "date_achat" alors qu'aucun
-- achat n'est encore constate a la reception. Renomme en date_reception —
-- c'est cette meme date qui datera l'ecriture d'achat une fois validee (voir
-- VenteService.valider, qui date lui aussi ses ecritures a la date de
-- creation, pas a celle de la validation).
ALTER TABLE camions_minerai RENAME COLUMN date_achat TO date_reception;
ALTER INDEX uq_camion_article_plaque_date RENAME TO uq_camion_article_plaque_reception;
