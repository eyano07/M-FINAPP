-- Duree de conservation d'un plat et date d'expiration des portions produites.
--
-- La fiche technique d'un plat porte sa duree de conservation, en jours. Chaque production en tire la date d'expiration de ses
-- portions (date de production + duree) : passee cette date, les portions qui n'ont pas ete vendues sont perimees (voir
-- RestaurantService.listerPlatsPerimes). Une duree absente veut dire que le plat ne se perime pas.
ALTER TABLE articles
    ADD COLUMN duree_conservation_jours INTEGER
        CONSTRAINT chk_articles_duree_conservation
        CHECK (duree_conservation_jours IS NULL OR duree_conservation_jours BETWEEN 0 AND 365);

-- Fixee a la production : modifier ensuite la duree de la fiche ne change pas la date des portions deja produites.
-- Les productions anterieures n'en ont pas : leurs portions ne se perimeront jamais.
ALTER TABLE productions ADD COLUMN date_expiration DATE;
