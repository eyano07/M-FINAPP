-- Projets (27/11/2026) : la logistique crée le projet et sa liste de besoins, le comptable en tire le budget secondaire
-- (un sous-compte « Projet … » par besoin : 6588 pour les achats divers, 6058 ou 21-24 pour les acquisitions), le DA le
-- valide, le DFIN l'exécute. Les achats payés sur ces sous-comptes alimentent la fiche de stock du projet, SÉPARÉE du stock
-- global : aucune variation de stock, aucune pièce comptable (les achats sont des charges, ou des immobilisations).

CREATE SEQUENCE seq_projet START 1;

CREATE TABLE projets (
    id             BIGSERIAL    PRIMARY KEY,
    code           VARCHAR(20)  NOT NULL UNIQUE,
    nom            VARCHAR(150) NOT NULL,
    description    VARCHAR(1000),
    statut         VARCHAR(12)  NOT NULL DEFAULT 'BROUILLON'
                   CHECK (statut IN ('BROUILLON', 'ENVOYE', 'RETOURNE', 'BUDGETE', 'CLOTURE')),
    motif_retour   VARCHAR(1000),
    budget_id      BIGINT       REFERENCES budgets(id),
    cree_par       VARCHAR(200),
    cree_le        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    envoye_le      TIMESTAMPTZ,
    budgete_le     TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_projets_nom ON projets (lower(nom));

-- Besoins du projet, devenus ses articles de stock une fois le budget créé (compte_id : le sous-compte « Projet … »).
-- Un article sans projet (projet_id NULL) est un achat sur un compte « Projet … » créé à la main, « à affecter ».
CREATE TABLE projet_articles (
    id              BIGSERIAL     PRIMARY KEY,
    projet_id       BIGINT        REFERENCES projets(id) ON DELETE CASCADE,
    designation     VARCHAR(200)  NOT NULL,
    unite           VARCHAR(30),
    quantite_prevue NUMERIC(15,3) NOT NULL DEFAULT 0 CHECK (quantite_prevue >= 0),
    prix_estime_usd NUMERIC(15,2) NOT NULL DEFAULT 0 CHECK (prix_estime_usd >= 0),
    nature          VARCHAR(12)   NOT NULL CHECK (nature IN ('ACHAT', 'ACQUISITION')),
    compte_parent   VARCHAR(20),
    compte_id       BIGINT        UNIQUE REFERENCES comptes_ohada(id),
    ordre           INTEGER       NOT NULL DEFAULT 0
);
CREATE INDEX idx_projet_articles_projet ON projet_articles (projet_id);

-- Mouvements de la fiche de stock projet (quantité signée, montant en USD signé). Jamais de pièce comptable.
CREATE TABLE projet_mouvements (
    id              BIGSERIAL     PRIMARY KEY,
    article_id      BIGINT        NOT NULL REFERENCES projet_articles(id) ON DELETE CASCADE,
    date_mouvement  DATE          NOT NULL DEFAULT CURRENT_DATE,
    type            VARCHAR(20)   NOT NULL CHECK (type IN ('ACHAT', 'SORTIE', 'TRANSFERT_SORTIE', 'TRANSFERT_ENTREE',
                                                           'RETOUR_FONDS', 'AJUSTEMENT')),
    quantite        NUMERIC(15,3) NOT NULL,
    montant_usd     NUMERIC(15,2) NOT NULL DEFAULT 0,
    note_id         BIGINT        REFERENCES notes_frais(id),
    ligne_note_id   BIGINT        REFERENCES lignes_note_frais(id),
    article_lie_id  BIGINT        REFERENCES projet_articles(id),
    commentaire     VARCHAR(500),
    auteur          VARCHAR(200),
    cree_le         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_projet_mouvements_article ON projet_mouvements (article_id);
-- Un achat payé n'entre qu'une fois (synchronisation idempotente).
CREATE UNIQUE INDEX ux_projet_mouvements_achat ON projet_mouvements (ligne_note_id) WHERE type = 'ACHAT';

-- Quantité achetée sur une ligne de note imputée à un compte « Projet … ».
ALTER TABLE lignes_note_frais ADD COLUMN quantite_projet NUMERIC(15,3);

-- Acquisition (fiche d'immobilisation) affectée à un projet.
ALTER TABLE immobilisations ADD COLUMN projet_id BIGINT REFERENCES projets(id);
CREATE INDEX idx_immobilisations_projet ON immobilisations (projet_id);
