-- RCCM, ID. Nat, NIF et email de contact de l'entreprise, configurables par
-- l'ADMIN, affiches sur le papier a en-tete des documents officiels
-- (ordres de mission notamment).
ALTER TABLE parametres_entreprise ADD COLUMN rccm VARCHAR(80);
ALTER TABLE parametres_entreprise ADD COLUMN id_nat VARCHAR(80);
ALTER TABLE parametres_entreprise ADD COLUMN nif VARCHAR(40);
ALTER TABLE parametres_entreprise ADD COLUMN email VARCHAR(255);

-- Preremplit avec les valeurs deja imprimees en dur sur le papier a en-tete
-- officiel (/pdf/entete_mbsc.pdf) : sans cela, la ligne existante afficherait
-- des champs vides dans l'ecran Parametres alors que le document imprime,
-- lui, montre deja ces valeurs — source de confusion pour l'ADMIN qui les
-- verrait "manquantes" a tort. Un ADMIN qui les modifie ensuite fait
-- basculer le document sur ses propres valeurs (voir OrdreMissionPdfService).
UPDATE parametres_entreprise
SET rccm = 'CD/LSH/RCCM/24-B-1162', id_nat = '05-B-0500-N49144X', nif = 'A2420976C',
    email = 'contact@mbsc-drc.com · direction.generale@mbsc-drc.com'
WHERE rccm IS NULL;
