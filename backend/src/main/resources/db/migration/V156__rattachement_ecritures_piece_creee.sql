-- Paiement d'une note à la caisse (et tout flux interne bâti de même) :
-- la transaction est enregistrée avec ses écritures, puis la pièce de caisse
-- est créée et les écritures y sont rattachées. Les identifiants étant
-- générés à l'insertion, les écritures existent un instant sans pièce, et le
-- verrou d'intangibilité (V135) refusait leur rattachement : « Écriture … de
-- la pièce (sans pièce) : une écriture comptabilisée est intangible ».
--
-- Une écriture sans pièce peut désormais être rattachée à une pièce créée
-- dans la même transaction (piece_creee_ici). Toute autre modification d'une
-- écriture sans pièce reste refusée.
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
        -- Rattachement à la pièce que la même transaction vient de créer.
        IF TG_OP = 'UPDATE' AND NEW.piece_id IS NOT NULL AND piece_creee_ici(NEW.piece_id) THEN
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
