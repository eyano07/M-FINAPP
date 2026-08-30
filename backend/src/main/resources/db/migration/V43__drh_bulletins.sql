-- Module DRH — bulletins de paie (module DRH_PAIE) et comptes OHADA
-- necessaires a leur comptabilisation.
--
-- Un bulletin par (employe, mois, annee) : contrainte absente de l'ancien
-- outil PayMBSC (verifiee seulement en Java, donc contournable par deux
-- requetes concurrentes) — corrigee ici au niveau base.

-- ---------------------------------------------------------------------------
-- 1. Sous-comptes ONEM/INPP, suivant le precedent 4221.1 "Masse salariale
--    MBSC" : le plan comptable OHADA n'a pas de compte dedie a ces deux
--    charges sociales specifiques a la RDC, seulement des libelles generiques
--    (431 Securite sociale, 4478 Autres impots et contributions).
-- ---------------------------------------------------------------------------
INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel) VALUES
    ('4478.1', 'ONEM à payer', 'PASSIF', 4, TRUE, TRUE, TRUE),
    ('4478.2', 'INPP à payer', 'PASSIF', 4, TRUE, TRUE, TRUE)
ON CONFLICT (numero) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Bulletins de paie
-- ---------------------------------------------------------------------------
CREATE TABLE drh_bulletins_paie (
    id                        BIGSERIAL PRIMARY KEY,
    employe_id                BIGINT        NOT NULL REFERENCES drh_employes(id),
    mois                      INTEGER       NOT NULL,
    annee                     INTEGER       NOT NULL,

    salaire_base_usd          NUMERIC(15,2) NOT NULL,
    presence_pct              NUMERIC(6,2)  NOT NULL DEFAULT 100,
    nombre_enfants            INTEGER       NOT NULL DEFAULT 0,
    conge                     NUMERIC(15,2) NOT NULL DEFAULT 0,
    heures_supplementaires    NUMERIC(15,2) NOT NULL DEFAULT 0,
    allocation_familiale      NUMERIC(15,2) NOT NULL DEFAULT 0,
    prime_diplome             NUMERIC(15,2) NOT NULL DEFAULT 0,
    prime_anciennete          NUMERIC(15,2) NOT NULL DEFAULT 0,
    prime_rendement           NUMERIC(15,2) NOT NULL DEFAULT 0,
    avance_salaire            NUMERIC(15,2) NOT NULL DEFAULT 0,
    pret                      NUMERIC(15,2) NOT NULL DEFAULT 0,

    salaire_brut              NUMERIC(15,2),
    indemnite_logement        NUMERIC(15,2),
    indemnite_transport       NUMERIC(15,2),
    base_imposable_inpp       NUMERIC(15,2),
    base_imposable_inss       NUMERIC(15,2),
    base_imposable_ipr        NUMERIC(15,2),
    cnss_ouvriere             NUMERIC(15,2),
    cnss_patronale            NUMERIC(15,2),
    onem                      NUMERIC(15,2),
    total_inss                NUMERIC(15,2),
    inpp                      NUMERIC(15,2),
    ipr                       NUMERIC(15,2),
    salaire_net               NUMERIC(15,2),
    taux_change_applique      NUMERIC(18,4),
    net_fc                    NUMERIC(15,2),

    date_paiement             DATE          NOT NULL,
    statut                    VARCHAR(20)   NOT NULL DEFAULT 'BROUILLON',
    piece_comptable_id        BIGINT REFERENCES pieces_comptables(id),
    created_by_id             BIGINT REFERENCES users(id),
    created_at                TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT uq_drh_bulletin_employe_periode UNIQUE (employe_id, mois, annee),
    CONSTRAINT chk_drh_bulletin_mois CHECK (mois BETWEEN 1 AND 12),
    CONSTRAINT chk_drh_bulletin_statut CHECK (statut IN ('BROUILLON', 'VALIDE', 'ANNULE'))
);

CREATE INDEX idx_drh_bulletins_periode ON drh_bulletins_paie (annee, mois);
CREATE INDEX idx_drh_bulletins_employe ON drh_bulletins_paie (employe_id);

COMMENT ON TABLE drh_bulletins_paie IS
    'Bulletins de paie mensuels. L''etat "comptabilise" se lit sur pieces_comptables.statut via piece_comptable_id, pas sur une colonne dediee de cette table.';
COMMENT ON COLUMN drh_bulletins_paie.nombre_enfants IS
    'Copie depuis drh_employes.nombre_enfants a la creation, figee ensuite pour que la reduction IPR du bulletin reste reproductible.';
COMMENT ON COLUMN drh_bulletins_paie.taux_change_applique IS
    'Taux de change (FC pour 1 USD) fige au moment du calcul, meme principe que grand_livre.taux_applique.';
