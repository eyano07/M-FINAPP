-- Répertoire des fournisseurs : raison sociale, contact, coordonnées, NIF, RCCM et compte comptable fournisseur
-- (401x). Les achats (lots de stock, immobilisations) gardent le nom du fournisseur en texte libre : une fiche à
-- laquelle des achats renvoient par son nom s'archive au lieu d'être supprimée.
CREATE TABLE fournisseurs (
    id                BIGSERIAL PRIMARY KEY,
    code              VARCHAR(20)  UNIQUE,
    nom               VARCHAR(200) NOT NULL,
    contact           VARCHAR(150),
    telephone         VARCHAR(40),
    email             VARCHAR(150),
    adresse           VARCHAR(255),
    nif               VARCHAR(40),
    rccm              VARCHAR(60),
    compte_comptable  VARCHAR(20)  NOT NULL DEFAULT '4011',
    actif             BOOLEAN      NOT NULL DEFAULT TRUE,
    date_creation     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    date_modification TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX ux_fournisseurs_nom ON fournisseurs (LOWER(TRIM(nom)));
