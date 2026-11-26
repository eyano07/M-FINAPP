-- Prix d'achat unitaire indicatif, saisi en amont par la logistique.
-- Facultatif, au meme titre que le prix de vente : il ne sert qu'a preremplir
-- le montant lors d'un achat de marchandise saisi a la caisse, ou le caissier
-- reste libre de le corriger au prix reellement paye (voir CaisseService
-- .acheterMarchandise). Aucune ecriture ne s'appuie sur cette valeur : le
-- cout d'entree en stock est toujours celui effectivement saisi.
ALTER TABLE articles ADD COLUMN prix_achat NUMERIC(15, 2);
