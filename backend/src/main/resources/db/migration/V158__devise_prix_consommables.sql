-- Devise du prix d'achat d'un consommable : USD (par défaut) ou CDF (francs congolais).
ALTER TABLE articles ADD COLUMN devise_prix VARCHAR(3) NOT NULL DEFAULT 'USD';
ALTER TABLE articles ADD CONSTRAINT ck_articles_devise_prix CHECK (devise_prix IN ('USD', 'CDF'));
