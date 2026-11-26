-- Facture normalisee DGI (RDC) : parametres du dispositif e-MCF, groupes de taxation, donnees fiscales du client et
-- registre des factures normalisees (une ligne par facture ou avoir transmis au dispositif).

CREATE TABLE parametres_emcf (
    id                BIGINT PRIMARY KEY,
    actif             BOOLEAN      NOT NULL DEFAULT FALSE,
    mode              VARCHAR(12)  NOT NULL DEFAULT 'SIMULATION',
    url_base          VARCHAR(300),
    jeton_chiffre     VARCHAR(1000),
    jeton_fin         VARCHAR(8),
    numero_def        VARCHAR(60),
    delai_ms          INTEGER      NOT NULL DEFAULT 10000,
    modifie_par_id    BIGINT REFERENCES users (id),
    date_maj          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE groupes_taxe_dgi (
    code     VARCHAR(5) PRIMARY KEY,
    libelle  VARCHAR(120)  NOT NULL,
    taux     NUMERIC(5, 2) NOT NULL DEFAULT 0,
    actif    BOOLEAN       NOT NULL DEFAULT TRUE,
    ordre    INTEGER       NOT NULL DEFAULT 0
);
-- Groupes 2026 : A = exonere et hors champ ; B = taxable au taux normal. Les autres groupes se completent depuis
-- l'ecran Administration une fois la specification DGI en main.
INSERT INTO groupes_taxe_dgi (code, libelle, taux, ordre) VALUES
    ('A', 'Exonéré et hors champ', 0, 1),
    ('B', 'Taxable (taux normal)', 16, 2);

ALTER TABLE articles     ADD COLUMN groupe_taxe VARCHAR(5);
ALTER TABLE lignes_vente ADD COLUMN groupe_taxe VARCHAR(5);

ALTER TABLE clients ADD COLUMN nif         VARCHAR(40);
ALTER TABLE clients ADD COLUMN type_client VARCHAR(20) NOT NULL DEFAULT 'PARTICULIER';

CREATE TABLE factures_normalisees (
    id                  BIGSERIAL PRIMARY KEY,
    vente_id            BIGINT      NOT NULL REFERENCES ventes (id),
    type                VARCHAR(10) NOT NULL,
    origine_id          BIGINT REFERENCES factures_normalisees (id),
    statut              VARCHAR(12) NOT NULL,
    mode                VARCHAR(12),
    uid                 VARCHAR(120),
    signature           VARCHAR(500),
    numero_def          VARCHAR(60),
    date_fiscale        TIMESTAMP WITH TIME ZONE,
    code_qr             TEXT,
    requete             TEXT,
    reponse             TEXT,
    tentatives          INTEGER     NOT NULL DEFAULT 0,
    derniere_erreur     VARCHAR(500),
    prochaine_tentative TIMESTAMP WITH TIME ZONE,
    date_creation       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    date_maj            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_facture_normalisee_vente_type UNIQUE (vente_id, type)
);
CREATE INDEX idx_factures_normalisees_statut ON factures_normalisees (statut, prochaine_tentative);
