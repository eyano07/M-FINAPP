-- Régime de TVA de l'entreprise, défini par l'administrateur (Administration →
-- Taux de TVA). Non assujettie : aucune TVA n'est facturée sur les ventes ni
-- récupérée sur les achats — le taux effectivement appliqué partout vaut 0
-- (TauxTvaService.tauxALaDate) et la TVA payée aux fournisseurs reste dans la
-- charge. Les documents déjà enregistrés gardent la TVA de leur date.
-- Par défaut : assujettie, comportement de l'application jusqu'ici.
ALTER TABLE parametres_entreprise ADD COLUMN assujetti_tva BOOLEAN NOT NULL DEFAULT TRUE;
