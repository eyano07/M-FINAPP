-- Société d'un article de la carte (brasserie, fabricant ou fournisseur : Bracongo, Brasimba...).
-- Champ libre et facultatif, réservé aux boissons : avec le libellé, il sert à composer
-- automatiquement le code d'une nouvelle boisson (voir GenerateurCodeArticle).
-- Les articles existants n'en ont pas : NULL.
ALTER TABLE articles ADD COLUMN societe VARCHAR(100);
