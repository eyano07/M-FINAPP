-- =====================================================================
-- V148 : téléphones inscrits aux notifications push (application Android, 17/11/2026)
-- =====================================================================
-- Un appareil = un jeton Firebase Cloud Messaging, rattaché au compte connecté sur ce téléphone. Le jeton passe au
-- dernier compte qui s'y connecte (un téléphone prêté ne reçoit pas les notes de l'ancien utilisateur) et disparaît à la
-- déconnexion, ou quand Firebase le déclare périmé (application désinstallée).
CREATE TABLE IF NOT EXISTS appareils_push (
    id              BIGSERIAL PRIMARY KEY,
    utilisateur_id  BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    jeton           VARCHAR(512) NOT NULL UNIQUE,
    plateforme      VARCHAR(20)  NOT NULL DEFAULT 'ANDROID',
    date_creation   TIMESTAMP    NOT NULL DEFAULT now(),
    date_vue        TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_appareils_push_utilisateur ON appareils_push (utilisateur_id);
