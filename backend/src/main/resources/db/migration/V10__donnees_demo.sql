-- ============================================================
-- MBSC Finapp — V10 : Données de démonstration (idempotent)
-- ============================================================

-- ── Taux de change du jour ────────────────────────────────────
INSERT INTO taux_change (taux, date_effet, note)
VALUES (2840.00, CURRENT_DATE, 'Taux initial de démonstration')
ON CONFLICT DO NOTHING;

-- ── Bloc principal ────────────────────────────────────────────
DO $$
DECLARE
    id_directeur BIGINT; id_da BIGINT; id_dfin BIGINT; id_caissier BIGINT;
    id_c601 BIGINT; id_c611 BIGINT; id_c622 BIGINT; id_c628 BIGINT;
    id_c624 BIGINT; id_c6588 BIGINT; id_c571 BIGINT; id_c758 BIGINT;
    id_nf1 BIGINT; id_nf2 BIGINT; id_nf3 BIGINT; id_nf4 BIGINT;
    id_nf5 BIGINT; id_nf7 BIGINT;
    id_tx1 BIGINT; id_tx2 BIGINT;
    id_bud1 BIGINT;
BEGIN

SELECT id INTO id_directeur FROM users WHERE email = 'directeur@mbsc.cd';
SELECT id INTO id_da         FROM users WHERE email = 'da@mbsc.cd';
SELECT id INTO id_dfin       FROM users WHERE email = 'dfin@mbsc.cd';
SELECT id INTO id_caissier   FROM users WHERE email = 'caissier@mbsc.cd';

-- Si les utilisateurs n'existent pas encore (DataSeeder n'a pas encore tourné),
-- on sort proprement : les données de démo seront insérées au prochain redémarrage.
IF id_directeur IS NULL OR id_dfin IS NULL OR id_caissier IS NULL THEN
    RAISE NOTICE 'V10 : utilisateurs absents, données de démo ignorées pour cet appel.';
    RETURN;
END IF;

SELECT id INTO id_c601  FROM comptes_ohada WHERE numero = '601';
SELECT id INTO id_c611  FROM comptes_ohada WHERE numero = '611';
SELECT id INTO id_c622  FROM comptes_ohada WHERE numero = '622';
SELECT id INTO id_c628  FROM comptes_ohada WHERE numero = '628';
SELECT id INTO id_c624  FROM comptes_ohada WHERE numero = '624';
SELECT id INTO id_c6588 FROM comptes_ohada WHERE numero = '6588';
SELECT id INTO id_c571  FROM comptes_ohada WHERE numero = '571';
SELECT id INTO id_c758  FROM comptes_ohada WHERE numero = '758';

-- ─── Notes de frais (ON CONFLICT DO NOTHING) ────────────────

INSERT INTO notes_frais (reference, objet, description, montant, devise, statut,
    createur_id, compte_imputation_id, priorite, date_creation, date_maj)
VALUES ('NF-2026-000001', 'Achat fournitures bureau',
    'Stylos, ramettes papier A4 et enveloppes pour le département administratif.',
    45000.00, 'CDF', 'PAYEE', id_directeur, id_c601, 'HAUTE',
    NOW() - INTERVAL '12 days', NOW() - INTERVAL '2 days')
ON CONFLICT (reference) DO NOTHING;
SELECT id INTO id_nf1 FROM notes_frais WHERE reference = 'NF-2026-000001';

INSERT INTO notes_frais (reference, objet, description, montant, devise, statut,
    createur_id, compte_imputation_id, priorite, date_creation, date_maj)
VALUES ('NF-2026-000002', 'Transport mission terrain Kinshasa',
    'Frais de déplacement pour supervision des chantiers — 3 jours.',
    120000.00, 'CDF', 'TRANSMISE_CAISSE', id_directeur, id_c611, 'HAUTE',
    NOW() - INTERVAL '7 days', NOW() - INTERVAL '1 day')
ON CONFLICT (reference) DO NOTHING;
SELECT id INTO id_nf2 FROM notes_frais WHERE reference = 'NF-2026-000002';

INSERT INTO notes_frais (reference, objet, description, montant, devise, statut,
    createur_id, compte_imputation_id, priorite, date_creation, date_maj)
VALUES ('NF-2026-000003', 'Abonnement logiciels SaaS',
    'Renouvellement annuel Microsoft 365 et Zoom — 5 licences.',
    284000.00, 'CDF', 'TRANSMISE_CAISSE', id_dfin, id_c628, 'MOYENNE',
    NOW() - INTERVAL '5 days', NOW() - INTERVAL '12 hours')
ON CONFLICT (reference) DO NOTHING;
SELECT id INTO id_nf3 FROM notes_frais WHERE reference = 'NF-2026-000003';

INSERT INTO notes_frais (reference, objet, description, montant, devise, statut,
    createur_id, compte_imputation_id, date_creation, date_maj)
VALUES ('NF-2026-000004', 'Réparation véhicule de service',
    'Remplacement plaquettes de frein et vidange — 4x4 terrain.',
    67500.00, 'CDF', 'VERIFIEE_DFIN', id_directeur, id_c624,
    NOW() - INTERVAL '3 days', NOW() - INTERVAL '1 day')
ON CONFLICT (reference) DO NOTHING;
SELECT id INTO id_nf4 FROM notes_frais WHERE reference = 'NF-2026-000004';

INSERT INTO notes_frais (reference, objet, description, montant, devise, statut,
    createur_id, compte_imputation_id, date_creation, date_maj)
VALUES ('NF-2026-000005', 'Frais de réception clients',
    'Déjeuner de travail avec partenaires — 8 personnes.',
    52000.00, 'CDF', 'SOUMISE', id_directeur, id_c6588,
    NOW() - INTERVAL '1 day', NOW() - INTERVAL '10 hours')
ON CONFLICT (reference) DO NOTHING;
SELECT id INTO id_nf5 FROM notes_frais WHERE reference = 'NF-2026-000005';

INSERT INTO notes_frais (reference, objet, description, montant, devise, statut,
    createur_id, date_creation, date_maj)
VALUES ('NF-2026-000006', 'Formation Excel avancé',
    'Session pour 3 agents comptables, 2 jours.',
    38000.00, 'CDF', 'BROUILLON', id_dfin,
    NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours')
ON CONFLICT (reference) DO NOTHING;

INSERT INTO notes_frais (reference, objet, description, montant, devise, statut,
    createur_id, compte_imputation_id, date_creation, date_maj)
VALUES ('NF-2026-000007', 'Achat mobilier de bureau',
    'Chaises ergonomiques — 10 unités open space.',
    230000.00, 'CDF', 'REJETEE_DA', id_directeur, id_c601,
    NOW() - INTERVAL '14 days', NOW() - INTERVAL '9 days')
ON CONFLICT (reference) DO NOTHING;
SELECT id INTO id_nf7 FROM notes_frais WHERE reference = 'NF-2026-000007';

-- ─── Observations (insérer seulement si note existe et pas déjà d'obs) ──

IF NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = id_nf1) THEN
    INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action) VALUES
        (id_nf1, id_directeur, 'SOUMISE',       'Soumis pour validation',          NOW() - INTERVAL '11 days'),
        (id_nf1, id_dfin,      'VERIFIEE_DFIN', 'Montant vérifié, conforme',       NOW() - INTERVAL '8 days'),
        (id_nf1, id_da,        'VALIDEE_DA',    'Validé, priorité haute urgente.', NOW() - INTERVAL '6 days'),
        (id_nf1, id_caissier,  'PAYEE',         'Paiement effectué en caisse.',    NOW() - INTERVAL '2 days');
END IF;

IF NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = id_nf2) THEN
    INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action) VALUES
        (id_nf2, id_directeur, 'SOUMISE',       'Déplacement urgent approuvé DG.',  NOW() - INTERVAL '6 days'),
        (id_nf2, id_dfin,      'VERIFIEE_DFIN', 'Pièces justificatives reçues.',    NOW() - INTERVAL '4 days'),
        (id_nf2, id_da,        'VALIDEE_DA',    'Priorité haute, transmis caisse.', NOW() - INTERVAL '1 day');
END IF;

IF NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = id_nf3) THEN
    INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action) VALUES
        (id_nf3, id_dfin, 'SOUMISE',          'Besoin métier confirmé.',    NOW() - INTERVAL '4 days'),
        (id_nf3, id_dfin, 'VERIFIEE_DFIN',    'Auto-vérification conforme.',NOW() - INTERVAL '3 days'),
        (id_nf3, id_da,   'VALIDEE_DA',       'Approuvé.',                  NOW() - INTERVAL '12 hours');
END IF;

IF NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = id_nf4) THEN
    INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action) VALUES
        (id_nf4, id_directeur, 'SOUMISE',       'Véhicule immobilisé.',    NOW() - INTERVAL '2 days'),
        (id_nf4, id_dfin,      'VERIFIEE_DFIN', 'Devis vérifié conforme.', NOW() - INTERVAL '1 day');
END IF;

IF NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = id_nf5) THEN
    INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action) VALUES
        (id_nf5, id_directeur, 'SOUMISE', 'Réception importante pour le contrat.', NOW() - INTERVAL '10 hours');
END IF;

IF NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = id_nf7) THEN
    INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action) VALUES
        (id_nf7, id_directeur, 'SOUMISE',       'Urgence, mobilier vétuste.',          NOW() - INTERVAL '13 days'),
        (id_nf7, id_dfin,      'VERIFIEE_DFIN', 'Montant correct.',                    NOW() - INTERVAL '11 days'),
        (id_nf7, id_da,        'REJETEE_DA',    'Budget insuffisant, reporter au Q3.', NOW() - INTERVAL '9 days');
END IF;

-- Avancer les séquences
PERFORM setval('seq_note_frais', GREATEST(nextval('seq_note_frais') + 3, 10));

-- ─── Transactions de caisse ────────────────────────────────────

INSERT INTO transactions_caisse (uuid, reference, note_id, montant, sens,
    caissier_id, numero_recu, libelle, date_operation, date_enregistrement)
VALUES (gen_random_uuid(), 'TRX-2026-000001', NULL, 500000.00, 'ENCAISSEMENT',
    id_caissier, 'RECU-2026-000001', 'Dotation initiale de caisse — Ordre DG',
    NOW() - INTERVAL '15 days', NOW() - INTERVAL '15 days')
ON CONFLICT (reference) DO NOTHING;
SELECT id INTO id_tx2 FROM transactions_caisse WHERE reference = 'TRX-2026-000001';

INSERT INTO transactions_caisse (uuid, reference, note_id, montant, sens,
    caissier_id, numero_recu, libelle, date_operation, date_enregistrement)
VALUES (gen_random_uuid(), 'TRX-2026-000002', id_nf1, 45000.00, 'DECAISSEMENT',
    id_caissier, 'RECU-2026-000002', 'Paiement NF-2026-000001 — Fournitures bureau',
    NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days')
ON CONFLICT (reference) DO NOTHING;
SELECT id INTO id_tx1 FROM transactions_caisse WHERE reference = 'TRX-2026-000002';

PERFORM setval('seq_transaction_caisse', GREATEST(nextval('seq_transaction_caisse') + 1, 5));
PERFORM setval('seq_recu_caisse', GREATEST(nextval('seq_recu_caisse') + 1, 5));

-- ─── Grand livre (idempotent via count) ────────────────────────

IF (SELECT COUNT(*) FROM grand_livre WHERE transaction_id = id_tx2) = 0 THEN
    INSERT INTO grand_livre (transaction_id, compte_id, debit, credit, libelle, date_ecriture) VALUES
        (id_tx2, id_c571, 500000.00, 0.00,      'Dotation initiale caisse', NOW() - INTERVAL '15 days'),
        (id_tx2, id_c758, 0.00,      500000.00, 'Dotation initiale caisse', NOW() - INTERVAL '15 days');
END IF;

IF (SELECT COUNT(*) FROM grand_livre WHERE transaction_id = id_tx1) = 0 THEN
    INSERT INTO grand_livre (transaction_id, compte_id, debit, credit, libelle, date_ecriture) VALUES
        (id_tx1, id_c601, 45000.00, 0.00,     'Fournitures bureau NF-2026-000001', NOW() - INTERVAL '2 days'),
        (id_tx1, id_c571, 0.00,     45000.00, 'Fournitures bureau NF-2026-000001', NOW() - INTERVAL '2 days');
END IF;

-- ─── Budgets ───────────────────────────────────────────────────

IF NOT EXISTS (SELECT 1 FROM budgets WHERE intitule = 'Budget Exploitation Q2 2026') THEN
    INSERT INTO budgets (intitule, exercice, statut, elabore_par_id, approuve_par_id,
        observation, date_creation)
    VALUES ('Budget Exploitation Q2 2026', 2026, 'EN_EXECUTION',
        id_dfin, id_da,
        'Approuvé en réunion du 01/04/2026. Couvre charges courantes et déplacements.',
        NOW() - INTERVAL '30 days')
    RETURNING id INTO id_bud1;

    INSERT INTO lignes_budget (budget_id, compte_id, montant_prevu, montant_realise) VALUES
        (id_bud1, id_c601,  300000.00,  45000.00),
        (id_bud1, id_c611,  400000.00, 120000.00),
        (id_bud1, id_c622,  500000.00,   0.00),
        (id_bud1, id_c628,  350000.00, 284000.00),
        (id_bud1, id_c624,  200000.00,   0.00),
        (id_bud1, id_c6588, 250000.00,   0.00);
END IF;

IF NOT EXISTS (SELECT 1 FROM budgets WHERE intitule = 'Budget Formation & Développement 2026') THEN
    INSERT INTO budgets (intitule, exercice, statut, elabore_par_id, approuve_par_id,
        observation, date_creation)
    VALUES ('Budget Formation & Développement 2026', 2026, 'APPROUVE',
        id_dfin, id_da,
        'Renforcement de capacités : formations, séminaires, abonnements outils métier.',
        NOW() - INTERVAL '20 days');
END IF;

PERFORM setval('seq_budget', GREATEST(nextval('seq_budget') + 1, 3));

END $$;
