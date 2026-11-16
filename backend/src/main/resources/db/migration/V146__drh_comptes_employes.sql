-- =====================================================================
-- V146 : comptes de paie par agent (audit DRH du 15/11/2026, D-02, D-03, D-04)
-- =====================================================================
-- D-02 : chaque agent reçoit son propre compte de rémunérations dues sous le collectif 42211 « Personnel,
--        rémunérations dues » (422111, 422112… : numéro du parent suivi du rang, sans point ni zéro). La paie y
--        crédite son net à payer, la note de paie l'y débite. Les fiches déjà enregistrées reçoivent ici leur
--        compte (par id croissant) ; les nouvelles le reçoivent à leur création (EmployeService).
-- D-03 : compte du prêt de l'agent (272…), choisi sur sa fiche, crédité par la retenue de prêt (à défaut 2728) ;
--        la retenue n'est plus portée en 2762, qui est le compte des intérêts courus.
-- D-04 : l'indemnité de transport est portée en 6634 « Indemnités de transport » (et non plus 6638) : le compte
--        est créé s'il manque au plan.
-- =====================================================================

ALTER TABLE drh_employes ADD COLUMN IF NOT EXISTS compte_remuneration VARCHAR(20);
ALTER TABLE drh_employes ADD COLUMN IF NOT EXISTS compte_pret VARCHAR(20);

-- D-04 : 6634 sous 663 « Indemnités forfaitaires versées au personnel », mêmes type et classe que 6631.
INSERT INTO comptes_ohada (numero, libelle, type, classe, parent_id, manuel, imputable, actif, contenu, commentaires)
SELECT '6634', 'Indemnités de transport', l.type, l.classe, (SELECT id FROM comptes_ohada WHERE numero = '663'),
       FALSE, TRUE, TRUE,
       'Indemnités forfaitaires de transport versées au personnel (SYSCOHADA : 663 Indemnités forfaitaires versées '
           || 'au personnel). Débité par la constatation de la paie pour l''indemnité de transport des bulletins.',
       'Compte créé par la migration V146 (audit DRH du 15/11/2026, D-04) : l''indemnité de transport était '
           || 'portée en 6638 « Autres indemnités et avantages divers ».'
FROM comptes_ohada l
WHERE l.numero = '6631'
ON CONFLICT (numero) DO NOTHING;

-- Comptes que la paie mouvemente : des comptes de détail, actifs.
UPDATE comptes_ohada SET actif = TRUE, imputable = TRUE WHERE numero IN ('42211', '2728', '6634');

-- D-02 : un compte par agent sous 42211.
DO $$
DECLARE
    collectif comptes_ohada%ROWTYPE;
    e RECORD;
    rang INTEGER;
    numero_libre VARCHAR(20);
BEGIN
    SELECT * INTO collectif FROM comptes_ohada WHERE numero = '42211';
    IF NOT FOUND THEN
        RETURN;   -- plan comptable sans 42211 : les fiches restent sans compte propre (la paie retombe sur 42211)
    END IF;
    rang := 0;
    FOR e IN SELECT id, matricule, nom_complet FROM drh_employes WHERE compte_remuneration IS NULL ORDER BY id LOOP
        LOOP
            rang := rang + 1;
            numero_libre := '42211' || rang;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM comptes_ohada WHERE numero = numero_libre);
        END LOOP;
        INSERT INTO comptes_ohada (numero, libelle, type, classe, parent_id, manuel, imputable, actif, contenu, commentaires)
        VALUES (numero_libre, LEFT('Rémunérations dues : ' || e.nom_complet, 200), collectif.type, collectif.classe,
                collectif.id, TRUE, TRUE, TRUE,
                'Sous-compte de 42211 « ' || collectif.libelle || ' », ouvert pour l''agent « ' || e.nom_complet
                    || ' » (' || e.matricule || ') : crédité du net à payer de ses bulletins, débité par la note '
                    || 'de paie qui le règle.',
                'Compte créé automatiquement par la migration V146 pour une fiche employé déjà enregistrée.');
        UPDATE drh_employes SET compte_remuneration = numero_libre WHERE id = e.id;
    END LOOP;
END $$;

-- Un compte par agent : deux fiches ne partagent jamais le même numéro (les fiches sans compte restent permises).
CREATE UNIQUE INDEX IF NOT EXISTS ux_drh_employes_compte_remuneration ON drh_employes (compte_remuneration);

-- D-11 : le DFIN est le seul rôle autorisé à clôturer, rouvrir et comptabiliser la paie (audit du 10/11/2026, C-14),
-- mais la grille d'origine (V41) ne lui donnait que la LECTURE sur DRH_PAIE : ces actions étaient refusées (403) à
-- tout le monde et aucune paie ne pouvait être clôturée. Chaque écriture du module reste gardée par rôle côté
-- service : l'écriture n'ouvre au DFIN que ces trois actions.
UPDATE role_permissions SET niveau = 'ECRITURE' WHERE role = 'DFIN' AND module = 'DRH_PAIE' AND niveau = 'LECTURE';
