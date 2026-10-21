-- ---------------------------------------------------------------------------
-- Retour de fonds sur une note de frais deja payee (reduction apres paiement).
--
-- L'emetteur d'une note PAYEE la reduit (montant d'une ligne, ou quantite d'un achat de marchandise : bouteilles,
-- casiers, kg, litres...) ; la demande reste EN_ATTENTE jusqu'a ce que le caissier recoive l'argent en especes et la
-- valide (ou la refuse, avec un motif). A la validation, le systeme passe le retour en caisse (D 571 / C comptes de la
-- note), sort du stock les marchandises rendues, rend les bouteilles vides d'un echange de consigne, met la note a jour
-- et previent les personnes concernees. La demande garde l'ancien et le nouveau de chaque ligne (historique).
--
-- Une seule demande en attente par note (index unique partiel) ; d'autres reductions restent possibles ensuite.
-- ---------------------------------------------------------------------------

CREATE TABLE retours_fonds_note (
    id             BIGSERIAL     PRIMARY KEY,
    note_id        BIGINT        NOT NULL REFERENCES notes_frais(id),
    statut         VARCHAR(20)   NOT NULL CHECK (statut IN ('EN_ATTENTE', 'VALIDE', 'REFUSE', 'ANNULE')),
    motif          VARCHAR(500)  NOT NULL,
    -- Montant rendu, TTC, dans la devise de la note.
    montant        NUMERIC(15,2) NOT NULL CHECK (montant > 0),
    demandeur_id   BIGINT        NOT NULL REFERENCES users(id),
    date_demande   TIMESTAMP     NOT NULL DEFAULT now(),
    caissier_id    BIGINT        REFERENCES users(id),
    date_decision  TIMESTAMP,
    motif_refus    VARCHAR(500),
    -- Encaissement du retour en caisse (renseigne a la validation).
    transaction_id BIGINT        REFERENCES transactions_caisse(id)
);
CREATE INDEX idx_retours_fonds_note ON retours_fonds_note (note_id);
CREATE INDEX idx_retours_fonds_statut ON retours_fonds_note (statut);
CREATE UNIQUE INDEX ux_retours_fonds_un_en_attente ON retours_fonds_note (note_id) WHERE statut = 'EN_ATTENTE';

CREATE TABLE lignes_retour_fonds (
    id                BIGSERIAL     PRIMARY KEY,
    retour_id         BIGINT        NOT NULL REFERENCES retours_fonds_note(id) ON DELETE CASCADE,
    ligne_note_id     BIGINT        NOT NULL REFERENCES lignes_note_frais(id),
    -- Montant de la ligne : prix unitaire HT pour un achat de marchandise, montant HT sinon (comme lignes_note_frais).
    ancien_montant    NUMERIC(15,2) NOT NULL,
    nouveau_montant   NUMERIC(15,2) NOT NULL CHECK (nouveau_montant >= 0 AND nouveau_montant <= ancien_montant),
    ancienne_quantite NUMERIC(15,3),
    nouvelle_quantite NUMERIC(15,3)
        CHECK (nouvelle_quantite IS NULL OR (nouvelle_quantite >= 0 AND nouvelle_quantite <= ancienne_quantite)),
    -- Part rendue de la ligne, TTC, dans la devise de la note.
    montant_retour    NUMERIC(15,2) NOT NULL CHECK (montant_retour >= 0)
);
CREATE INDEX idx_lignes_retour_fonds_retour ON lignes_retour_fonds (retour_id);

-- La transaction de caisse d'un retour designe la note qu'il reduit. Le lien note_id existant est un un-pour-un
-- (le paiement de la note) : le retour ne peut pas l'utiliser. Sert au suivi budgetaire par service et au journal.
ALTER TABLE transactions_caisse ADD COLUMN note_retour_id BIGINT REFERENCES notes_frais(id);
CREATE INDEX idx_transactions_caisse_note_retour ON transactions_caisse (note_retour_id);
