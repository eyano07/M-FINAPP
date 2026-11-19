-- ---------------------------------------------------------------------------
-- M-FINAPP Hôtellerie, base mbsc_pms : socle de la phase 1 (établissements, unités, tarifs, clients, réservations,
-- folios, acomptes et cautions, ménage, journal). La comptabilité, la caisse, le stock et les comptes restent dans
-- M-FINAPP : cette base ne garde que l'exploitation de l'hébergement et les références des écritures de M-FINAPP.
-- ---------------------------------------------------------------------------

-- Exclusion « une unité, une seule réservation active à la fois » : index GiST sur (unité, période).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE parametres_pms (
    id            BIGINT PRIMARY KEY,
    cle_chiffree  TEXT,
    appaire_le    TIMESTAMPTZ,
    appaire_par   VARCHAR(255)
);
INSERT INTO parametres_pms (id) VALUES (1);

CREATE TABLE etablissements (
    id            BIGSERIAL PRIMARY KEY,
    nom           VARCHAR(150) NOT NULL UNIQUE,
    type          VARCHAR(20)  NOT NULL CHECK (type IN ('HOTEL', 'MOTEL', 'APPARTEMENTS')),
    adresse       VARCHAR(255),
    telephone     VARCHAR(40),
    heure_arrivee TIME NOT NULL DEFAULT '14:00',
    heure_depart  TIME NOT NULL DEFAULT '12:00',
    actif         BOOLEAN NOT NULL DEFAULT TRUE,
    cree_le       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE types_unite (
    id               BIGSERIAL PRIMARY KEY,
    etablissement_id BIGINT NOT NULL REFERENCES etablissements(id),
    nom              VARCHAR(100) NOT NULL,
    capacite         INTEGER NOT NULL DEFAULT 2 CHECK (capacite > 0),
    description      VARCHAR(500),
    actif            BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (etablissement_id, nom)
);

CREATE TABLE unites (
    id               BIGSERIAL PRIMARY KEY,
    etablissement_id BIGINT NOT NULL REFERENCES etablissements(id),
    type_id          BIGINT NOT NULL REFERENCES types_unite(id),
    numero           VARCHAR(30) NOT NULL,
    etage            VARCHAR(20),
    capacite         INTEGER CHECK (capacite IS NULL OR capacite > 0),
    equipements      VARCHAR(500),
    -- État ménage : PROPRE, A_NETTOYER, EN_COURS (nettoyage commencé), A_INSPECTER.
    etat_menage      VARCHAR(20) NOT NULL DEFAULT 'PROPRE'
                     CHECK (etat_menage IN ('PROPRE', 'A_NETTOYER', 'EN_COURS', 'A_INSPECTER')),
    hors_service     BOOLEAN NOT NULL DEFAULT FALSE,
    motif_hors_service VARCHAR(255),
    notes            VARCHAR(500),
    ordre            INTEGER NOT NULL DEFAULT 0,
    actif            BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (etablissement_id, numero)
);

-- Prix par type d'unité et par durée (heure, nuit, semaine, mois) ; saison facultative (du … au …).
CREATE TABLE tarifs (
    id           BIGSERIAL PRIMARY KEY,
    type_id      BIGINT NOT NULL REFERENCES types_unite(id),
    unite_duree  VARCHAR(10) NOT NULL CHECK (unite_duree IN ('HEURE', 'NUIT', 'SEMAINE', 'MOIS')),
    prix         NUMERIC(15, 2) NOT NULL CHECK (prix >= 0),
    libelle      VARCHAR(100),
    debut_saison DATE,
    fin_saison   DATE,
    actif        BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (debut_saison IS NULL OR fin_saison IS NULL OR fin_saison >= debut_saison)
);

-- Fiche client (CRM) ; finapp_client_id : le client M-FINAPP (compte 411x) qui porte ses folios.
CREATE TABLE clients (
    id                BIGSERIAL PRIMARY KEY,
    nom               VARCHAR(120) NOT NULL,
    prenom            VARCHAR(120),
    telephone         VARCHAR(40),
    email             VARCHAR(150),
    nationalite       VARCHAR(80),
    date_naissance    DATE,
    piece_type        VARCHAR(40),
    piece_numero      VARCHAR(60),
    adresse           VARCHAR(255),
    entreprise        VARCHAR(150),
    preferences       TEXT,
    notes             TEXT,
    finapp_client_id  BIGINT,
    cree_le           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_clients_nom ON clients (lower(nom));

-- Pièces d'identité scannées : le fichier vit dans le volume des téléversements, jamais en base.
CREATE TABLE pieces_identite (
    id            BIGSERIAL PRIMARY KEY,
    client_id     BIGINT NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    fichier       VARCHAR(255) NOT NULL,
    nom_origine   VARCHAR(255),
    type_contenu  VARCHAR(100),
    cree_par      VARCHAR(255),
    cree_le       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE SEQUENCE reference_reservation_seq;

CREATE TABLE reservations (
    id                  BIGSERIAL PRIMARY KEY,
    reference           VARCHAR(30) NOT NULL UNIQUE,
    etablissement_id    BIGINT NOT NULL REFERENCES etablissements(id),
    unite_id            BIGINT NOT NULL REFERENCES unites(id),
    client_id           BIGINT NOT NULL REFERENCES clients(id),
    debut               TIMESTAMPTZ NOT NULL,
    fin                 TIMESTAMPTZ NOT NULL,
    unite_duree         VARCHAR(10) NOT NULL CHECK (unite_duree IN ('HEURE', 'NUIT', 'SEMAINE', 'MOIS')),
    prix_unitaire       NUMERIC(15, 2) NOT NULL CHECK (prix_unitaire >= 0),
    adultes             INTEGER NOT NULL DEFAULT 1 CHECK (adultes >= 0),
    enfants             INTEGER NOT NULL DEFAULT 0 CHECK (enfants >= 0),
    canal               VARCHAR(20) NOT NULL DEFAULT 'RECEPTION',
    statut              VARCHAR(20) NOT NULL DEFAULT 'CONFIRMEE'
                        CHECK (statut IN ('OPTION', 'CONFIRMEE', 'ARRIVEE', 'PARTIE', 'ANNULEE', 'NO_SHOW')),
    code_acces          VARCHAR(40),
    notes               TEXT,
    arrivee_le          TIMESTAMPTZ,
    depart_le           TIMESTAMPTZ,
    -- Folio devenu vente M-FINAPP au départ ; EN_ATTENTE : départ enregistré, M-FINAPP injoignable, à réessayer.
    vente_id            BIGINT,
    vente_reference     VARCHAR(40),
    encaissement        VARCHAR(20) CHECK (encaissement IN ('EN_ATTENTE', 'ENCAISSE', 'CREDIT')),
    cle_encaissement    VARCHAR(80),
    -- Demande d'encaissement gardée tant que M-FINAPP n'a pas répondu (rejouée avec la même clé : jamais deux ventes).
    encaissement_demande TEXT,
    motif_annulation    VARCHAR(255),
    cree_par            VARCHAR(255),
    cree_le             TIMESTAMPTZ NOT NULL DEFAULT now(),
    maj_le              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (fin > debut),
    -- Surréservation impossible : deux réservations actives ne se chevauchent jamais sur la même unité.
    CONSTRAINT reservation_sans_chevauchement EXCLUDE USING gist (
        unite_id WITH =, tstzrange(debut, fin, '[)') WITH &&
    ) WHERE (statut IN ('OPTION', 'CONFIRMEE', 'ARRIVEE'))
);
CREATE INDEX idx_reservations_periode ON reservations (debut, fin);
CREATE INDEX idx_reservations_client ON reservations (client_id);

-- Extras du folio (minibar, restauration, blanchisserie…) ; les nuitées se calculent depuis la réservation.
CREATE TABLE folio_lignes (
    id              BIGSERIAL PRIMARY KEY,
    reservation_id  BIGINT NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    article_id      BIGINT,
    article_code    VARCHAR(40),
    designation     VARCHAR(200) NOT NULL,
    quantite        NUMERIC(15, 3) NOT NULL CHECK (quantite > 0),
    prix_unitaire   NUMERIC(15, 2) NOT NULL CHECK (prix_unitaire >= 0),
    stocke          BOOLEAN NOT NULL DEFAULT FALSE,
    date_ligne      DATE NOT NULL DEFAULT current_date,
    cree_par        VARCHAR(255),
    cree_le         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Acomptes, cautions et restitutions : chacun est une pièce de M-FINAPP (référence gardée ici).
CREATE TABLE fonds (
    id               BIGSERIAL PRIMARY KEY,
    reservation_id   BIGINT NOT NULL REFERENCES reservations(id),
    type             VARCHAR(20) NOT NULL CHECK (type IN ('ACOMPTE', 'CAUTION', 'RESTITUTION', 'REMB_ACOMPTE')),
    montant          NUMERIC(15, 2) NOT NULL CHECK (montant > 0),
    mode             VARCHAR(20) NOT NULL,
    etablissement_id BIGINT,
    piece_reference  VARCHAR(40),
    cle              VARCHAR(80) NOT NULL UNIQUE,
    cree_par         VARCHAR(255),
    cree_le          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE taches_menage (
    id              BIGSERIAL PRIMARY KEY,
    unite_id        BIGINT NOT NULL REFERENCES unites(id),
    reservation_id  BIGINT REFERENCES reservations(id),
    type            VARCHAR(20) NOT NULL DEFAULT 'DEPART' CHECK (type IN ('DEPART', 'RECOUCHE', 'INSPECTION', 'AUTRE')),
    statut          VARCHAR(20) NOT NULL DEFAULT 'A_FAIRE' CHECK (statut IN ('A_FAIRE', 'EN_COURS', 'TERMINEE')),
    priorite        VARCHAR(10) NOT NULL DEFAULT 'NORMALE' CHECK (priorite IN ('BASSE', 'NORMALE', 'HAUTE')),
    assigne_email   VARCHAR(255),
    assigne_nom     VARCHAR(255),
    echeance        TIMESTAMPTZ,
    notes           VARCHAR(500),
    cree_le         TIMESTAMPTZ NOT NULL DEFAULT now(),
    commence_le     TIMESTAMPTZ,
    termine_le      TIMESTAMPTZ,
    termine_par     VARCHAR(255)
);
CREATE INDEX idx_taches_statut ON taches_menage (statut);

-- Journal des actions (ajout seul), doublé du fichier audit.log du volume des journaux.
CREATE TABLE journal (
    id      BIGSERIAL PRIMARY KEY,
    quand   TIMESTAMPTZ NOT NULL DEFAULT now(),
    qui     VARCHAR(255),
    action  VARCHAR(60) NOT NULL,
    objet   VARCHAR(120),
    detail  TEXT
);
