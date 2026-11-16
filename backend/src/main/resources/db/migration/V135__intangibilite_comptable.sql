-- =====================================================================
-- V135 : intangibilité des écritures comptables (audit SYSCOHADA du 10/11/2026, constat C-02)
-- =====================================================================
-- Jusqu'ici, seule l'application protégeait une pièce comptabilisée : un UPDATE ou un DELETE en base
-- passait sans obstacle. Désormais la base elle-même refuse :
--   * de modifier le contenu d'une pièce COMPTABILISEE ou ANNULEE (date, journal, libellé, montants,
--     auteur, référence) ; seuls restent possibles le passage COMPTABILISEE -> ANNULEE (extourne), la
--     trace de validation (C-06) quand elle est vide, et les marqueurs de présentation ;
--   * de supprimer une pièce qui n'est plus au brouillon ;
--   * de modifier ou supprimer une écriture d'une pièce comptabilisée, hors pointage du rapprochement
--     et taux figé à la validation ;
--   * de comptabiliser une pièce datée d'une période clôturée (C-26) ;
--   * de laisser, à la fin d'une transaction, une pièce comptabilisée déséquilibrée.
-- Une pièce reste entièrement modifiable dans la transaction qui l'a créée (marqueurs posés juste
-- après la création par la clôture, la réévaluation...).
--
-- Deux traitements réécrivent légitimement la comptabilité et lèvent ces verrous pour LEUR seule
-- transaction (set_config(..., true)) : l'application d'un lot de synchronisation sur la copie en
-- ligne, et l'effacement qui précède un import « Remplacer » (C-01, maintenu à la demande).
-- =====================================================================

-- Vrai si la transaction courante a levé les verrous (synchronisation, import « Remplacer »).
CREATE OR REPLACE FUNCTION reecriture_comptable_autorisee() RETURNS boolean AS $$
    SELECT coalesce(current_setting('app.reecriture_comptable', true), '') = 'on';
$$ LANGUAGE sql STABLE;

-- Pièces créées par la transaction courante : notées à leur insertion dans un réglage local à la
-- transaction. Une pièce reste modifiable dans la transaction qui l'a créée, jamais au-delà ; une simple
-- mise à jour (extourne) ne la rend pas modifiable.
CREATE OR REPLACE FUNCTION piece_creee_ici(pid bigint) RETURNS boolean AS $$
    SELECT pid IS NOT NULL
       AND position(',' || pid || ',' IN coalesce(current_setting('app.pieces_creees', true), '')) > 0;
$$ LANGUAGE sql VOLATILE;

-- ---------------------------------------------------------------------
-- Pièces comptables
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION proteger_piece_comptable() RETURNS trigger AS $$
DECLARE
    cloture date;
BEGIN
    IF reecriture_comptable_autorisee() THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        PERFORM set_config('app.pieces_creees',
            coalesce(nullif(current_setting('app.pieces_creees', true), ''), ',') || NEW.id || ',', true);
    END IF;

    IF TG_OP = 'DELETE' THEN
        IF OLD.statut <> 'BROUILLON' AND NOT piece_creee_ici(OLD.id) THEN
            RAISE EXCEPTION 'Pièce % (%) : une pièce comptabilisée ne se supprime pas, elle s''extourne.',
                OLD.reference, OLD.statut USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;

    -- Comptabilisation d'une pièce datée d'une période clôturée (C-26), quelle que soit la voie.
    IF NEW.statut = 'COMPTABILISEE' AND (TG_OP = 'INSERT' OR OLD.statut = 'BROUILLON') THEN
        SELECT date_cloture INTO cloture FROM parametres_comptables WHERE id = 1;
        IF cloture IS NOT NULL AND NEW.date_piece <= cloture THEN
            RAISE EXCEPTION 'Période clôturée : la pièce % ne peut pas être comptabilisée au % (clôture au %).',
                NEW.reference, NEW.date_piece, cloture USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF TG_OP = 'INSERT' OR OLD.statut = 'BROUILLON' OR piece_creee_ici(OLD.id) THEN
        RETURN NEW;
    END IF;

    -- OLD est COMPTABILISEE ou ANNULEE, écrite par une transaction précédente.
    IF NEW.reference IS DISTINCT FROM OLD.reference
       OR NEW.date_piece IS DISTINCT FROM OLD.date_piece
       OR NEW.journal IS DISTINCT FROM OLD.journal
       OR NEW.libelle IS DISTINCT FROM OLD.libelle
       OR NEW.total_debit IS DISTINCT FROM OLD.total_debit
       OR NEW.total_credit IS DISTINCT FROM OLD.total_credit
       OR NEW.created_by_id IS DISTINCT FROM OLD.created_by_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.piece_origine_id IS DISTINCT FROM OLD.piece_origine_id
       OR to_jsonb(NEW) -> 'piece_restauree_id' IS DISTINCT FROM to_jsonb(OLD) -> 'piece_restauree_id' THEN
        RAISE EXCEPTION 'Pièce % (%) : le contenu d''une pièce comptabilisée est intangible ; corrigez-la par une extourne.',
            OLD.reference, OLD.statut USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.statut IS DISTINCT FROM OLD.statut
       AND NOT (OLD.statut = 'COMPTABILISEE' AND NEW.statut = 'ANNULEE') THEN
        RAISE EXCEPTION 'Pièce % : passage de % à % interdit.', OLD.reference, OLD.statut, NEW.statut
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS intangibilite_piece ON pieces_comptables;
CREATE TRIGGER intangibilite_piece
    BEFORE INSERT OR UPDATE OR DELETE ON pieces_comptables
    FOR EACH ROW EXECUTE FUNCTION proteger_piece_comptable();

-- ---------------------------------------------------------------------
-- Écritures du grand livre
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION proteger_ecriture_comptable() RETURNS trigger AS $$
DECLARE
    p record;
    refp text := '(sans pièce)';
    ligne grand_livre%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        ligne := NEW;
    ELSE
        ligne := OLD;
    END IF;
    IF reecriture_comptable_autorisee() THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    IF ligne.piece_id IS NULL THEN
        -- Écriture sans pièce (anciens flux) : figée dès qu'elle existe.
        IF TG_OP = 'INSERT' THEN
            RETURN NEW;
        END IF;
    ELSE
        SELECT statut, reference INTO p FROM pieces_comptables WHERE id = ligne.piece_id;
        IF NOT FOUND OR p.statut = 'BROUILLON' OR piece_creee_ici(ligne.piece_id) THEN
            IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
            RETURN NEW;
        END IF;
        refp := p.reference;
    END IF;

    IF TG_OP = 'INSERT' THEN
        RAISE EXCEPTION 'Pièce % : aucune écriture ne s''ajoute à une pièce comptabilisée.',
            refp USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Écriture % de la pièce % : une écriture comptabilisée ne se supprime pas, elle s''extourne.',
            OLD.id, refp USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.compte_id IS DISTINCT FROM OLD.compte_id
       OR NEW.debit IS DISTINCT FROM OLD.debit
       OR NEW.credit IS DISTINCT FROM OLD.credit
       OR NEW.libelle IS DISTINCT FROM OLD.libelle
       OR NEW.date_ecriture IS DISTINCT FROM OLD.date_ecriture
       OR NEW.piece_id IS DISTINCT FROM OLD.piece_id
       OR NEW.devise IS DISTINCT FROM OLD.devise
       OR NEW.montant_devise IS DISTINCT FROM OLD.montant_devise
       OR (OLD.taux_applique IS NOT NULL AND NEW.taux_applique IS DISTINCT FROM OLD.taux_applique)
       OR (OLD.transaction_id IS NOT NULL AND NEW.transaction_id IS DISTINCT FROM OLD.transaction_id)
       OR (OLD.transaction_bancaire_id IS NOT NULL AND NEW.transaction_bancaire_id IS DISTINCT FROM OLD.transaction_bancaire_id)
       OR (OLD.transaction_mobile_money_id IS NOT NULL
           AND NEW.transaction_mobile_money_id IS DISTINCT FROM OLD.transaction_mobile_money_id) THEN
        RAISE EXCEPTION 'Écriture % de la pièce % : une écriture comptabilisée est intangible ; corrigez-la par une extourne.',
            OLD.id, refp USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;  -- pointage du rapprochement, taux figé à la validation
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS intangibilite_ecriture ON grand_livre;
CREATE TRIGGER intangibilite_ecriture
    BEFORE INSERT OR UPDATE OR DELETE ON grand_livre
    FOR EACH ROW EXECUTE FUNCTION proteger_ecriture_comptable();

-- ---------------------------------------------------------------------
-- Équilibre de chaque pièce comptabilisée, contrôlé à la fin de la transaction
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION controler_equilibre_piece() RETURNS trigger AS $$
DECLARE
    pid bigint;
    st varchar;
    ref varchar;
    d numeric;
    c numeric;
BEGIN
    IF reecriture_comptable_autorisee() THEN
        RETURN NULL;
    END IF;
    IF TG_TABLE_NAME = 'pieces_comptables' THEN
        pid := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        pid := OLD.piece_id;
    ELSE
        pid := NEW.piece_id;
    END IF;
    IF pid IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT statut, reference INTO st, ref FROM pieces_comptables WHERE id = pid;
    IF NOT FOUND OR st = 'BROUILLON' THEN
        RETURN NULL;
    END IF;
    SELECT coalesce(sum(debit), 0), coalesce(sum(credit), 0) INTO d, c FROM grand_livre WHERE piece_id = pid;
    IF d <> c THEN
        RAISE EXCEPTION 'Pièce % déséquilibrée : débit %, crédit %.', ref, d, c USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS equilibre_piece_ecriture ON grand_livre;
CREATE CONSTRAINT TRIGGER equilibre_piece_ecriture
    AFTER INSERT OR UPDATE OR DELETE ON grand_livre
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION controler_equilibre_piece();

DROP TRIGGER IF EXISTS equilibre_piece ON pieces_comptables;
CREATE CONSTRAINT TRIGGER equilibre_piece
    AFTER INSERT OR UPDATE OF statut ON pieces_comptables
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION controler_equilibre_piece();

-- ---------------------------------------------------------------------
-- Contraintes de V13 posées NOT VALID : validées si les données le permettent. Une ligne ancienne
-- non conforme ne doit pas bloquer le démarrage : la contrainte reste alors non validée (elle
-- s'applique quand même à toute nouvelle écriture) et l'anomalie est signalée dans le journal.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'grand_livre'::regclass AND contype = 'c' AND NOT convalidated LOOP
        BEGIN
            EXECUTE format('ALTER TABLE grand_livre VALIDATE CONSTRAINT %I', c.conname);
        EXCEPTION WHEN check_violation THEN
            RAISE WARNING 'Contrainte % non validée : des écritures anciennes ne la respectent pas.', c.conname;
        END;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- Une opération de caisse, de banque ou de mobile money ne supprime plus ses écritures en cascade :
-- sa suppression échoue tant que ses écritures existent (contrôle en fin de transaction, comme les
-- autres clés étrangères, pour que l'effacement d'un import « Remplacer » reste possible).
-- ---------------------------------------------------------------------
DO $$
DECLARE
    fk record;
BEGIN
    FOR fk IN SELECT c.conname, a.attname AS colonne, c.confrelid::regclass::text AS parent
              FROM pg_constraint c
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
              WHERE c.conrelid = 'grand_livre'::regclass AND c.contype = 'f' AND c.confdeltype = 'c' LOOP
        EXECUTE format('ALTER TABLE grand_livre DROP CONSTRAINT %I', fk.conname);
        EXECUTE format('ALTER TABLE grand_livre ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %s (id) '
                       'DEFERRABLE INITIALLY IMMEDIATE', fk.conname, fk.colonne, fk.parent);
    END LOOP;
END $$;
