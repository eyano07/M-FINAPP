-- Module DRH — parametres de conformite au droit congolais (RDC).
--
-- Le moteur de calcul reproduit le classeur metier DEBOURS MBSC 2026, dont le
-- coeur fiscal a ete verifie conforme (bareme IPR 162 000 / 1 800 000 /
-- 3 600 000 FC a 3/15/30/40 %, plancher 2 000 FC, reduction 2 %/personne a
-- charge plafonnee a 9 — Loi de Finances 2020, Art. 84 et 89 modifies).
--
-- Cette migration n'ajoute AUCUN calcul automatique : elle fournit les
-- references legales necessaires aux controles de conformite, qui sont
-- purement consultatifs (avertissements non bloquants). Toutes les colonnes
-- ont une valeur par defaut qui preserve exactement le comportement actuel :
-- aucun bulletin deja emis n'est recalcule.

ALTER TABLE drh_parametres_paie
    -- Deductibilite de la CNSS ouvriere de la base imposable IPR : les
    -- sources fiscales consultees se contredisent et aucun texte officiel
    -- n'a permis de trancher. FALSE = comportement actuel (base = H, CNSS
    -- non deduite) ; a confirmer par le comptable/fiscaliste avant bascule.
    ADD COLUMN cnss_deductible_ipr BOOLEAN NOT NULL DEFAULT FALSE,

    -- Decret n° 25/22 du 30 mai 2025 : SMIG du manoeuvre ordinaire, porte a
    -- 21 500 FC/jour a compter de janvier 2026.
    ADD COLUMN smig_journalier_fc NUMERIC(15,2) NOT NULL DEFAULT 21500,

    -- Decret n° 25/22, Art. 5 : allocation familiale minimale par enfant =
    -- 1/27e du SMIG journalier du manoeuvre ordinaire.
    ADD COLUMN diviseur_allocation_familiale INTEGER NOT NULL DEFAULT 27,

    -- Retenues pour avances en especes : limitees au dixieme du salaire.
    ADD COLUMN plafond_retenue_pct NUMERIC(6,4) NOT NULL DEFAULT 0.10,

    -- Quota legal d'exoneration de l'indemnite de transport, exprime en
    -- courses reelles (bus/taxi) et non en pourcentage. 0 = controle
    -- desactive, faute de tarif local de reference renseigne.
    ADD COLUMN plafond_transport_exonere_fc_jour NUMERIC(15,2) NOT NULL DEFAULT 0,

    -- Code du travail, Art. 119 : duree legale hebdomadaire.
    ADD COLUMN heures_legales_hebdo INTEGER NOT NULL DEFAULT 45,

    -- Code du travail, Art. 120 : majorations des heures supplementaires
    -- (6 premieres heures, heures suivantes, repos hebdomadaire/jour ferie).
    -- Utilisees uniquement pour afficher le taux horaire de reference sur le
    -- bulletin — le montant des heures sup reste saisi manuellement.
    ADD COLUMN taux_majoration_hs1 NUMERIC(6,4) NOT NULL DEFAULT 0.30,
    ADD COLUMN taux_majoration_hs2 NUMERIC(6,4) NOT NULL DEFAULT 0.60,
    ADD COLUMN taux_majoration_hs_ferie NUMERIC(6,4) NOT NULL DEFAULT 1.00;

COMMENT ON COLUMN drh_parametres_paie.cnss_deductible_ipr IS
    'Deduire la CNSS ouvriere de la base imposable IPR. FALSE = comportement historique (classeur DEBOURS MBSC).';
COMMENT ON COLUMN drh_parametres_paie.smig_journalier_fc IS
    'SMIG journalier du manoeuvre ordinaire en FC (Decret n° 25/22 du 30/05/2025).';
COMMENT ON COLUMN drh_parametres_paie.plafond_transport_exonere_fc_jour IS
    'Plafond journalier d''exoneration de l''indemnite de transport en FC ; 0 desactive le controle.';
