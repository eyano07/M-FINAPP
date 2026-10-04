-- Majoration de prix par salle (ex. Salle VIP : +15% sur chaque article
-- vendu a une table de cette salle). 0 = prix inchange, comportement actuel.
ALTER TABLE salles_restaurant
    ADD COLUMN majoration_pourcentage NUMERIC(5,2) NOT NULL DEFAULT 0;
