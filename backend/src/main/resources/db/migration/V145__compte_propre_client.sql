-- Chaque client reçoit son propre compte sous le collectif 4111 « Clients » (41111, 41112… : numéro du parent suivi
-- du rang, comme les fournisseurs sous 4011) et un code attribué par l'application (CLI-0001…). Demande du
-- 15/11/2026 : ni le code ni le compte ne sont plus saisis. Les fiches déjà enregistrées reçoivent ici leur compte.

ALTER TABLE clients ADD COLUMN IF NOT EXISTS compte_comptable VARCHAR(20);

DO $$
DECLARE
    collectif comptes_ohada%ROWTYPE;
    c RECORD;
    rang INTEGER;
    numero_libre VARCHAR(20);
BEGIN
    SELECT * INTO collectif FROM comptes_ohada WHERE numero = '4111';
    IF NOT FOUND THEN
        RETURN;   -- plan comptable sans 4111 : les fiches restent sans compte propre
    END IF;
    rang := 0;
    FOR c IN SELECT id, nom, code FROM clients WHERE compte_comptable IS NULL ORDER BY id LOOP
        LOOP
            rang := rang + 1;
            numero_libre := '4111' || rang;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM comptes_ohada WHERE numero = numero_libre);
        END LOOP;
        INSERT INTO comptes_ohada (numero, libelle, type, classe, parent_id, manuel, imputable, actif, contenu, commentaires)
        VALUES (numero_libre, LEFT('Clients : ' || c.nom, 200), collectif.type, collectif.classe, collectif.id,
                TRUE, TRUE, TRUE,
                'Sous-compte de 4111 « ' || collectif.libelle || ' », ouvert pour le client « ' || c.nom || ' »'
                    || COALESCE(' (' || c.code || ')', '') || '.',
                'Compte créé automatiquement par la migration V145 pour une fiche client déjà enregistrée.');
        UPDATE clients SET compte_comptable = numero_libre WHERE id = c.id;
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_clients_compte ON clients (compte_comptable);
