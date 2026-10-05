-- Libelles d'affichage des roles, personnalisables par l'ADMIN (ex. DA -> DAF).
-- Purement cosmetique : le role technique (RoleType) et ses droits ne changent
-- jamais. L'absence de ligne signifie "libelle par defaut".
CREATE TABLE role_libelles (
    role     VARCHAR(30) PRIMARY KEY,
    libelle  VARCHAR(60) NOT NULL
);
