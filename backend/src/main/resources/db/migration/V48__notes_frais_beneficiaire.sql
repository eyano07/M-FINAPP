-- ---------------------------------------------------------------------------
-- Le nom du beneficiaire (destinataire reel du paiement) n'etait jamais
-- saisi : le recu de caisse retombait sur le createur de la note, qui n'est
-- pas toujours la personne payee (achat aupres d'un tiers, remboursement
-- pour un autre agent...). Colonne obligatoire desormais saisie a la
-- creation ; les notes existantes sont retro-remplies avec le nom du
-- createur pour ne pas casser la contrainte NOT NULL sur des donnees deja
-- en place.
-- ---------------------------------------------------------------------------
ALTER TABLE notes_frais ADD COLUMN beneficiaire VARCHAR(200);

UPDATE notes_frais nf
SET beneficiaire = TRIM(COALESCE(u.prenom, '') || ' ' || COALESCE(u.nom, ''))
FROM users u
WHERE nf.createur_id = u.id AND nf.beneficiaire IS NULL;

UPDATE notes_frais SET beneficiaire = 'Non renseigné' WHERE beneficiaire IS NULL OR beneficiaire = '';

ALTER TABLE notes_frais ALTER COLUMN beneficiaire SET NOT NULL;
