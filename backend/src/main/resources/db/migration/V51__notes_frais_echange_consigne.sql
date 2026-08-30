-- Achat de boissons du restaurant via note de frais : l'échange de consigne
-- (rendre les bouteilles vides équivalentes) devient une propriété de la
-- ligne d'achat de marchandise, appliquée au stock de vides au paiement de
-- la note (voir RestaurantService.enregistrerAchatVidesDepuisNoteFraisInterne),
-- au même titre que l'entrée en stock de la boisson elle-même.
ALTER TABLE lignes_note_frais ADD COLUMN echange_consigne BOOLEAN NOT NULL DEFAULT FALSE;
