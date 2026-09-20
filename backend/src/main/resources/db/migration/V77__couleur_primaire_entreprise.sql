-- Couleur de marque personnalisable (theme Vuetify + accents codes en dur
-- dans le frontend), a la place du vert fixe. Defaut = vert actuel : ne
-- change aucun rendu tant que l'admin ne choisit pas une autre couleur.
ALTER TABLE parametres_entreprise
    ADD COLUMN couleur_primaire VARCHAR(7) NOT NULL DEFAULT '#16A34A';
