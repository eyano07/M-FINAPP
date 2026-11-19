-- ---------------------------------------------------------------------------
-- Phase 3 : motels (forfaits par créneau, arrivée express, parkings et véhicules), appartements (baux et échéancier,
-- relevés de compteurs refacturés, états des lieux avec photos), maintenance (tickets, unité hors service datée,
-- achats de pièces par note de frais M-FINAPP). Établissement mixte (hôtel et appartements sous le même toit).
-- ---------------------------------------------------------------------------

-- 0. Établissement mixte.
ALTER TABLE etablissements DROP CONSTRAINT IF EXISTS etablissements_type_check;
ALTER TABLE etablissements ADD CONSTRAINT etablissements_type_check
    CHECK (type IN ('HOTEL', 'MOTEL', 'APPARTEMENTS', 'MIXTE'));

-- 1. Forfaits par créneau (motels) : prix TOTAL pour N heures (« Sieste 3 h »), éventuellement réservé à une plage
--    d'heures d'arrivée (« Nuit motel » de 20 h à 2 h). La réservation garde le forfait choisi.
ALTER TABLE tarifs DROP CONSTRAINT IF EXISTS tarifs_unite_duree_check;
ALTER TABLE tarifs ADD CONSTRAINT tarifs_unite_duree_check
    CHECK (unite_duree IN ('HEURE', 'NUIT', 'SEMAINE', 'MOIS', 'CRENEAU'));
ALTER TABLE tarifs ADD COLUMN IF NOT EXISTS duree_heures INTEGER CHECK (duree_heures IS NULL OR duree_heures BETWEEN 1 AND 24);
ALTER TABLE tarifs ADD COLUMN IF NOT EXISTS plage_debut TIME;
ALTER TABLE tarifs ADD COLUMN IF NOT EXISTS plage_fin TIME;
ALTER TABLE tarifs ADD CONSTRAINT tarif_creneau_avec_duree CHECK (unite_duree <> 'CRENEAU' OR duree_heures IS NOT NULL);

ALTER TABLE reservations DROP CONSTRAINT IF EXISTS reservations_unite_duree_check;
ALTER TABLE reservations ADD CONSTRAINT reservations_unite_duree_check
    CHECK (unite_duree IN ('HEURE', 'NUIT', 'SEMAINE', 'MOIS', 'CRENEAU'));
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS tarif_id BIGINT REFERENCES tarifs(id);
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS express BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Parkings et véhicules. Une place n'accueille qu'un véhicule présent à la fois.
CREATE TABLE places_parking (
    id               BIGSERIAL PRIMARY KEY,
    etablissement_id BIGINT NOT NULL REFERENCES etablissements(id),
    numero           VARCHAR(20) NOT NULL,
    couverte         BOOLEAN NOT NULL DEFAULT FALSE,
    -- Garage attitré d'une unité de motel (facultatif) : proposé d'office à l'arrivée.
    unite_id         BIGINT REFERENCES unites(id),
    notes            VARCHAR(255),
    actif            BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (etablissement_id, numero)
);

CREATE TABLE vehicules (
    id               BIGSERIAL PRIMARY KEY,
    reservation_id   BIGINT NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    immatriculation  VARCHAR(20) NOT NULL,
    marque           VARCHAR(60),
    couleur          VARCHAR(40),
    place_id         BIGINT REFERENCES places_parking(id),
    entree_le        TIMESTAMPTZ NOT NULL DEFAULT now(),
    sortie_le        TIMESTAMPTZ,
    cree_par         VARCHAR(255)
);
CREATE UNIQUE INDEX vehicule_un_par_place ON vehicules (place_id) WHERE sortie_le IS NULL AND place_id IS NOT NULL;
CREATE INDEX idx_vehicules_immatriculation ON vehicules (upper(immatriculation));

-- 3. Unité hors service DATÉE : jusqu'à quand (fin prévue des travaux) et par quel ticket. Sans date de fin, l'unité est
--    bloquée sans limite ; avec une date, les réservations qui commencent après restent possibles.
ALTER TABLE unites ADD COLUMN IF NOT EXISTS hors_service_du TIMESTAMPTZ;
ALTER TABLE unites ADD COLUMN IF NOT EXISTS hors_service_au TIMESTAMPTZ;
ALTER TABLE unites ADD COLUMN IF NOT EXISTS ticket_hors_service_id BIGINT;

-- 4. Maintenance.
CREATE SEQUENCE reference_ticket_seq;

CREATE TABLE tickets_maintenance (
    id               BIGSERIAL PRIMARY KEY,
    reference        VARCHAR(30) NOT NULL UNIQUE,
    etablissement_id BIGINT NOT NULL REFERENCES etablissements(id),
    unite_id         BIGINT REFERENCES unites(id),
    -- Lieu hors unité (hall, piscine, cuisine…) quand le ticket ne vise pas une chambre.
    lieu             VARCHAR(120),
    categorie        VARCHAR(20) NOT NULL CHECK (categorie IN ('PLOMBERIE', 'CLIMATISATION', 'ELECTRICITE', 'ELECTROMENAGER',
                     'SERRURERIE', 'MOBILIER', 'PEINTURE', 'INTERNET_TV', 'AUTRE')),
    priorite         VARCHAR(10) NOT NULL DEFAULT 'NORMALE' CHECK (priorite IN ('BASSE', 'NORMALE', 'HAUTE', 'URGENTE')),
    titre            VARCHAR(150) NOT NULL,
    description      TEXT,
    statut           VARCHAR(20) NOT NULL DEFAULT 'OUVERT'
                     CHECK (statut IN ('OUVERT', 'EN_COURS', 'ATTENTE_PIECES', 'RESOLU', 'ANNULE')),
    hors_service     BOOLEAN NOT NULL DEFAULT FALSE,
    hors_service_au  TIMESTAMPTZ,
    assigne_email    VARCHAR(255),
    assigne_nom      VARCHAR(255),
    signale_par      VARCHAR(255),
    signale_le       TIMESTAMPTZ NOT NULL DEFAULT now(),
    commence_le      TIMESTAMPTZ,
    resolu_le        TIMESTAMPTZ,
    resolu_par       VARCHAR(255),
    resolution       TEXT,
    maj_le           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tickets_statut ON tickets_maintenance (statut);
CREATE INDEX idx_tickets_unite ON tickets_maintenance (unite_id);
ALTER TABLE unites ADD CONSTRAINT unite_ticket_hors_service_fk
    FOREIGN KEY (ticket_hors_service_id) REFERENCES tickets_maintenance(id);

-- Achats de pièces ou de prestations : chacun est une note de frais M-FINAPP (circuit DFIN → DA → caisse) dont le
-- statut est relu ici. La clé rend la demande idempotente (jamais deux notes pour un même clic).
CREATE TABLE achats_maintenance (
    id               BIGSERIAL PRIMARY KEY,
    ticket_id        BIGINT NOT NULL REFERENCES tickets_maintenance(id),
    cle              VARCHAR(80) NOT NULL UNIQUE,
    objet            VARCHAR(200) NOT NULL,
    fournisseur      VARCHAR(200),
    montant          NUMERIC(15, 2) NOT NULL CHECK (montant > 0),
    devise           VARCHAR(3) NOT NULL DEFAULT 'USD',
    compte           VARCHAR(20) NOT NULL,
    note_id          BIGINT,
    note_reference   VARCHAR(40),
    note_statut      VARCHAR(30),
    statut_lu_le     TIMESTAMPTZ,
    cree_par         VARCHAR(255),
    cree_le          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 5. Photos (états des lieux, tickets, relevés) : fichiers dans le volume des téléversements, jamais en base.
CREATE TABLE photos (
    id            BIGSERIAL PRIMARY KEY,
    objet         VARCHAR(20) NOT NULL CHECK (objet IN ('ETAT_LIEUX', 'TICKET', 'RELEVE')),
    objet_id      BIGINT NOT NULL,
    fichier       VARCHAR(255) NOT NULL,
    nom_origine   VARCHAR(255),
    type_contenu  VARCHAR(100),
    legende       VARCHAR(200),
    cree_par      VARCHAR(255),
    cree_le       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_photos_objet ON photos (objet, objet_id);

-- 6. Appartements : baux (sur une réservation au mois, qui bloque le calendrier) et échéancier mensuel. Chaque échéance
--    facturée devient une vente M-FINAPP à crédit (loyer + charges), réglée ensuite par la réception.
CREATE SEQUENCE reference_bail_seq;

CREATE TABLE baux (
    id                    BIGSERIAL PRIMARY KEY,
    reference             VARCHAR(30) NOT NULL UNIQUE,
    reservation_id        BIGINT NOT NULL UNIQUE REFERENCES reservations(id),
    loyer_mensuel         NUMERIC(15, 2) NOT NULL CHECK (loyer_mensuel >= 0),
    charges_forfait       NUMERIC(15, 2) NOT NULL DEFAULT 0 CHECK (charges_forfait >= 0),
    jour_echeance         INTEGER NOT NULL DEFAULT 1 CHECK (jour_echeance BETWEEN 1 AND 28),
    refacturer_compteurs  BOOLEAN NOT NULL DEFAULT TRUE,
    statut                VARCHAR(20) NOT NULL DEFAULT 'ACTIF' CHECK (statut IN ('ACTIF', 'TERMINE', 'RESILIE')),
    conditions            TEXT,
    cree_par              VARCHAR(255),
    cree_le               TIMESTAMPTZ NOT NULL DEFAULT now(),
    termine_le            TIMESTAMPTZ
);

CREATE TABLE echeances_bail (
    id               BIGSERIAL PRIMARY KEY,
    bail_id          BIGINT NOT NULL REFERENCES baux(id) ON DELETE CASCADE,
    numero           INTEGER NOT NULL,
    periode_debut    DATE NOT NULL,
    periode_fin      DATE NOT NULL,
    date_echeance    DATE NOT NULL,
    loyer            NUMERIC(15, 2) NOT NULL CHECK (loyer >= 0),
    charges          NUMERIC(15, 2) NOT NULL DEFAULT 0 CHECK (charges >= 0),
    detail_charges   TEXT,
    -- A_FACTURER → (EN_ATTENTE si M-FINAPP injoignable) → FACTUREE (vente à crédit) → REGLEE.
    statut           VARCHAR(20) NOT NULL DEFAULT 'A_FACTURER' CHECK (statut IN ('A_FACTURER', 'EN_ATTENTE', 'FACTUREE', 'REGLEE')),
    cle              VARCHAR(80) UNIQUE,
    demande          TEXT,
    vente_id         BIGINT,
    vente_reference  VARCHAR(40),
    total_ttc        NUMERIC(15, 2),
    facturee_le      TIMESTAMPTZ,
    reglement_cle    VARCHAR(80) UNIQUE,
    reglee_le        TIMESTAMPTZ,
    UNIQUE (bail_id, numero),
    CHECK (periode_fin >= periode_debut)
);

-- Prix du mètre cube d'eau et du kWh refacturés, par établissement.
CREATE TABLE tarifs_compteurs (
    etablissement_id BIGINT NOT NULL REFERENCES etablissements(id),
    type             VARCHAR(20) NOT NULL CHECK (type IN ('EAU', 'ELECTRICITE')),
    prix_unite       NUMERIC(15, 4) NOT NULL CHECK (prix_unite >= 0),
    PRIMARY KEY (etablissement_id, type)
);

CREATE TABLE releves_compteurs (
    id            BIGSERIAL PRIMARY KEY,
    unite_id      BIGINT NOT NULL REFERENCES unites(id),
    type          VARCHAR(20) NOT NULL CHECK (type IN ('EAU', 'ELECTRICITE')),
    date_releve   DATE NOT NULL,
    index_valeur  NUMERIC(15, 3) NOT NULL CHECK (index_valeur >= 0),
    -- Échéance qui a refacturé la consommation jusqu'à ce relevé (null : pas encore refacturée).
    echeance_id   BIGINT REFERENCES echeances_bail(id),
    notes         VARCHAR(255),
    cree_par      VARCHAR(255),
    cree_le       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (unite_id, type, date_releve)
);

-- Inventaire type d'un logement : repris dans chaque état des lieux d'entrée.
CREATE TABLE inventaires_modeles (
    id        BIGSERIAL PRIMARY KEY,
    unite_id  BIGINT NOT NULL REFERENCES unites(id) ON DELETE CASCADE,
    piece     VARCHAR(60) NOT NULL,
    element   VARCHAR(120) NOT NULL,
    quantite  INTEGER NOT NULL DEFAULT 1 CHECK (quantite > 0),
    ordre     INTEGER NOT NULL DEFAULT 0
);

-- États des lieux d'entrée et de sortie (un de chaque par séjour) ; la sortie propose une retenue sur la caution.
CREATE TABLE etats_des_lieux (
    id                BIGSERIAL PRIMARY KEY,
    reservation_id    BIGINT NOT NULL REFERENCES reservations(id),
    type              VARCHAR(10) NOT NULL CHECK (type IN ('ENTREE', 'SORTIE')),
    date_etat         TIMESTAMPTZ NOT NULL DEFAULT now(),
    observations      TEXT,
    valide            BOOLEAN NOT NULL DEFAULT FALSE,
    valide_le         TIMESTAMPTZ,
    cree_par          VARCHAR(255),
    cree_le           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (reservation_id, type)
);

CREATE TABLE etats_des_lieux_lignes (
    id            BIGSERIAL PRIMARY KEY,
    etat_id       BIGINT NOT NULL REFERENCES etats_des_lieux(id) ON DELETE CASCADE,
    piece         VARCHAR(60) NOT NULL,
    element       VARCHAR(120) NOT NULL,
    quantite      INTEGER NOT NULL DEFAULT 1 CHECK (quantite >= 0),
    etat          VARCHAR(12) NOT NULL DEFAULT 'BON' CHECK (etat IN ('NEUF', 'BON', 'USAGE', 'ABIME', 'MANQUANT')),
    commentaire   VARCHAR(255),
    cout_retenue  NUMERIC(15, 2) NOT NULL DEFAULT 0 CHECK (cout_retenue >= 0),
    ordre         INTEGER NOT NULL DEFAULT 0
);
