-- =====================================================================
-- V149 : widgets de l'écran d'accueil Android (18/11/2026)
-- =====================================================================
-- Le widget lit un résumé du compte (notes de frais à traiter, ses propres notes, trésorerie) sans ouvrir
-- l'application : il présente un jeton propre au téléphone, remis à la connexion dans l'application et retiré à la
-- déconnexion. Seule son empreinte SHA-256 est gardée. Il n'ouvre que la lecture du résumé (/widget/**).
CREATE TABLE IF NOT EXISTS jetons_widget (
    id              BIGSERIAL PRIMARY KEY,
    utilisateur_id  BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    empreinte       VARCHAR(64) NOT NULL UNIQUE,
    date_creation   TIMESTAMP   NOT NULL DEFAULT now(),
    date_vue        TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_jetons_widget_utilisateur ON jetons_widget (utilisateur_id);

-- Soldes de trésorerie sur l'écran d'accueil d'un téléphone : désactivés tant que l'administrateur ne les active pas.
ALTER TABLE parametres_entreprise ADD COLUMN IF NOT EXISTS widget_tresorerie BOOLEAN NOT NULL DEFAULT FALSE;
