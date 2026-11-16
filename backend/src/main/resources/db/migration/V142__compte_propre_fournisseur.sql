-- Chaque fournisseur reçoit son propre compte sous le collectif 4011 (40111, 40112… : numéro du parent suivi du
-- rang, sans point ni zéro). Le compte n'est plus saisi : l'application l'ouvre à la création de la fiche.
-- Les fiches déjà enregistrées sur le collectif 4011 (ou un autre 401…) reçoivent ici leur compte.

ALTER TABLE fournisseurs ALTER COLUMN compte_comptable DROP DEFAULT;

DO $$
DECLARE
    collectif comptes_ohada%ROWTYPE;
    f RECORD;
    rang INTEGER;
    numero_libre VARCHAR(20);
BEGIN
    SELECT * INTO collectif FROM comptes_ohada WHERE numero = '4011';
    IF NOT FOUND THEN
        RETURN;   -- plan comptable sans 4011 : rien à rattacher (les fiches gardent leur compte)
    END IF;
    rang := 0;
    FOR f IN
        SELECT id, nom, code FROM fournisseurs fo
        WHERE NOT EXISTS (SELECT 1 FROM comptes_ohada c WHERE c.numero = fo.compte_comptable AND c.parent_id = collectif.id)
        ORDER BY id
    LOOP
        LOOP
            rang := rang + 1;
            numero_libre := '4011' || rang;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM comptes_ohada WHERE numero = numero_libre);
        END LOOP;
        INSERT INTO comptes_ohada (numero, libelle, type, classe, parent_id, manuel, imputable, actif, contenu, commentaires)
        VALUES (numero_libre, LEFT('Fournisseurs : ' || f.nom, 200), collectif.type, collectif.classe, collectif.id,
                TRUE, TRUE, TRUE,
                'Sous-compte de 4011 « ' || collectif.libelle || ' », ouvert pour le fournisseur « ' || f.nom || ' »'
                    || COALESCE(' (' || f.code || ')', '') || '.',
                'Compte créé automatiquement par la migration V142 pour une fiche fournisseur déjà enregistrée.');
        UPDATE fournisseurs SET compte_comptable = numero_libre WHERE id = f.id;
    END LOOP;
    -- Un compte par fournisseur : deux fiches ne partagent plus le même numéro.
    CREATE UNIQUE INDEX IF NOT EXISTS ux_fournisseurs_compte ON fournisseurs (compte_comptable);
END $$;
