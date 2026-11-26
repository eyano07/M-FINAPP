-- Deux comptes de frais accessoires manquants, pour que CHAQUE nature
-- proposee a la saisie ait son compte propre.
--
-- L'ecran de saisie preselectionne desormais le compte des que la nature du
-- frais est choisie (voir NATURES_FRAIS dans pages/logistique/minerais.vue) :
-- il fallait donc un compte pour « Transport sur achat » et « Manutention »,
-- les deux seules natures courantes qui n'en avaient pas.
--
-- Le transport sur achat est range sous 618 « Autres frais de transport »
-- plutot que sous 6112 « Transport de marchandises », pourtant plus
-- orthodoxe : 611 et 6112 sont desactives dans le referentiel livre, et
-- regrouper toute la chaine d'acheminement d'un minerais sous 618 rend son
-- cout de revient lisible d'un coup d'oeil. Pour basculer sur 6112, il
-- suffit de le reactiver et de changer la table NATURES_FRAIS : rien dans la
-- comptabilisation ne depend de ce choix, seul le classement par nature
-- change.
--
-- Complete V67 plutot que de la modifier : cette migration-la est deja
-- appliquee sur les bases existantes, et en changer le contenu invaliderait
-- son checksum Flyway.

INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel, parent_id)
SELECT v.numero, v.libelle, p.type, p.classe, true, true, true, p.id
FROM (VALUES
    ('618', '618.3', 'Transport sur achat de minerais'),
    ('638', '638.2', 'Manutention')
) AS v(parent, numero, libelle)
JOIN comptes_ohada p ON p.numero = v.parent
WHERE NOT EXISTS (SELECT 1 FROM comptes_ohada c WHERE c.numero = v.numero);
