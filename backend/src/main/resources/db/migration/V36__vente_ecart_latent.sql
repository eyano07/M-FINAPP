-- Une creance revaluee a la cloture (ecarts 478/479, piece separee de la
-- piece d'origine) doit rester traçable jusqu'a son reglement : sans ce
-- cumul, crediter le compte client au moment du reglement ne solderait que
-- le montant initialement facture, laissant un residu correspondant a la
-- reevaluation latente definitivement bloque sur le compte 4111.
ALTER TABLE ventes ADD COLUMN ecart_latent_cumule NUMERIC(15,2) NOT NULL DEFAULT 0;
