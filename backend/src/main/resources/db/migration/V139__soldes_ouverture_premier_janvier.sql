-- =====================================================================
-- V139 : les pièces du journal datées du 1er janvier sont des soldes d'ouverture
-- =====================================================================
-- Règle posée par l'utilisateur le 13/11/2026 : « dans le journal comptable, toute opération du 1er janvier doit
-- être considérée comme solde d'ouverture ». En SYSCOHADA l'exercice coïncide avec l'année civile : le 1er janvier
-- est le jour d'ouverture, et la balance générale donne pour chaque compte son solde à l'ouverture de l'exercice,
-- distinct des mouvements de la période.
--
-- Jusqu'ici le marqueur solde_ouverture ne se posait qu'à la main (case de la saisie, bouton du DFIN). Un journal
-- importé gardait donc sa reprise des à-nouveaux parmi les mouvements, et les colonnes « Soldes d'ouverture » de la
-- balance restaient à zéro. Les nouvelles pièces reçoivent désormais le marqueur à leur création (import et
-- saisie : voir SoldeOuverture.java) ; cette migration le pose sur les pièces déjà enregistrées.
--
-- Sont marquées les pièces datées du 1er janvier qui :
--   * ne portent que des comptes de bilan (classes 1 à 5) : un compte de gestion (classes 6, 7, 8) n'a pas de solde
--     d'ouverture, une charge ou un produit du 1er janvier reste un mouvement de l'exercice ;
--   * ne sont pas des écritures que l'application passe elle-même ce jour-là (extourne d'ouverture d'une
--     régularisation, réévaluation des soldes en devise, clôture) ;
--   * n'appartiennent à aucune opération : aucune ligne liée à une opération de caisse, de banque ou de mobile
--     money, et aucune autre table ne les référence (vente, mouvement de stock, régularisation...). Les colonnes
--     qui référencent pieces_comptables sont lues au catalogue, pour couvrir tous les modules.
--
-- Seul le marqueur change : c'est un marqueur de présentation, que la V135 laisse modifiable. Le contenu des
-- pièces (date, libellé, montants) reste intangible ; la mention « - Solde d'ouverture » du libellé est ajoutée à
-- l'affichage. Chaque pièce marquée laisse un événement au journal des événements comptables (C-09), sauf sur un
-- serveur miroir, qui reçoit ceux du serveur maître.
-- =====================================================================

DO $$
DECLARE
    lien           record;
    piece          record;
    hors_operation text := '';
    miroir         boolean;
    nombre         integer := 0;
BEGIN
    FOR lien IN
        SELECT c.conrelid::regclass::text AS nom_table, a.attname AS colonne
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.contype = 'f'
          AND c.confrelid = 'pieces_comptables'::regclass
          AND array_length(c.conkey, 1) = 1
          AND c.conrelid NOT IN ('pieces_comptables'::regclass, 'grand_livre'::regclass)
    LOOP
        hors_operation := hors_operation
            || format(' AND NOT EXISTS (SELECT 1 FROM %s x WHERE x.%I = p.id)', lien.nom_table, lien.colonne);
    END LOOP;

    SELECT EXISTS (SELECT 1 FROM parametres_instance WHERE mode = 'MIROIR') INTO miroir;

    FOR piece IN EXECUTE $requete$
        SELECT p.id, p.reference, p.date_piece, p.journal, p.libelle, p.statut, p.total_debit, p.total_credit
        FROM pieces_comptables p
        WHERE extract(month FROM p.date_piece) = 1 AND extract(day FROM p.date_piece) = 1
          AND p.solde_ouverture = false
          AND p.cloture_exercice = false
          AND p.reevaluation_devise = false
          AND p.extourne_ouverture = false
          AND EXISTS (SELECT 1 FROM grand_livre g WHERE g.piece_id = p.id)
          AND NOT EXISTS (SELECT 1 FROM grand_livre g JOIN comptes_ohada c ON c.id = g.compte_id
                          WHERE g.piece_id = p.id AND left(c.numero, 1) NOT IN ('1', '2', '3', '4', '5'))
          AND NOT EXISTS (SELECT 1 FROM grand_livre g
                          WHERE g.piece_id = p.id
                            AND (g.transaction_id IS NOT NULL OR g.transaction_bancaire_id IS NOT NULL
                                 OR g.transaction_mobile_money_id IS NOT NULL))
        $requete$ || hors_operation || ' ORDER BY p.id'
    LOOP
        UPDATE pieces_comptables SET solde_ouverture = true WHERE id = piece.id;
        IF NOT miroir THEN
            INSERT INTO journal_evenements_comptables
                (auteur, action, objet_type, objet_id, objet_reference, avant, apres, motif, empreinte)
            VALUES ('système', 'BASCULE_SOLDE_OUVERTURE', 'PIECE', piece.id, piece.reference,
                jsonb_build_object('reference', piece.reference, 'date', piece.date_piece::text,
                    'journal', piece.journal, 'libelle', piece.libelle, 'statut', piece.statut,
                    'soldeOuverture', false, 'totalDebit', piece.total_debit, 'totalCredit', piece.total_credit),
                jsonb_build_object('reference', piece.reference, 'date', piece.date_piece::text,
                    'journal', piece.journal, 'libelle', piece.libelle, 'statut', piece.statut,
                    'soldeOuverture', true, 'totalDebit', piece.total_debit, 'totalCredit', piece.total_credit),
                'Règle du 13/11/2026 : une pièce du journal datée du 1er janvier, aux seuls comptes de bilan, est un '
                    || 'solde d''ouverture (migration V139).',
                '');
        END IF;
        nombre := nombre + 1;
    END LOOP;

    RAISE NOTICE 'V139 : % pièce(s) du 1er janvier marquée(s) « solde d''ouverture ».', nombre;
END $$;
