-- TVA recuperable sur la reception d'un chargement de minerais.
--
-- Jusqu'ici la reception ignorait la TVA, alors que la note de frais
-- l'imputait (4452) : un fournisseur assujetti voyait sa taxe absorbee dans
-- le cout d'achat, gonflant le stock et privant l'entreprise de sa
-- deduction. Incoherence entre canaux d'achat, corrigee ici.
--
-- prix_achat reste le montant HORS TAXES : c'est lui qui entre en stock et
-- forme le cout d'acquisition, la TVA recuperable etant une creance sur
-- l'Etat et non un element de cout. La dette envers le fournisseur, elle,
-- est bien le TTC — d'ou cette colonne, sans laquelle le reglement en caisse
-- sous-payerait la dette du montant de la taxe.
--
-- 0 par defaut : les chargements deja receptionnes l'ont ete hors TVA, et le
-- restent. Aucun recalcul retroactif.
ALTER TABLE camions_minerai
    ADD COLUMN montant_tva NUMERIC(15, 2) NOT NULL DEFAULT 0;
