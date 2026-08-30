-- Correctif : le caissier ne pouvait plus creer de vente.
--
-- La page « Nouvelle vente » charge le catalogue via GET /logistique/articles.
-- ModuleAccessFilter protege tout le prefixe /logistique par le module
-- LOGISTIQUE, or V28 n'accordait aucun droit au CAISSIER sur ce module : le
-- filtre repondait 403 avant meme d'atteindre StockService.listerArticles,
-- dont la liste de roles inclut pourtant explicitement le caissier
-- (« Le caissier est inclus en lecture : le catalogue (et sa disponibilite)
-- lui est necessaire pour saisir une vente. »).
--
-- On accorde donc la LECTURE seule : le caissier consulte articles, entrepots
-- et etat du stock, mais ne peut ni creer ni valider un mouvement de stock,
-- ce que StockService reserve deja aux roles LOGISTIQUE et ADMIN.
INSERT INTO role_permissions (role, module, niveau)
VALUES ('CAISSIER', 'LOGISTIQUE', 'LECTURE')
ON CONFLICT (role, module) DO UPDATE SET niveau = EXCLUDED.niveau;
