-- =====================================================================
-- V136 : clôture, numérotation, validation et journal des événements comptables
-- (audit SYSCOHADA du 10/11/2026 : C-03, C-04, C-05, C-06, C-09, C-10, C-14, C-19)
-- =====================================================================

-- ---------------------------------------------------------------------
-- C-04 : la pièce de clôture est marquée, pour être exclue du compte de résultat, des soldes
-- intermédiaires de gestion, du tableau des flux et de la colonne N-1.
-- ---------------------------------------------------------------------
ALTER TABLE pieces_comptables ADD COLUMN IF NOT EXISTS cloture_exercice BOOLEAN NOT NULL DEFAULT FALSE;

-- Pièces de clôture déjà passées (libellé posé par ClotureAnnuelleService). La pièce est comptabilisée :
-- les verrous de V135 sont levés pour cette seule transaction, le temps de poser le marqueur.
SELECT set_config('app.reecriture_comptable', 'on', true);
UPDATE pieces_comptables SET cloture_exercice = TRUE
 WHERE journal = 'OPERATIONS_DIVERSES' AND libelle LIKE 'Clôture de l''exercice au %' AND piece_origine_id IS NULL;

-- ---------------------------------------------------------------------
-- C-06 : qui a validé la pièce, et quand. Les pièces déjà comptabilisées gardent ces colonnes vides
-- (inconnu) ; les pièces nées comptabilisées (flux automatiques) les reçoivent à la création.
-- ---------------------------------------------------------------------
ALTER TABLE pieces_comptables ADD COLUMN IF NOT EXISTS comptabilisee_par_id BIGINT REFERENCES users(id);
ALTER TABLE pieces_comptables ADD COLUMN IF NOT EXISTS comptabilisee_le TIMESTAMP;

-- Restauration d'une pièce annulée par erreur : un nouveau brouillon identique, relié à la pièce d'origine.
ALTER TABLE pieces_comptables ADD COLUMN IF NOT EXISTS piece_restauree_id BIGINT REFERENCES pieces_comptables(id);

-- ---------------------------------------------------------------------
-- C-05 : numérotation continue par exercice (année de la date de la pièce), attribuée à la validation
-- dans la même transaction : un numéro définitif n'est jamais perdu. Le brouillon porte un numéro
-- provisoire BR-NNNNNN.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS compteurs_pieces (
    annee    INTEGER PRIMARY KEY,
    dernier  BIGINT  NOT NULL
);
INSERT INTO compteurs_pieces (annee, dernier)
SELECT substring(reference FROM 4 FOR 4)::int, max(substring(reference FROM 9)::bigint)
  FROM pieces_comptables
 WHERE reference ~ '^PC-[0-9]{4}-[0-9]+$'
 GROUP BY 1
ON CONFLICT (annee) DO UPDATE SET dernier = greatest(compteurs_pieces.dernier, excluded.dernier);

CREATE SEQUENCE IF NOT EXISTS seq_piece_brouillon START WITH 1 INCREMENT BY 1;

-- ---------------------------------------------------------------------
-- C-03 : clôture définitive de l'exercice, distincte du verrou de période, et historique de tous les
-- changements de date (aucune ligne n'est jamais modifiée ni supprimée).
-- C-06 : dérogation à la validation par une autre personne, paramétrable et tracée.
-- ---------------------------------------------------------------------
ALTER TABLE parametres_comptables ADD COLUMN IF NOT EXISTS date_cloture_exercice DATE;
ALTER TABLE parametres_comptables ADD COLUMN IF NOT EXISTS auto_validation_autorisee BOOLEAN NOT NULL DEFAULT FALSE;

-- Exercices déjà clôturés : la dernière pièce de clôture fixe la date de clôture définitive.
UPDATE parametres_comptables p SET date_cloture_exercice = (
    SELECT max(date_piece) FROM pieces_comptables WHERE cloture_exercice AND statut = 'COMPTABILISEE')
 WHERE p.id = 1;

CREATE TABLE IF NOT EXISTS historique_clotures (
    id             BIGSERIAL    PRIMARY KEY,
    type_cloture   VARCHAR(20)  NOT NULL,   -- VERROU (période) ou EXERCICE (clôture définitive)
    ancienne_date  DATE,
    nouvelle_date  DATE,
    motif          VARCHAR(500),
    auteur_id      BIGINT       REFERENCES users(id),
    auteur         VARCHAR(150) NOT NULL,
    cree_le        TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_historique_clotures_type CHECK (type_cloture IN ('VERROU', 'EXERCICE', 'PARAMETRE'))
);

-- ---------------------------------------------------------------------
-- C-09 : journal des événements comptables en ajout seul, chaîné par empreinte SHA-256. Chaque ligne
-- porte l'empreinte de la précédente : modifier ou supprimer une ligne casse la chaîne, ce que
-- verifier_journal_evenements() détecte. La base refuse en outre toute modification ou suppression.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS journal_evenements_comptables (
    id                    BIGSERIAL     PRIMARY KEY,
    horodatage            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    auteur_id             BIGINT,
    auteur                VARCHAR(150)  NOT NULL,
    action                VARCHAR(50)   NOT NULL,
    objet_type            VARCHAR(50)   NOT NULL,
    objet_id              BIGINT,
    objet_reference       VARCHAR(100),
    avant                 JSONB,
    apres                 JSONB,
    motif                 VARCHAR(1000),
    empreinte_precedente  CHAR(64),
    empreinte             CHAR(64)      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_journal_evenements_objet ON journal_evenements_comptables (objet_type, objet_id);
CREATE INDEX IF NOT EXISTS idx_journal_evenements_horodatage ON journal_evenements_comptables (horodatage);

CREATE OR REPLACE FUNCTION empreinte_evenement(precedente text, e journal_evenements_comptables) RETURNS text AS $$
    SELECT encode(sha256(convert_to(concat_ws('|',
        coalesce(precedente, ''), e.id, to_char(e.horodatage AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'),
        coalesce(e.auteur_id::text, ''), e.auteur, e.action, e.objet_type, coalesce(e.objet_id::text, ''),
        coalesce(e.objet_reference, ''), coalesce(e.avant::text, ''), coalesce(e.apres::text, ''),
        coalesce(e.motif, '')), 'UTF8')), 'hex');
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION chainer_evenement() RETURNS trigger AS $$
BEGIN
    IF reecriture_comptable_autorisee() THEN
        RETURN NEW;   -- copie en ligne : les lignes arrivent avec leur empreinte d'origine
    END IF;
    -- Un seul chaînage à la fois : la ligne précédente est bien la dernière écrite.
    PERFORM pg_advisory_xact_lock(hashtext('journal_evenements_comptables'));
    SELECT empreinte INTO NEW.empreinte_precedente
      FROM journal_evenements_comptables ORDER BY id DESC LIMIT 1;
    NEW.horodatage := now();
    NEW.empreinte := empreinte_evenement(NEW.empreinte_precedente, NEW);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS chainage_evenement ON journal_evenements_comptables;
CREATE TRIGGER chainage_evenement BEFORE INSERT ON journal_evenements_comptables
    FOR EACH ROW EXECUTE FUNCTION chainer_evenement();

CREATE OR REPLACE FUNCTION interdire_reecriture() RETURNS trigger AS $$
BEGIN
    IF reecriture_comptable_autorisee() THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'La table % est en ajout seul : aucune ligne ne se modifie ni ne se supprime.', TG_TABLE_NAME
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS ajout_seul ON journal_evenements_comptables;
CREATE TRIGGER ajout_seul BEFORE UPDATE OR DELETE ON journal_evenements_comptables
    FOR EACH ROW EXECUTE FUNCTION interdire_reecriture();
DROP TRIGGER IF EXISTS ajout_seul ON historique_clotures;
CREATE TRIGGER ajout_seul BEFORE UPDATE OR DELETE ON historique_clotures
    FOR EACH ROW EXECUTE FUNCTION interdire_reecriture();

-- Premier identifiant dont l'empreinte ne correspond plus (null : chaîne intacte).
CREATE OR REPLACE FUNCTION verifier_journal_evenements() RETURNS bigint AS $$
DECLARE
    e journal_evenements_comptables%ROWTYPE;
    precedente text := NULL;
BEGIN
    FOR e IN SELECT * FROM journal_evenements_comptables ORDER BY id LOOP
        IF e.empreinte_precedente IS DISTINCT FROM precedente
           OR e.empreinte <> empreinte_evenement(precedente, e) THEN
            RETURN e.id;
        END IF;
        precedente := e.empreinte;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

-- ---------------------------------------------------------------------
-- C-14 : la paie est constatée à la clôture du mois (engagement), par le DFIN ; le paiement de la note
-- de paie ne solde plus que les salaires nets.
-- ---------------------------------------------------------------------
ALTER TABLE drh_bulletins_paie ADD COLUMN IF NOT EXISTS constatee_a_la_cloture BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE drh_bulletins_paie ADD COLUMN IF NOT EXISTS cloture_par_id BIGINT REFERENCES users(id);

-- ---------------------------------------------------------------------
-- C-03 et C-10 : double validation. Reculer ou lever le verrou de période, et clôturer l'exercice, se
-- font en deux temps : le DFIN demande (avec un motif), le DA ou le DG approuve ou refuse — jamais la
-- même personne. Avancer le verrou reste immédiat.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS demandes_cloture (
    id               BIGSERIAL     PRIMARY KEY,
    type_demande     VARCHAR(20)   NOT NULL,
    date_demandee    DATE,
    motif            VARCHAR(1000) NOT NULL,
    statut           VARCHAR(20)   NOT NULL DEFAULT 'EN_ATTENTE',
    demande_par_id   BIGINT        NOT NULL REFERENCES users(id),
    demande_le       TIMESTAMP     NOT NULL DEFAULT now(),
    decide_par_id    BIGINT        REFERENCES users(id),
    decide_le        TIMESTAMP,
    commentaire      VARCHAR(1000),
    CONSTRAINT ck_demandes_cloture_type CHECK (type_demande IN ('RECUL_VERROU', 'EXERCICE')),
    CONSTRAINT ck_demandes_cloture_statut CHECK (statut IN ('EN_ATTENTE', 'APPROUVEE', 'REFUSEE', 'RETIREE'))
);
CREATE INDEX IF NOT EXISTS idx_demandes_cloture_statut ON demandes_cloture (statut);
