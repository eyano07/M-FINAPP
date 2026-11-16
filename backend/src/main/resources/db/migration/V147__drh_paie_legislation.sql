-- =====================================================================
-- V147 : paie conforme à la législation congolaise ; audit DRH du 15/11/2026, D-05 à D-08 (corrections du 16/11)
-- =====================================================================
-- Formule : CNSS patronale par branche (décret n° 18/041 du 24/11/2018 : pensions 5 %, prestations familiales
--   6,5 %, risques professionnels 1,5 %) ; INPP selon l'effectif (3 %, 2 %, 1 %) ; conjoint à charge compté dans la
--   réduction familiale de l'IPR ; CNSS salariale déduite de la base IPR ; nombre de personnes à charge figé sur
--   le bulletin ; parts patronales CNSS par branche sur le bulletin.
-- D-08 : CNSS créditée par branche (4311, 4312, 4313) au lieu de 4315 (gardé pour l'historique).
-- D-07 : ONEM et INPP sous 4428 « Autres impôts et taxes » (44281, 44282) au lieu de 44781 / 44782 (historique).
-- D-06 : un compte d'avances par agent sous 4211 « Personnel, avances » (42111, 42112…).
-- D-05 : provision pour congés payés de fin d'exercice (table drh_provisions_conges).
-- Sous-comptes : numéro du parent suivi du rang, sans point ni zéro.
-- =====================================================================

-- --- Paramètres de paie -----------------------------------------------------------------------------------------
ALTER TABLE drh_parametres_paie ADD COLUMN IF NOT EXISTS taux_cnss_pension_patronale NUMERIC(6, 4) NOT NULL DEFAULT 0.05;
ALTER TABLE drh_parametres_paie ADD COLUMN IF NOT EXISTS taux_cnss_pf NUMERIC(6, 4) NOT NULL DEFAULT 0.065;
ALTER TABLE drh_parametres_paie ADD COLUMN IF NOT EXISTS taux_cnss_rp NUMERIC(6, 4) NOT NULL DEFAULT 0.015;
ALTER TABLE drh_parametres_paie ADD COLUMN IF NOT EXISTS taux_inpp_51_300 NUMERIC(6, 4) NOT NULL DEFAULT 0.02;
ALTER TABLE drh_parametres_paie ADD COLUMN IF NOT EXISTS taux_inpp_plus_300 NUMERIC(6, 4) NOT NULL DEFAULT 0.01;
ALTER TABLE drh_parametres_paie ADD COLUMN IF NOT EXISTS conjoint_a_charge BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE drh_parametres_paie ADD COLUMN IF NOT EXISTS jours_conge_par_mois NUMERIC(6, 2) NOT NULL DEFAULT 1;
ALTER TABLE drh_parametres_paie ADD COLUMN IF NOT EXISTS jours_conge_par_tranche_5_ans NUMERIC(6, 2) NOT NULL DEFAULT 1;

-- La CNSS patronale totale est désormais la somme des trois branches ; la CNSS salariale est déductible de la base
-- IPR (PwC Tax Summaries RDC : « employee's share only is deductible from the IPR taxable basis »).
UPDATE drh_parametres_paie
   SET taux_cnss_pension_patronale = 0.05, taux_cnss_pf = 0.065, taux_cnss_rp = 0.015,
       taux_cnss_patronale = 0.05 + 0.065 + 0.015,
       cnss_deductible_ipr = TRUE;

-- --- Bulletins : personnes à charge figées et parts patronales CNSS par branche -----------------------------------
ALTER TABLE drh_bulletins_paie ADD COLUMN IF NOT EXISTS personnes_a_charge INTEGER;
ALTER TABLE drh_bulletins_paie ADD COLUMN IF NOT EXISTS cnss_patronale_pension NUMERIC(15, 2);
ALTER TABLE drh_bulletins_paie ADD COLUMN IF NOT EXISTS cnss_patronale_pf NUMERIC(15, 2);
ALTER TABLE drh_bulletins_paie ADD COLUMN IF NOT EXISTS cnss_patronale_rp NUMERIC(15, 2);
-- Les anciens bulletins gardent leur nombre d'enfants comme nombre de personnes à charge (calcul d'origine).
UPDATE drh_bulletins_paie SET personnes_a_charge = nombre_enfants WHERE personnes_a_charge IS NULL;

-- --- D-07 : ONEM et INPP sous 4428 « Autres impôts et taxes » -----------------------------------------------------
INSERT INTO comptes_ohada (numero, libelle, type, classe, parent_id, manuel, imputable, actif, contenu, commentaires)
SELECT v.numero, v.libelle, 'PASSIF', 4, p.id, TRUE, TRUE, TRUE, v.contenu,
       'Compte créé par la migration V147 (audit DRH du 15/11/2026, D-07) : la taxe était créditée en ' || v.ancien
           || ', sous 447 « État, impôts retenus à la source », alors qu''elle est à la charge de l''employeur.'
FROM (VALUES
        ('44281', 'ONEM à payer', '44781',
         'Cotisation ONEM (Office national de l''emploi), à la charge de l''employeur : créditée par la constatation '
             || 'de la paie, débitée par la note de versement à l''ONEM.'),
        ('44282', 'INPP à payer', '44782',
         'Cotisation INPP (Institut national de préparation professionnelle), à la charge de l''employeur : créditée '
             || 'par la constatation de la paie, débitée par la note de versement à l''INPP.')
     ) AS v(numero, libelle, ancien, contenu)
JOIN comptes_ohada p ON p.numero = '4428'
ON CONFLICT (numero) DO NOTHING;

-- Comptes que la paie et la provision pour congés mouvementent : des comptes de détail, actifs.
UPDATE comptes_ohada SET actif = TRUE, imputable = TRUE
 WHERE numero IN ('4311', '4312', '4313', '44281', '44282', '4211', '4281', '4382');

-- --- D-06 : un compte d'avances par agent sous 4211 ---------------------------------------------------------------
ALTER TABLE drh_employes ADD COLUMN IF NOT EXISTS compte_avance VARCHAR(20);

DO $$
DECLARE
    collectif comptes_ohada%ROWTYPE;
    e RECORD;
    rang INTEGER;
    numero_libre VARCHAR(20);
BEGIN
    SELECT * INTO collectif FROM comptes_ohada WHERE numero = '4211';
    IF NOT FOUND THEN
        RETURN;   -- plan comptable sans 4211 : les fiches restent sans compte d'avances (la retenue retombe sur 4211)
    END IF;
    rang := 0;
    FOR e IN SELECT id, matricule, nom_complet FROM drh_employes WHERE compte_avance IS NULL ORDER BY id LOOP
        LOOP
            rang := rang + 1;
            numero_libre := '4211' || rang;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM comptes_ohada WHERE numero = numero_libre);
        END LOOP;
        INSERT INTO comptes_ohada (numero, libelle, type, classe, parent_id, manuel, imputable, actif, contenu, commentaires)
        VALUES (numero_libre, LEFT('Avances : ' || e.nom_complet, 200), collectif.type, collectif.classe,
                collectif.id, TRUE, TRUE, TRUE,
                'Sous-compte de 4211 « ' || collectif.libelle || ' », ouvert pour l''agent « ' || e.nom_complet
                    || ' » (' || e.matricule || ') : débité par la note de frais qui lui verse une avance, crédité '
                    || 'par la retenue de l''avance sur son bulletin.',
                'Compte créé automatiquement par la migration V147 pour une fiche employé déjà enregistrée.');
        UPDATE drh_employes SET compte_avance = numero_libre WHERE id = e.id;
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_drh_employes_compte_avance ON drh_employes (compte_avance);

-- --- D-05 : provisions pour congés payés ------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS drh_provisions_conges (
    id                BIGSERIAL PRIMARY KEY,
    annee             INTEGER        NOT NULL,
    date_arrete       DATE           NOT NULL,
    montant_conges    NUMERIC(15, 2) NOT NULL,
    montant_charges   NUMERIC(15, 2) NOT NULL,
    piece_id          BIGINT REFERENCES pieces_comptables (id),
    piece_extourne_id BIGINT REFERENCES pieces_comptables (id),
    statut            VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    detail            TEXT,
    created_by_id     BIGINT REFERENCES users (id),
    created_at        TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- Une seule provision active par exercice ; une provision annulée peut être reconstituée.
CREATE UNIQUE INDEX IF NOT EXISTS ux_drh_provisions_conges_annee_active
    ON drh_provisions_conges (annee) WHERE statut = 'ACTIVE';
