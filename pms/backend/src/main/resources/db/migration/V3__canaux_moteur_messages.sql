-- ---------------------------------------------------------------------------
-- Phase 2 : canaux iCal, moteur de réservation direct, messages automatiques.
-- ---------------------------------------------------------------------------

-- Flux iCal exporté par unité : adresse secrète à donner à Booking.com, Airbnb, Expedia/VRBO.
ALTER TABLE unites ADD COLUMN IF NOT EXISTS jeton_ical VARCHAR(64) UNIQUE;
UPDATE unites SET jeton_ical = md5(random()::text || clock_timestamp()::text || id::text) WHERE jeton_ical IS NULL;
ALTER TABLE unites ALTER COLUMN jeton_ical SET DEFAULT md5(random()::text || clock_timestamp()::text);

-- Calendriers importés : un par unité et par canal.
CREATE TABLE canaux_ical (
    id               BIGSERIAL PRIMARY KEY,
    unite_id         BIGINT NOT NULL REFERENCES unites(id),
    canal            VARCHAR(20) NOT NULL CHECK (canal IN ('BOOKING', 'AIRBNB', 'EXPEDIA', 'AUTRE')),
    url              VARCHAR(1000) NOT NULL,
    actif            BOOLEAN NOT NULL DEFAULT TRUE,
    dernier_import   TIMESTAMPTZ,
    dernier_statut   VARCHAR(20),
    dernier_message  VARCHAR(1000),
    nb_evenements    INTEGER,
    cree_le          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (unite_id, canal)
);

-- Réservation venue d'un canal : identifiant de l'événement iCal (mise à jour, annulation si retiré du flux).
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS canal_ical_id BIGINT REFERENCES canaux_ical(id);
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS uid_externe VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS uq_reservations_uid_externe ON reservations (canal_ical_id, uid_externe) WHERE uid_externe IS NOT NULL;
-- Demande faite sur la page publique : en option jusqu'à la confirmation par la réception.
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS demande_publique BOOLEAN NOT NULL DEFAULT FALSE;

-- Conflits rencontrés à l'import (dates déjà prises dans l'hôtellerie) : à traiter par la réception.
CREATE TABLE alertes_canaux (
    id            BIGSERIAL PRIMARY KEY,
    canal_ical_id BIGINT NOT NULL REFERENCES canaux_ical(id) ON DELETE CASCADE,
    uid_externe   VARCHAR(255),
    debut         DATE,
    fin           DATE,
    message       VARCHAR(1000) NOT NULL,
    traitee       BOOLEAN NOT NULL DEFAULT FALSE,
    cree_le       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (canal_ical_id, uid_externe, debut)
);

-- Modèles de messages (variables : {client} {reference} {etablissement} {unite} {arrivee} {depart} {code} {total}).
CREATE TABLE messages_modeles (
    type     VARCHAR(30) PRIMARY KEY CHECK (type IN ('CONFIRMATION', 'RAPPEL_ARRIVEE', 'CODE_ACCES', 'REMERCIEMENT')),
    actif    BOOLEAN NOT NULL DEFAULT TRUE,
    sujet    VARCHAR(200) NOT NULL,
    corps    TEXT NOT NULL,
    maj_le   TIMESTAMPTZ,
    maj_par  VARCHAR(255)
);
INSERT INTO messages_modeles (type, sujet, corps) VALUES
 ('CONFIRMATION', 'Confirmation de votre réservation {reference}',
  'Votre réservation à {etablissement} est confirmée : arrivée le {arrivee}, départ le {depart}. Nous vous attendons avec plaisir.'),
 ('RAPPEL_ARRIVEE', 'Votre arrivée demain à {etablissement}',
  'Nous vous rappelons votre arrivée le {arrivee} (réservation {reference}). La réception est à votre disposition pour toute demande.'),
 ('CODE_ACCES', 'Votre accès à {unite}',
  'Bienvenue ! Votre unité : {unite}. Code d''accès ou clé : {code}. Départ prévu le {depart}.'),
 ('REMERCIEMENT', 'Merci de votre séjour à {etablissement}',
  'Merci d''avoir séjourné chez nous (réservation {reference}). Au plaisir de vous accueillir à nouveau.');

-- Messages envoyés (un par réservation et par type : jamais de doublon).
CREATE TABLE messages_envoyes (
    id              BIGSERIAL PRIMARY KEY,
    reservation_id  BIGINT NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    type            VARCHAR(30) NOT NULL,
    destinataire    VARCHAR(150) NOT NULL,
    statut          VARCHAR(20) NOT NULL CHECK (statut IN ('ENVOYE', 'ECHEC')),
    detail          VARCHAR(1000),
    envoye_le       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (reservation_id, type)
);
