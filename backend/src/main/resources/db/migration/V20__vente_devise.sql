-- Devise de la vente : le caissier saisit desormais en FC ou en USD.
--
-- Les montants (prix unitaires et totaux) sont stockes DANS LA DEVISE DE LA
-- VENTE, exactement comme une note de frais porte son montant dans sa propre
-- devise. La conversion vers la devise de base (CDF) n'intervient qu'a la
-- validation, au moment de produire les ecritures du journal VENTES.
--
-- Les ventes deja saisies l'ont ete avec des montants convertis en FC par le
-- front : elles sont donc en CDF, ce que donne la valeur par defaut.

ALTER TABLE ventes
    ADD COLUMN devise VARCHAR(3) NOT NULL DEFAULT 'CDF',
    ADD COLUMN taux_journalier NUMERIC(15, 6);

-- Taux du jour fige au moment de l'operation : indispensable pour reafficher
-- une vente en FC dans l'autre devise sans dependre du taux courant.
COMMENT ON COLUMN ventes.taux_journalier IS
    'Taux de change (FC pour 1 USD) en vigueur au moment de la saisie de la vente';
