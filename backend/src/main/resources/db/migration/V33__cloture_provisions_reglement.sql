-- Corrections issues de l'audit OHADA du 18/08/2026 (A-01 à A-06) :
--  1. Source tracee sur le taux de change (A-03).
--  2. Reglement des creances clients avec lettrage (A-04, A-05).
--  3. Marqueur des pieces de reevaluation devise, reversees a chaque cloture (A-01).
--  4. Module Provisions (A-06).

-- A-03 : source du taux de change
ALTER TABLE taux_change ADD COLUMN source VARCHAR(200);

-- A-04 : reglement de creance client (une vente a credit est reglee en une fois,
-- comme le paiement d'une note de frais : coherent avec le seul autre flux de
-- reglement existant dans l'application, pas de lettrage partiel multi-paiement).
ALTER TABLE ventes ADD COLUMN piece_reglement_id BIGINT REFERENCES pieces_comptables(id);
ALTER TABLE ventes ADD COLUMN date_reglement DATE;
ALTER TABLE ventes ADD COLUMN taux_reglement NUMERIC(15,6);

-- A-01 : marqueur des pieces de reevaluation devise (latentes, a reverser au
-- debut de la periode suivante avant toute nouvelle reevaluation).
ALTER TABLE pieces_comptables ADD COLUMN reevaluation_devise BOOLEAN NOT NULL DEFAULT FALSE;

-- A-06 : module Provisions (constitution / reprise), rattache a un compte de
-- provision et un compte de dotation du plan comptable existant plutot qu'a
-- une nomenclature figee : coherent avec le reste de l'application (Immobilisation,
-- import journal) qui laisse toujours choisir le compte OHADA reel.
CREATE SEQUENCE IF NOT EXISTS seq_provision START WITH 1 INCREMENT BY 1;

CREATE TABLE provisions (
    id                    BIGSERIAL PRIMARY KEY,
    reference             VARCHAR(30) NOT NULL UNIQUE,
    libelle               VARCHAR(300) NOT NULL,
    compte_provision_id   BIGINT NOT NULL REFERENCES comptes_ohada(id),
    compte_dotation_id    BIGINT NOT NULL REFERENCES comptes_ohada(id),
    montant_constitue     NUMERIC(15,2) NOT NULL,
    montant_repris        NUMERIC(15,2) NOT NULL DEFAULT 0,
    statut                VARCHAR(20) NOT NULL DEFAULT 'CONSTITUEE',
    date_constitution     DATE NOT NULL,
    piece_constitution_id BIGINT REFERENCES pieces_comptables(id),
    created_by_id         BIGINT REFERENCES users(id),
    created_at            TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE provision_reprises (
    id              BIGSERIAL PRIMARY KEY,
    provision_id    BIGINT NOT NULL REFERENCES provisions(id),
    montant         NUMERIC(15,2) NOT NULL,
    motif           VARCHAR(300),
    date_reprise    DATE NOT NULL,
    compte_reprise_id BIGINT NOT NULL REFERENCES comptes_ohada(id),
    piece_id        BIGINT REFERENCES pieces_comptables(id),
    created_by_id   BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_provision_reprises_provision ON provision_reprises(provision_id);
