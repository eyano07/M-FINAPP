-- Le montant saisi sur une ligne de note de frais est HORS TAXE, pas TTC :
-- la TVA doit etre ajoutee par-dessus, jamais extraite du montant saisi.
-- Le taux applicable est fige a la ligne au moment de sa creation/modification
-- (meme principe que notes_frais.taux_engagement pour le change) : la TVA
-- effectivement comptabilisee au paiement doit correspondre exactement a ce
-- qui a ete annonce a la creation de la note, meme si le taux legal change
-- entre-temps.
ALTER TABLE lignes_note_frais ADD COLUMN taux_tva_applique NUMERIC(5,2);
