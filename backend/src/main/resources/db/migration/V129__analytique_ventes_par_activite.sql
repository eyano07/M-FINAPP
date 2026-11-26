-- ---------------------------------------------------------------------------
-- V129 : la comptabilite analytique (classe 9) est alimentee automatiquement par les ventes.
--
-- Chaque vente validee est « reflechie » en classe 9, par activite : son chiffre d'affaires HT (les credits des comptes 7 de
-- sa piece de vente, au montant exact du grand livre) et le cout des marchandises vendues (sa sortie de stock au cout moyen,
-- ou le cout d'acquisition du camion de minerais) passent dans le resultat analytique de l'activite, en contrepartie des
-- comptes reflechis :
--     D 927 Produits reflechis      / C 98x1 Chiffre d'affaires HT de l'activite
--     D 98x2 Cout des ventes        / C 926 Charges reflechies
-- Le solde du compte 98x (regroupement de 98x1 et 98x2) est la marge de l'activite. Une vente annulee est contre-passee.
-- Le reflet ne bloque jamais une vente : si un compte manque ou si la periode est cloturee, la vente reste sans reflet et la
-- reprise faite au demarrage de l'application la rattrape (elle reprend aussi les ventes validees avant cette version).
--
-- Activites (selon l'article vendu) : Bar = boissons ; Cuisine = plats (et provisions) ; Marchandises = marchandises et
-- consommables ; Minerais = articles minerais (camions) ; Services = services.
--
-- Comptes « systeme » (manuel = FALSE), comme les rubriques 92 a 99 de V126 ; type NULL : ni bilan ni compte de resultat.
-- ---------------------------------------------------------------------------

INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel, contenu) VALUES
    ('926', 'Charges réfléchies — coût des ventes', NULL, 9, TRUE, TRUE, FALSE,
     'Contrepartie analytique du coût des marchandises vendues (sorties de stock des ventes) repris dans le résultat analytique de chaque activité. Alimenté automatiquement à la validation d''une vente.'),
    ('927', 'Produits réfléchis — ventes', NULL, 9, TRUE, TRUE, FALSE,
     'Contrepartie analytique du chiffre d''affaires HT des ventes (comptes 7) repris dans le résultat analytique de chaque activité. Alimenté automatiquement à la validation d''une vente.'),

    ('981', 'Résultat analytique — Bar (boissons)', NULL, 9, FALSE, TRUE, FALSE,
     'Résultat analytique de l''activité Bar : chiffre d''affaires HT des boissons vendues (9811) moins leur coût (9812). Le solde créditeur est la marge.'),
    ('9811', 'Bar — chiffre d''affaires HT', NULL, 9, TRUE, TRUE, FALSE,
     'Chiffre d''affaires HT des ventes de boissons, reflété automatiquement depuis les pièces de vente.'),
    ('9812', 'Bar — coût des ventes', NULL, 9, TRUE, TRUE, FALSE,
     'Coût des boissons vendues (sortie de stock au coût moyen pondéré, transport compris), reflété automatiquement.'),

    ('982', 'Résultat analytique — Cuisine (plats)', NULL, 9, FALSE, TRUE, FALSE,
     'Résultat analytique de l''activité Cuisine : chiffre d''affaires HT des plats vendus (9821) moins leur coût de production (9822). Le solde créditeur est la marge.'),
    ('9821', 'Cuisine — chiffre d''affaires HT', NULL, 9, TRUE, TRUE, FALSE,
     'Chiffre d''affaires HT des ventes de plats, reflété automatiquement depuis les pièces de vente.'),
    ('9822', 'Cuisine — coût des ventes', NULL, 9, TRUE, TRUE, FALSE,
     'Coût des plats vendus (sortie de stock au coût de production), reflété automatiquement.'),

    ('983', 'Résultat analytique — Marchandises', NULL, 9, FALSE, TRUE, FALSE,
     'Résultat analytique de l''activité Marchandises : chiffre d''affaires HT (9831) moins coût d''achat des marchandises vendues (9832). Le solde créditeur est la marge.'),
    ('9831', 'Marchandises — chiffre d''affaires HT', NULL, 9, TRUE, TRUE, FALSE,
     'Chiffre d''affaires HT des ventes de marchandises et consommables, reflété automatiquement.'),
    ('9832', 'Marchandises — coût des ventes', NULL, 9, TRUE, TRUE, FALSE,
     'Coût d''achat des marchandises vendues (sortie de stock au coût moyen pondéré), reflété automatiquement.'),

    ('984', 'Résultat analytique — Minerais', NULL, 9, FALSE, TRUE, FALSE,
     'Résultat analytique de l''activité Minerais : chiffre d''affaires HT des camions vendus (9841) moins leur coût d''acquisition (9842). Le solde créditeur est la marge.'),
    ('9841', 'Minerais — chiffre d''affaires HT', NULL, 9, TRUE, TRUE, FALSE,
     'Chiffre d''affaires HT des ventes de minerais (camions), reflété automatiquement.'),
    ('9842', 'Minerais — coût des ventes', NULL, 9, TRUE, TRUE, FALSE,
     'Coût d''acquisition des camions vendus (prix d''achat et frais accessoires), reflété automatiquement.'),

    ('985', 'Résultat analytique — Services', NULL, 9, FALSE, TRUE, FALSE,
     'Résultat analytique de l''activité Services : chiffre d''affaires HT des prestations (9851) moins leur coût (9852, sans stock : saisie manuelle). Le solde créditeur est la marge.'),
    ('9851', 'Services — chiffre d''affaires HT', NULL, 9, TRUE, TRUE, FALSE,
     'Chiffre d''affaires HT des prestations de services, reflété automatiquement.'),
    ('9852', 'Services — coût des ventes', NULL, 9, TRUE, TRUE, FALSE,
     'Coût des prestations vendues : un service n''a pas de stock, ce compte ne reçoit que des écritures manuelles.')
ON CONFLICT (numero) DO NOTHING;

-- Rattachement hierarchique (plus long prefixe strict existant), comme V126.
UPDATE comptes_ohada c
SET parent_id = (
    SELECT p.id FROM comptes_ohada p
    WHERE p.numero <> c.numero
      AND c.numero LIKE p.numero || '%'
    ORDER BY length(p.numero) DESC
    LIMIT 1
)
WHERE c.numero IN ('926', '927', '981', '9811', '9812', '982', '9821', '9822', '983', '9831', '9832',
                   '984', '9841', '9842', '985', '9851', '9852');

-- Regle du plan (V23) : un compte subdivise n'est plus imputable, sauf s'il porte deja des ecritures.
UPDATE comptes_ohada p
SET imputable = FALSE
WHERE p.numero IN ('92', '98')
  AND NOT EXISTS (SELECT 1 FROM grand_livre e WHERE e.compte_id = p.id);

-- Piece du journal ANALYTIQUE qui reflete la vente (NULL tant qu'elle ne l'est pas : la reprise au demarrage la rattrape).
ALTER TABLE ventes ADD COLUMN piece_analytique_id BIGINT REFERENCES pieces_comptables(id);
