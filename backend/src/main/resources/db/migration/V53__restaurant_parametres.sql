-- Paramètres du module Restaurant (ligne unique) : devise d'affichage par
-- défaut (USD ou CDF) pour la carte, le stock et les tableaux de bord.
CREATE TABLE restaurant_parametres (
    id                BIGINT PRIMARY KEY,
    devise_affichage  VARCHAR(3) NOT NULL DEFAULT 'USD'
);

INSERT INTO restaurant_parametres (id, devise_affichage) VALUES (1, 'USD');
