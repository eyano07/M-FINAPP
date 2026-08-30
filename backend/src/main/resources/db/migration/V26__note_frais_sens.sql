-- =====================================================================
-- Notes de frais d'encaissement.
--
-- Jusqu'ici, toute note de frais etait un decaissement (avance ou
-- remboursement paye par la caisse a l'issue du circuit DFIN -> DA ->
-- Caisse). On introduit le pendant symetrique : une note d'encaissement,
-- emise directement par le caissier, sans validation DFIN/DA -- une
-- recette de caisse (vente comptant, remboursement recu, etc.) qui
-- necessite neanmoins de ventiler le montant sur plusieurs comptes de
-- produits, comme une note de decaissement le fait sur des comptes de
-- charges.
--
-- Reutilisation de l'entite NoteFrais existante (et non une table
-- separee) : la piste d'audit (observations), les pieces jointes, la
-- numerotation de reference et les regles de visibilite deja en place
-- s'appliquent alors sans aucune modification aux notes d'encaissement.
-- =====================================================================

ALTER TABLE notes_frais
    ADD COLUMN sens VARCHAR(20) NOT NULL DEFAULT 'DECAISSEMENT';

COMMENT ON COLUMN notes_frais.sens IS
    'DECAISSEMENT (circuit complet DFIN/DA, defaut historique) ou '
    'ENCAISSEMENT (recette de caisse directe, emise et executee par le caissier seul).';
