-- =====================================================================
-- V137 : sous-comptes sans point et comptes de regroupement non imputables (audit SYSCOHADA, C-17)
-- =====================================================================
-- La codification SYSCOHADA est décimale : un sous-compte prolonge le numéro de son parent par des
-- chiffres, jamais par un point. Le point est retiré (6031.2 -> 60312, 736.1 -> 7361, 4221.1 -> 42211).
--
-- Deux cas où le numéro sans point est déjà pris :
--   * par un compte créé par l'application ou à la main (compte d'article : l'application numérote les
--     comptes de ses articles racine + 1 à 99, ex. 60312 « Fanta ») : c'est ce compte-là qui prend le
--     numéro libre suivant sous son parent, et le sous-compte à point reçoit son numéro sans point ;
--   * par un compte du plan officiel (431.1 -> 4311 « Prestations familiales », 618.1 -> 6181...) : le
--     sous-compte reçoit le numéro libre suivant sous son parent (431.1 -> 4315).
-- Les comptes que le code de l'application mouvemente reçoivent un numéro fixe (table _fixes).
-- Seuls les numéros changent : les écritures référencent les comptes par leur identifiant, aucune n'est
-- modifiée. Les fiches de documentation qui citaient un ancien numéro sont mises à jour.
-- =====================================================================

CREATE TEMP TABLE _fixes (ancien VARCHAR(30) PRIMARY KEY, nouveau VARCHAR(20) NOT NULL) ON COMMIT DROP;
INSERT INTO _fixes (ancien, nouveau) VALUES
    ('3111.1', '31111'), ('4221.1', '42211'), ('4478.1', '44781'), ('4478.2', '44782'),
    ('6011.3', '60113'), ('6031.1', '60311'), ('6031.2', '60312'), ('6276.1', '62761'),
    ('6276.2', '62762'), ('6617.1', '66171'), ('7011.1', '70111'),
    ('736.1', '7361'), ('736.2', '7362'), ('736.3', '7363'), ('736.4', '7364'), ('736.5', '7365'),
    ('431.1', '4315');

-- deplace = compte sans point qui cède son numéro (il est traité avant les comptes à point).
CREATE TEMP TABLE _renumerotation (ancien VARCHAR(30) PRIMARY KEY, nouveau VARCHAR(20) NOT NULL UNIQUE,
                                   deplace BOOLEAN NOT NULL) ON COMMIT DROP;

-- Numéros que les comptes à point visent : aucun compte déplacé ne doit y atterrir.
CREATE TEMP TABLE _reserves (numero VARCHAR(20) PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _reserves SELECT nouveau FROM _fixes;
INSERT INTO _reserves SELECT DISTINCT replace(numero, '.', '') FROM comptes_ohada
 WHERE numero LIKE '%.%' AND numero NOT IN (SELECT ancien FROM _fixes)
ON CONFLICT DO NOTHING;

-- Premier numéro libre « préfixe + 1, 2, 3... » : ni pris, ni déjà attribué, ni réservé.
CREATE FUNCTION pg_temp.numero_libre(prefixe text, eviter_reserves boolean) RETURNS text AS $$
DECLARE
    n int := 1;
    candidat text;
BEGIN
    LOOP
        candidat := prefixe || n;
        IF NOT EXISTS (SELECT 1 FROM comptes_ohada WHERE numero = candidat)
           AND NOT EXISTS (SELECT 1 FROM _renumerotation WHERE nouveau = candidat)
           AND (NOT eviter_reserves OR NOT EXISTS (SELECT 1 FROM _reserves WHERE numero = candidat)) THEN
            RETURN candidat;
        END IF;
        n := n + 1;
        IF n > 9999 THEN
            RAISE EXCEPTION 'Aucun numéro libre sous %', prefixe;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    d record;
    occ record;
    cible text;
    fixe boolean;
    prefixe_occ text;
BEGIN
    -- Les comptes à numéro fixe d'abord, puis les autres par ordre de numéro.
    FOR d IN SELECT c.numero, (f.ancien IS NOT NULL) AS fixe, f.nouveau AS cible_fixe
             FROM comptes_ohada c LEFT JOIN _fixes f ON f.ancien = c.numero
             WHERE c.numero LIKE '%.%'
             ORDER BY (f.ancien IS NULL), c.numero LOOP
        fixe := d.fixe;
        cible := coalesce(d.cible_fixe, replace(d.numero, '.', ''));
        IF EXISTS (SELECT 1 FROM _renumerotation WHERE nouveau = cible) THEN
            IF fixe THEN
                RAISE EXCEPTION 'Renumérotation de % : le numéro % est déjà attribué.', d.numero, cible;
            END IF;
            cible := NULL;
        END IF;
        IF cible IS NOT NULL THEN
            SELECT c.numero, c.manuel, p.numero AS parent INTO occ
              FROM comptes_ohada c LEFT JOIN comptes_ohada p ON p.id = c.parent_id
             WHERE c.numero = cible;
            IF FOUND THEN
                IF occ.manuel THEN
                    -- Compte d'article ou compte manuel : il cède son numéro et prend le suivant libre.
                    prefixe_occ := coalesce(occ.parent, left(occ.numero, length(occ.numero) - 1));
                    INSERT INTO _renumerotation (ancien, nouveau, deplace)
                    VALUES (occ.numero, pg_temp.numero_libre(prefixe_occ, true), true);
                ELSIF fixe THEN
                    RAISE EXCEPTION 'Renumérotation de % impossible : le compte % du plan officiel existe déjà.',
                        d.numero, cible;
                ELSE
                    cible := NULL;
                END IF;
            END IF;
        END IF;
        IF cible IS NULL THEN
            -- Numéro officiel déjà pris : le suivant libre sous le parent (618.1 -> 6184...).
            cible := pg_temp.numero_libre(split_part(d.numero, '.', 1), true);
        END IF;
        INSERT INTO _renumerotation (ancien, nouveau, deplace) VALUES (d.numero, cible, false);
    END LOOP;
END $$;

-- Ancien numéro à point -> nouveau numéro, gardé pour lire les journaux exportés avant cette migration
-- (l'import retrouve ainsi 431.1 sous 4315, et non sous 4311 « Prestations familiales »).
CREATE TABLE IF NOT EXISTS comptes_anciens_numeros (
    ancien   VARCHAR(30) PRIMARY KEY,
    nouveau  VARCHAR(20) NOT NULL
);
INSERT INTO comptes_anciens_numeros (ancien, nouveau)
SELECT ancien, nouveau FROM _renumerotation WHERE NOT deplace
ON CONFLICT (ancien) DO UPDATE SET nouveau = excluded.nouveau;

-- Les comptes qui cèdent leur numéro d'abord, puis les comptes à point.
UPDATE comptes_ohada c SET numero = r.nouveau FROM _renumerotation r WHERE c.numero = r.ancien AND r.deplace;
UPDATE comptes_ohada c SET numero = r.nouveau FROM _renumerotation r WHERE c.numero = r.ancien AND NOT r.deplace;

-- Fiches de documentation des comptes : l'ancien numéro cité dans un texte devient le nouveau, dans le même
-- ordre (un numéro ne se remplace que s'il n'est pas prolongé par un chiffre : 736.1 mais pas 736.15).
DO $$
DECLARE
    r record;
    motif text;
BEGIN
    FOR r IN SELECT * FROM _renumerotation ORDER BY deplace DESC LOOP
        motif := '(^|[^0-9.])' || replace(r.ancien, '.', '\.') || '(?![0-9])';
        UPDATE comptes_ohada SET
            contenu = regexp_replace(contenu, motif, '\1' || r.nouveau, 'g'),
            fonctionnement = regexp_replace(fonctionnement, motif, '\1' || r.nouveau, 'g'),
            exclusions = regexp_replace(exclusions, motif, '\1' || r.nouveau, 'g'),
            controle = regexp_replace(controle, motif, '\1' || r.nouveau, 'g'),
            commentaires = regexp_replace(commentaires, motif, '\1' || r.nouveau, 'g')
        WHERE contenu ~ motif OR fonctionnement ~ motif OR exclusions ~ motif OR controle ~ motif
           OR commentaires ~ motif;
    END LOOP;
END $$;

-- Un compte renuméroté garde son parent. Celui qui n'en avait pas (431.1, 4478.1) reçoit le plus long numéro
-- existant qui préfixe son ancien numéro sans le point (431.1 -> 431).
UPDATE comptes_ohada c SET parent_id = (
        SELECT p.id FROM comptes_ohada p
         WHERE split_part(r.ancien, '.', 1) LIKE p.numero || '%'
         ORDER BY length(p.numero) DESC LIMIT 1)
  FROM _renumerotation r
 WHERE c.numero = r.nouveau AND NOT r.deplace AND c.parent_id IS NULL;

-- ---------------------------------------------------------------------
-- Les comptes qui ont des subdivisions restent imputables : l'historique de l'entreprise mouvemente à la
-- fois le compte et ses sous-comptes (6051 et 6051.1, 7061 et 7061.1), et l'import de ce journal doit
-- rester possible.
-- 571 « Caisse » est le compte de caisse de l'application : ses subdivisions standard jamais
-- mouvementées (5711 à 5714) sont désactivées, et 571 reste le seul compte de caisse.
-- ---------------------------------------------------------------------
UPDATE comptes_ohada c SET actif = FALSE
 WHERE c.numero IN ('5711', '5712', '5713', '5714') AND NOT c.manuel
   AND NOT EXISTS (SELECT 1 FROM grand_livre g WHERE g.compte_id = c.id);

-- ---------------------------------------------------------------------
-- C-18 : cessions courantes d'immobilisations corporelles (654 / 754 du SYSCOHADA révisé) et créance
-- sur cession (485). Le plan chargé n'a sous 654 et 754 que d'anciens comptes sans rapport.
-- ---------------------------------------------------------------------
INSERT INTO comptes_ohada (numero, libelle, type, classe, parent_id, manuel, imputable, actif)
SELECT '6542', 'Valeurs comptables des cessions courantes : immobilisations corporelles', 'CHARGE', 6,
       (SELECT id FROM comptes_ohada WHERE numero = '654'), FALSE, TRUE, TRUE
 WHERE NOT EXISTS (SELECT 1 FROM comptes_ohada WHERE numero = '6542');
INSERT INTO comptes_ohada (numero, libelle, type, classe, parent_id, manuel, imputable, actif)
SELECT '7542', 'Produits des cessions courantes : immobilisations corporelles', 'PRODUIT', 7,
       (SELECT id FROM comptes_ohada WHERE numero = '754'), FALSE, TRUE, TRUE
 WHERE NOT EXISTS (SELECT 1 FROM comptes_ohada WHERE numero = '7542');

-- ---------------------------------------------------------------------
-- C-16 : 477 « Produits constatés d'avance » et 479 « Écarts de conversion - passif » sont des dettes ;
-- les dépréciations 59x viennent en moins de l'actif de trésorerie.
-- ---------------------------------------------------------------------
UPDATE comptes_ohada SET type = 'PASSIF' WHERE numero ~ '^(477|479)' AND type IS DISTINCT FROM 'PASSIF';
UPDATE comptes_ohada SET type = 'ACTIF' WHERE numero ~ '^59' AND type IS DISTINCT FROM 'ACTIF';
