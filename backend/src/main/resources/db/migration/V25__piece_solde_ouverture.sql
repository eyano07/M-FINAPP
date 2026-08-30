-- =====================================================================
-- Marqueur « solde d'ouverture » sur la piece comptable.
--
-- Une reprise de soldes (a-nouveaux) est saisie a la date d'ouverture de
-- l'exercice, donc A L'INTERIEUR de la periode analysee. Sans marqueur,
-- la balance a 6 colonnes la comptait en « mouvements de la periode »
-- alors qu'elle appartient aux « soldes d'ouverture » : le compte de
-- resultat s'en trouvait fausse (une reprise de stock ou de charge
-- devenait un flux de l'exercice) et le controle de la balance affichait
-- une ouverture vide.
--
-- Le marqueur est porte par la PIECE et non deduit du libelle : un
-- libelle est du texte libre, sensible a la casse, aux accents et aux
-- fautes de frappe, et « Solde d'ouverture de la caisse » pourrait tres
-- bien designer une operation ordinaire.
-- =====================================================================

ALTER TABLE pieces_comptables
    ADD COLUMN solde_ouverture BOOLEAN NOT NULL DEFAULT FALSE;

-- Rattrapage des reprises deja saisies a la main : jusqu'ici, la seule
-- facon de les signaler etait de l'ecrire dans le libelle. On reprend
-- cette convention une derniere fois pour ne pas perdre l'intention de
-- l'utilisateur, en restant tolerant sur la casse, le pluriel et la
-- forme de l'apostrophe (droite ou typographique).
UPDATE pieces_comptables
SET solde_ouverture = TRUE
WHERE lower(libelle) LIKE '%solde%ouverture%';

COMMENT ON COLUMN pieces_comptables.solde_ouverture IS
    'true = reprise des a-nouveaux : les ecritures alimentent les colonnes '
    '« soldes d''ouverture » de la balance et sont exclues des mouvements de la periode.';
