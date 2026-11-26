-- Rend la comptabilisation de la paie optionnelle : parametre global
-- (bascule ecran Parametres de paie) qui decide si la cloture de periode
-- poste ou non une piece comptable par bulletin (salaire net + charges
-- patronales CNSS/ONEM/INPP). Actif par defaut : preserve le comportement
-- existant pour les installations deja en production.
ALTER TABLE drh_parametres_paie ADD COLUMN comptabiliser_paie BOOLEAN NOT NULL DEFAULT TRUE;

-- Horodatage de cloture, desormais independant de la piece comptable
-- (facultative) : c'est ce champ qui verrouille le bulletin contre toute
-- modification ulterieure, que la paie soit comptabilisee ou non.
ALTER TABLE drh_bulletins_paie ADD COLUMN date_cloture TIMESTAMP;

-- Bulletins deja clotures sous l'ancien regime (comptabilisation toujours
-- active) : renseigne retroactivement pour rester coherent avec la nouvelle
-- regle de verrouillage basee sur date_cloture plutot que sur piece_comptable_id.
UPDATE drh_bulletins_paie SET date_cloture = now() WHERE piece_comptable_id IS NOT NULL;
