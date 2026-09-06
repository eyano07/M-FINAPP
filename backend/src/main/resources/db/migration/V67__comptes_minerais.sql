-- Comptes du cycle minerais, versionnes plutot que saisis a la main.
--
-- Ces comptes etaient jusqu'ici crees un par un depuis l'ecran Plan comptable
-- (manuel = true) : ils n'existaient donc que dans la base ou quelqu'un les
-- avait saisis. Un deploiement sur une base neuve — production comprise —
-- rendait le module minerais inutilisable, la reception exigeant un compte
-- d'achat, de stock et de variation. Les voici crees automatiquement.
--
-- Deux familles :
--   * le cycle de valorisation lui-meme (achat / stock / variation / vente) ;
--   * les frais accessoires d'achat, qui entrent dans le cout d'acquisition
--     du chargement (voir MineraiService.ajouterCharge).
--
-- Idempotent : NOT EXISTS sur le numero. Les bases ou ces comptes ont deja ete
-- saisis a la main les conservent tels quels — leur libelle, eventuellement
-- personnalise, n'est pas ecrase. Le type et la classe sont herites du parent,
-- comme le fait la creation manuelle (voir AdminService/CompteRequest), ce qui
-- garantit qu'un compte de charge reste une charge meme si le referentiel de
-- base evolue.
--
-- manuel = true a dessein : ces comptes restent modifiables et desactivables
-- depuis l'ecran Plan comptable, contrairement au referentiel SYSCOHADA livre.

INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel, parent_id)
SELECT v.numero, v.libelle, p.type, p.classe, true, true, true, p.id
FROM (VALUES
    -- Cycle de valorisation du minerais
    ('6011', '6011.3',  'Achat minerais'),
    ('3111', '3111.1',  'Minerais'),
    ('6031', '6031.1',  'Variations des stocks de Minerais'),
    ('7011', '7011.1',  'Vente des Minerais'),
    -- Frais accessoires d'achat rattaches a un chargement
    ('618',  '618.1',   'Péage routier'),
    ('618',  '618.2',   'Per diem de route'),
    ('638',  '638.1',   'Pont bascule - pesée des camions'),
    ('646',  '646.1',   'Autorisations de transport de minerais')
) AS v(parent, numero, libelle)
JOIN comptes_ohada p ON p.numero = v.parent
WHERE NOT EXISTS (SELECT 1 FROM comptes_ohada c WHERE c.numero = v.numero);
